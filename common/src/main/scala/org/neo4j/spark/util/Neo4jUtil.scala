package org.neo4j.spark.util

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.{JsonSerializer, ObjectMapper, SerializerProvider}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{GenericRowWithSchema, UnsafeArrayData, UnsafeMapData, UnsafeRow}
import org.apache.spark.sql.catalyst.util.{ArrayBasedMapData, ArrayData, DateTimeUtils}
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.neo4j.cypherdsl.core.{Condition, Cypher, Expression, Functions, Property, PropertyContainer}
import org.neo4j.driver.exceptions.{Neo4jException, ServiceUnavailableException, SessionExpiredException, TransientException}
import org.neo4j.driver.internal._
import org.neo4j.driver.types.{Entity, Path}
import org.neo4j.driver.{Session, Transaction, Value, Values}
import org.neo4j.spark.service.SchemaService
import org.neo4j.spark.util.Neo4jImplicits.{EntityImplicits, _}
import org.slf4j.Logger

import java.time._
import java.time.format.DateTimeFormatter
import java.util.Properties
import org.neo4j.spark.util.Neo4jImplicits._

import scala.annotation.tailrec
import scala.collection.JavaConverters._

object Neo4jUtil {

  val NODE_ALIAS = "n"
  private val INTERNAL_ID_FIELD_NAME = "id"
  val INTERNAL_ID_FIELD = s"<${INTERNAL_ID_FIELD_NAME}>"
  private val INTERNAL_LABELS_FIELD_NAME = "labels"
  val INTERNAL_LABELS_FIELD = s"<${INTERNAL_LABELS_FIELD_NAME}>"
  val INTERNAL_REL_ID_FIELD = s"<rel.${INTERNAL_ID_FIELD_NAME}>"
  val INTERNAL_REL_TYPE_FIELD = "<rel.type>"
  val RELATIONSHIP_SOURCE_ALIAS = "source"
  val RELATIONSHIP_TARGET_ALIAS = "target"
  val INTERNAL_REL_SOURCE_ID_FIELD = s"<${RELATIONSHIP_SOURCE_ALIAS}.${INTERNAL_ID_FIELD_NAME}>"
  val INTERNAL_REL_TARGET_ID_FIELD = s"<${RELATIONSHIP_TARGET_ALIAS}.${INTERNAL_ID_FIELD_NAME}>"
  val INTERNAL_REL_SOURCE_LABELS_FIELD = s"<${RELATIONSHIP_SOURCE_ALIAS}.${INTERNAL_LABELS_FIELD_NAME}>"
  val INTERNAL_REL_TARGET_LABELS_FIELD = s"<${RELATIONSHIP_TARGET_ALIAS}.${INTERNAL_LABELS_FIELD_NAME}>"
  val RELATIONSHIP_ALIAS = "rel"

  private val properties = new Properties()
  properties.load(Thread.currentThread().getContextClassLoader().getResourceAsStream("neo4j-spark-connector.properties"))

  def closeSafety(autoCloseable: AutoCloseable, logger: Logger = null): Unit = {
    try {
      autoCloseable match {
        case s: Session => if (s.isOpen) s.close()
        case t: Transaction => if (t.isOpen) t.close()
        case null => ()
        case _ => autoCloseable.close()
      }
    } catch {
      case t: Throwable => if (logger != null) logger
        .warn(s"Cannot close ${autoCloseable.getClass.getSimpleName} because of the following exception:", t)
    }
  }

  val mapper = new ObjectMapper()
  private val module = new SimpleModule("Neo4jApocSerializer")
  module.addSerializer(classOf[Path], new JsonSerializer[Path]() {
    override def serialize(path: Path,
                           jsonGenerator: JsonGenerator,
                           serializerProvider: SerializerProvider): Unit = jsonGenerator.writeString(path.toString)
  })
  module.addSerializer(classOf[Entity], new JsonSerializer[Entity]() {
    override def serialize(entity: Entity,
                           jsonGenerator: JsonGenerator,
                           serializerProvider: SerializerProvider): Unit = jsonGenerator.writeObject(entity.toMap())
  })
  mapper.registerModule(module)

  @tailrec
  def extractStructType(dataType: DataType): StructType = dataType match {
    case structType: StructType => structType
    case mapType: MapType => extractStructType(mapType.valueType)
    case arrayType: ArrayType => extractStructType(arrayType.elementType)
    case _ => throw new UnsupportedOperationException(s"$dataType not supported")
  }

  def flattenMap(map: java.util.Map[String, AnyRef],
                 prefix: String = ""): java.util.Map[String, AnyRef] = map.asScala.flatMap(t => {
    val key: String = if (prefix != "") s"${prefix.quote()}.${t._1.quote()}" else t._1.quote()
    t._2 match {
      case nestedMap: Map[String, AnyRef] => flattenMap(nestedMap.asJava, key).asScala.toSeq
      case _ => Seq((key, t._2))
    }
  })
    .toMap
    .asJava

