package org.neo4j.spark.reader

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.sources.v2.reader.{InputPartition, InputPartitionReader}
import org.apache.spark.sql.types.StructType
import org.neo4j.spark.service._
import org.neo4j.spark.util.Neo4jImplicits.StructTypeImplicit
import org.neo4j.spark.util.Neo4jOptions

class Neo4jInputPartition(private val options: Neo4jOptions,
                          private val schema: StructType,
                          private val jobId: String,
                          private val partitionSkipLimit: PartitionSkipLimit,
                          private val scriptResult: java.util.List[java.util.Map[String, AnyRef]],
                          private val requiredColumns: StructType,
                          private val readStrategy: Neo4jQueryReadStrategy,
                          private val eventFields: java.util.Map[String, AnyRef] = new java.util.HashMap())
    extends InputPartition[InternalRow] {

  override def createPartitionReader(): InputPartitionReader[InternalRow] = new Neo4jInputPartitionReader(options, schema,
    jobId, partitionSkipLimit, scriptResult, requiredColumns, readStrategy, eventFields)
}