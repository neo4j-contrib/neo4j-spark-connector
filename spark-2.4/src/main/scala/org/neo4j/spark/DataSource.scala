package org.neo4j.spark

import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.sources.DataSourceRegister
import org.apache.spark.sql.sources.v2._
import org.apache.spark.sql.sources.v2.reader.streaming.MicroBatchReader
import org.apache.spark.sql.sources.v2.reader.DataSourceReader
import org.apache.spark.sql.sources.v2.writer.DataSourceWriter
import org.apache.spark.sql.sources.v2.writer.streaming.StreamWriter
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.StructType
import org.neo4j.spark.reader.Neo4jDataSourceReader
import org.neo4j.spark.stream.Neo4jDataSourceStreamReader
import org.neo4j.spark.stream.Neo4jDataSourceStreamWriter
import org.neo4j.spark.util.{Neo4jOptions, Validations}
import org.neo4j.spark.writer.Neo4jDataSourceWriter


class DataSource extends DataSourceV2
  with StreamWriteSupport
  with ReadSupport
  with DataSourceRegister
  with WriteSupport {
import java.util.{Optional, UUID}

class DataSource extends DataSourceV2
  with MicroBatchReadSupport
  with StreamWriteSupport
  with ReadSupport
  with DataSourceRegister
  with WriteSupport {

  Validations.version("2.4.*")

  private val jobId: String = UUID.randomUUID().toString

  def createReader(options: DataSourceOptions) = new Neo4jDataSourceReader(options, jobId)

  override def createReader(schema: StructType, options: DataSourceOptions): DataSourceReader = new Neo4jDataSourceReader(options, jobId, schema)

  override def shortName: String = "neo4j"

  override def createWriter(jobId: String,
                            structType: StructType,
                            saveMode: SaveMode,
                            options: DataSourceOptions): Optional[DataSourceWriter] = {
    Validations.supportedSaveMode(saveMode.toString)
    Optional.of(new Neo4jDataSourceWriter(jobId, structType, saveMode, options))
  }

  override def createMicroBatchReader(optional: Optional[StructType], s: String, dataSourceOptions: DataSourceOptions): MicroBatchReader = {
    new Neo4jDataSourceStreamReader(dataSourceOptions, jobId)
  }

  @volatile
  private var streamWriter: Neo4jDataSourceStreamWriter = null

  def isNewInstance(queryId: String,
                    schema: StructType,
                    options: DataSourceOptions): Boolean = (streamWriter == null ||
    streamWriter.queryId != queryId ||
    streamWriter.schema != schema ||
    streamWriter.options != options)

  override def createStreamWriter(queryId: String, schema: StructType, mode: OutputMode, options: DataSourceOptions): StreamWriter = {
    val streamingSaveMode = options.get(Neo4jOptions.SAVE_MODE)
      .orElse(Neo4jOptions.DEFAULT_SAVE_MODE.toString)
    Validations.supportedSaveMode(streamingSaveMode)
    if (isNewInstance(queryId, schema, options)) {
      streamWriter = new Neo4jDataSourceStreamWriter(queryId, schema, options, SaveMode.valueOf(streamingSaveMode))
    }
    streamWriter
  }
}