  def isLong(str: String): Boolean = {
    if (str == null) {
      false
    } else {
      try {
        str.trim.toLong
        true
      } catch {
        case nfe: NumberFormatException => false
        case t: Throwable => throw t
      }
    }
  }

  def connectorVersion: String = properties.getOrDefault("version", "UNKNOWN").toString

  def getCorrectProperty(container: PropertyContainer, attribute: String): Property = {
    container.property(attribute.split('.'): _*)
  }

  def paramsFromFilters(filters: Array[Filter]): Map[String, Any] = {
    filters.flatMap(f => f.flattenFilters).map(_.getAttributeAndValue)
      .filter(_.nonEmpty)
      .map(valAndAtt => valAndAtt.head.toString.unquote() -> toParamValue(valAndAtt(1)))
      .toMap
  }

  def toParamValue(value: Any): Any = {
    value match {
      case date: java.sql.Date => date.toString
      case timestamp: java.sql.Timestamp => timestamp.toLocalDateTime.toString
      case _ => value
    }
  }

  def valueToCypherExpression(attribute: String, value: Any): Expression = {
    val parameter = Cypher.parameter(attribute.toParameterName(value))
    value match {
      case d: java.sql.Date => Functions.date(parameter)
      case t: java.sql.Timestamp => Functions.localdatetime(parameter)
      case _ => parameter
    }
  }

  def mapSparkFiltersToCypher(filter: Filter, container: PropertyContainer, attributeAlias: Option[String] = None): Condition = {
    filter match {
      case eqns: EqualNullSafe =>
        val parameter = valueToCypherExpression(eqns.attribute, eqns.value)
        val property = getCorrectProperty(container, attributeAlias.getOrElse(eqns.attribute))
        property.isNull.and(parameter.isNull)
          .or(
            property.isEqualTo(parameter)
          )
      case eq: EqualTo =>
        getCorrectProperty(container, attributeAlias.getOrElse(eq.attribute))
          .isEqualTo(valueToCypherExpression(eq.attribute, eq.value))
      case gt: GreaterThan =>
        getCorrectProperty(container, attributeAlias.getOrElse(gt.attribute))
          .gt(valueToCypherExpression(gt.attribute, gt.value))
      case gte: GreaterThanOrEqual =>
        getCorrectProperty(container, attributeAlias.getOrElse(gte.attribute))
          .gte(valueToCypherExpression(gte.attribute, gte.value))
      case lt: LessThan =>
        getCorrectProperty(container, attributeAlias.getOrElse(lt.attribute))
          .lt(valueToCypherExpression(lt.attribute, lt.value))
      case lte: LessThanOrEqual =>
        getCorrectProperty(container, attributeAlias.getOrElse(lte.attribute))
          .lte(valueToCypherExpression(lte.attribute, lte.value))
      case in: In =>
        getCorrectProperty(container, attributeAlias.getOrElse(in.attribute))
          .in(valueToCypherExpression(in.attribute, in.values))
      case startWith: StringStartsWith =>
        getCorrectProperty(container, attributeAlias.getOrElse(startWith.attribute))
          .startsWith(valueToCypherExpression(startWith.attribute, startWith.value))
      case endsWith: StringEndsWith =>
        getCorrectProperty(container, attributeAlias.getOrElse(endsWith.attribute))
          .endsWith(valueToCypherExpression(endsWith.attribute, endsWith.value))
      case contains: StringContains =>
        getCorrectProperty(container, attributeAlias.getOrElse(contains.attribute))
          .contains(valueToCypherExpression(contains.attribute, contains.value))
      case notNull: IsNotNull => getCorrectProperty(container, attributeAlias.getOrElse(notNull.attribute)).isNotNull
      case isNull: IsNull => getCorrectProperty(container, attributeAlias.getOrElse(isNull.attribute)).isNull
      case not: Not => mapSparkFiltersToCypher(not.child, container, attributeAlias).not()
      case filter@(_: Filter) => throw new IllegalArgumentException(s"Filter of type `$filter` is not supported.")
    }
  }

  def getStreamingPropertyName(options: Neo4jOptions) = options.query.queryType match {
    case QueryType.RELATIONSHIP => s"rel.${options.streamingOptions.propertyName}"
    case _ => options.streamingOptions.propertyName
  }

  def callSchemaService[T](neo4jOptions: Neo4jOptions,
                           jobId: String,
                           filters: Array[Filter],
                           function: SchemaService => T): T = {
    val driverCache = new DriverCache(neo4jOptions.connection, jobId)
    val schemaService = new SchemaService(neo4jOptions, driverCache, filters)
    var hasError = false
    try {
      function(schemaService)
    } catch {
      case e: Throwable => {
        hasError = true
        throw e
      }
    } finally {
      schemaService.close()
      if (hasError) {
        driverCache.close()
      }
    }
  }

  def isRetryableException(neo4jTransientException: Neo4jException) = (neo4jTransientException.isInstanceOf[SessionExpiredException]
    || neo4jTransientException.isInstanceOf[TransientException]
    || neo4jTransientException.isInstanceOf[ServiceUnavailableException])
}
