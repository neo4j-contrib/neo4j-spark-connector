package org.neo4j.spark.stream

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.v2.DataSourceOptions
import org.apache.spark.sql.sources.v2.reader.streaming.{MicroBatchReader, Offset}
import org.apache.spark.sql.sources.v2.reader.{InputPartition, SupportsPushDownFilters}
import org.apache.spark.sql.sources.{Filter, GreaterThan}
import org.apache.spark.sql.types.StructType
import org.neo4j.spark.service.PartitionSkipLimit
import org.neo4j.spark.streaming.OffsetStorage
import org.neo4j.spark.util.{Neo4jOptions, Neo4jUtil, StreamingFrom, Validations}

import java.util
import java.util.function.Supplier
import java.util.{Collections, Optional, function}
import scala.collection.JavaConverters._

class Neo4jMicroBatchReader(private val optionalSchema: Optional[StructType],
                            private val options: DataSourceOptions,
                            private val jobId: String)
  extends MicroBatchReader
    with SupportsPushDownFilters
    with Logging {

  private val neo4jOptions: Neo4jOptions = new Neo4jOptions(options.asMap())
    .validate(options => Validations.read(options, jobId))

  private var filters: Array[Filter] = Array[Filter]()

  private var startOffset: Neo4jOffset24 = null

  private var endOffset: Neo4jOffset24 = null

  private val schema = optionalSchema.orElseGet(new Supplier[StructType] {
    override def get(): StructType = Neo4jUtil.callSchemaService(neo4jOptions, jobId, { schemaService => schemaService.struct() })
  })

  override def readSchema(): StructType = schema

  override def setOffsetRange(start: Optional[Offset], end: Optional[Offset]): Unit = {
    startOffset = start
      .orElseGet(new Supplier[Offset] {
        override def get(): Offset = Neo4jOffset24(neo4jOptions.streamingOptions.from.value())
      })
      .asInstanceOf[Neo4jOffset24]
    val lastOffset = Neo4jOffset24(OffsetStorage.getLastOffset(jobId))
    endOffset = end
      .map(new function.Function[Offset, Offset] {
        override def apply(o: Offset): Offset = if (lastOffset == null || o.asInstanceOf[Neo4jOffset24].offset > lastOffset.offset) o else lastOffset
      })
      .orElseGet(new Supplier[Offset] {
        override def get(): Offset = if (lastOffset == null) Neo4jOffset24(startOffset.offset + 1) else lastOffset
      })
      .asInstanceOf[Neo4jOffset24]
  }

  override def getStartOffset: Offset = startOffset

  override def getEndOffset: Offset = endOffset

  override def deserializeOffset(json: String): Offset = Neo4jOffset24(json.toLong)

  override def commit(end: Offset): Unit = {}

  override def planInputPartitions: util.ArrayList[InputPartition[InternalRow]] = {
    val (filters, partitions) = if (startOffset.offset != StreamingFrom.ALL.value()) {
      val prop = Neo4jUtil.getStreamingPropertyName(neo4jOptions)
      (this.filters :+ GreaterThan(prop, endOffset.offset),
        Seq(PartitionSkipLimit.EMPTY))
    } else {
      (this.filters, Neo4jUtil.callSchemaService(neo4jOptions, jobId, { schemaService => schemaService.skipLimitFromPartition() }))
    }
    val collection = partitions
      .map(partitionSkipLimit => new Neo4jStreamingInputPartition(neo4jOptions, filters, schema, jobId,
        partitionSkipLimit, Collections.emptyList(), new StructType()))
      .toList
      .asJavaCollection
    new util.ArrayList[InputPartition[InternalRow]](collection)
  }

  override def stop(): Unit = {
    OffsetStorage.clearForJobId(jobId)
  }

  override def pushFilters(filtersArray: Array[Filter]): Array[Filter] = {
    if (neo4jOptions.pushdownFiltersEnabled) {
      filters = filtersArray
    }

    filtersArray
  }

  override def pushedFilters(): Array[Filter] = filters
}
