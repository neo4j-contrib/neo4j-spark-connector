package org.neo4j.spark

import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.streaming.StreamingQuery
import org.hamcrest.Matchers
import org.junit.{After, Test}
import org.neo4j.spark.Assert.ThrowingSupplier

import java.util.UUID
import java.util.concurrent.TimeUnit

class DataSourceStreamingTSE extends SparkConnectorScalaBaseTSE {

  private var query: StreamingQuery = null

  @After
  def close(): Unit = {
    if (query != null) {
      query.stop()
    }
  }

  @Test
  def testSinkStream(): Unit = {
    implicit val ctx = ss.sqlContext
    import ss.implicits._
    val memStream = MemoryStream[Int]
    val recordSize = 2000
    val partition = 5
    val checkpointLocation = "/tmp/checkpoint/" + UUID.randomUUID().toString
    query = memStream.toDF().writeStream
      .format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", "Timestamp")
      .option("checkpointLocation", checkpointLocation)
      .option("node.keys", "value")
      .start()
    (1 to partition).foreach(index => {
      // we send the total of records in 5 times
      val start = ((index - 1) * recordSize) + 1
      val end = index * recordSize
      memStream.addData((start to end).toArray)
    })

    Assert.assertEventually(new ThrowingSupplier[Boolean, Exception] {
      override def get(): Boolean = {
        val dataFrame = ss.read.format(classOf[DataSource].getName)
          .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
          .option("labels", "Timestamp")
          .load()

        val collect = dataFrame.collect()
        val data = if (dataFrame.columns.contains("value")) {
          collect
            .map(row => row.getAs[Long]("value").toInt)
            .sorted
        } else {
          Array.empty[Int]
        }
        data.toList == (1 to (recordSize * partition)).toList
      }
    }, Matchers.equalTo(true), 30L, TimeUnit.SECONDS)
  }
}