package org.neo4j.spark.stream

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.read.{InputPartition, PartitionReaderFactory, Scan, SupportsPushDownFilters}
import org.apache.spark.sql.connector.read.streaming.{MicroBatchStream, Offset, ReadLimit}
import org.apache.spark.sql.sources.{Filter, GreaterThan}
import org.apache.spark.sql.types.StructType
import org.neo4j.spark.reader.{Neo4jPartition, SimplePartitionReaderFactory}
import org.neo4j.spark.service.SchemaService
import org.neo4j.spark.streaming.OffsetStorage
import org.neo4j.spark.util._

import java.util.function.Supplier
import java.util.{Collections, Optional, function}
import java.{lang, util}
import scala.collection.JavaConverters._

class Neo4jMicroBatchReader(private val optionalSchema: Optional[StructType],
                            private val neo4jOptions: Neo4jOptions,
                            private val jobId: String)
  extends MicroBatchStream
    with Logging {

  private val driverCache = new DriverCache(neo4jOptions.connection, jobId)

  private lazy val scriptResult = {
    val schemaService = new SchemaService(neo4jOptions, driverCache)
    schemaService.createOptimizations()
    val scriptResult = schemaService.execute(neo4jOptions.script)
    schemaService.close()
    scriptResult
  }

  private var lastUsedOffset: Neo4jOffset = null

  private var filters: Array[Filter] = Array[Filter]()

  override def deserializeOffset(json: String): Offset = Neo4jOffset(json.toLong)

  override def commit(end: Offset): Unit = { }

  override def planInputPartitions(start: Offset, end: Offset): Array[InputPartition] = {
    this.filters = if (start.asInstanceOf[Neo4jOffset].offset != StreamingFrom.ALL.value()) {
      val prop = Neo4jUtil.getStreamingPropertyName(neo4jOptions)
      Array(GreaterThan(prop, latestOffset().asInstanceOf[Neo4jOffset].offset))
    }
    else {
      this.filters
    }

    val partitions = Neo4jUtil.callSchemaService(
      neo4jOptions, jobId, filters,
      { schemaService => schemaService.skipLimitFromPartition() }
    )

    partitions
      .map(p => Neo4jStreamingPartition(p, filters))
      .toArray
  }

  override def stop(): Unit = {
    OffsetStorage.clearForJobId(jobId)
    new DriverCache(neo4jOptions.connection, jobId).close()
  }

  override def latestOffset(): Offset = {
    val lastReadOffset: lang.Long = OffsetStorage.getLastOffset(jobId)

    // the current offset is build by the last read offset, if any, or from the last used offset
    var currentOffset: Neo4jOffset = if (lastReadOffset == null) {
      // if the last used offset is not set yet, we use the initial offset
      if (lastUsedOffset == null) {
        lastUsedOffset = initialOffset().asInstanceOf[Neo4jOffset]
      }

      lastUsedOffset
    }
    else {
      Neo4jOffset(lastReadOffset)
    }

    // if in the last cycle the partition returned
    // an empty result this means that start will be set equal end,
    // so we check if
    if (lastUsedOffset != null && currentOffset.offset == lastUsedOffset.offset) {
      // there is a database change by invoking the last offset inserted
      val lastNeo4jOffset = Neo4jUtil.callSchemaService[Long](neo4jOptions, jobId, filters, {
        schemaService =>
          try {
            schemaService.lastOffset()
          } catch {
            case _ => -1L
          }
      })
      // if a the last offset into the database is changed
      if (lastNeo4jOffset > currentOffset.offset) {
        // we just increment the end offset in order to push spark to do a new query over the database
        currentOffset = Neo4jOffset(currentOffset.offset + 1)
      }
    }

    lastUsedOffset = currentOffset
    currentOffset
  }

  override def initialOffset(): Offset = Neo4jOffset(neo4jOptions.streamingOptions.from.value())

  override def createReaderFactory(): PartitionReaderFactory = {
    new SimpleStreamingPartitionReaderFactory(
      neo4jOptions, optionalSchema.orElse(new StructType()), jobId, scriptResult
    )
  }
}
