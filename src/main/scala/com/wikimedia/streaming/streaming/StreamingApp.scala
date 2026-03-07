package com.wikimedia.streaming.streaming

import com.wikimedia.streaming.models.WikiEditEvent
import com.wikimedia.streaming.utils.{KafkaConfig, SparkSessionFactory}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.TimestampType

/**
 * StreamingApp — main entry point for the Spark Structured Streaming pipeline.
 *
 * This object orchestrates the entire pipeline:
 *  1. Creates a [[org.apache.spark.sql.SparkSession]] via [[SparkSessionFactory]].
 *  2. Reads raw JSON events from Kafka.
 *  3. Parses JSON, filters for edit events, and enriches with a proper event-time column.
 *  4. Applies a watermark to bound the streaming state.
 *  5. Starts three independent analytics jobs in parallel.
 *  6. Waits until any query terminates (or an exception is thrown).
 *
 * ============================================================================
 * WATERMARKING — why it is required and how it works
 * ============================================================================
 *
 * === The problem: late-arriving events ===
 * In a real-time pipeline events rarely arrive in perfect chronological order.
 * Network jitter, retries, and buffering mean that an event timestamped at
 * 12:00:00 might be received at 12:00:45.  For window aggregations keyed on
 * event time (e.g. "count edits per 10-minute window"), Spark must decide:
 *  - How long should it wait before finalising a window?
 *  - When is it safe to discard the accumulated state for a closed window?
 *
 * Without a bound on lateness, Spark would have to keep state for ALL past
 * windows forever — unbounded memory growth that will eventually cause OOM.
 *
 * === The solution: watermarks ===
 * `.withWatermark("event_time", "10 minutes")` tells Spark:
 *   "The current watermark is: max(event_time seen so far) − 10 minutes."
 *
 * Events whose `event_time` is OLDER than the watermark are considered "late"
 * and are silently dropped (they will not be included in any window result).
 * Windows whose end time is before the watermark can be safely finalised and
 * their state discarded from memory.
 *
 * === What gets accepted vs dropped ===
 *  - event_time >= watermark → accepted and included in the correct window
 *  - event_time < watermark  → dropped (logged by Spark's internal metrics)
 *
 * === Without watermarking ===
 * State would accumulate indefinitely.  With a 10-minute window sliding every
 * 30 seconds there would be 20 open windows at any instant; without cleanup
 * that number grows without bound.
 */
object StreamingApp {

  def main(args: Array[String]): Unit = {

    // -------------------------------------------------------------------------
    // Step 1: Create SparkSession
    // Delta Lake extensions and catalog are configured inside the factory.
    // -------------------------------------------------------------------------
    val spark = SparkSessionFactory.create()
    import spark.implicits._

    println("[StreamingApp] SparkSession created successfully")

    // -------------------------------------------------------------------------
    // Step 2: Read raw events from Kafka
    //
    // - startingOffsets=latest  → only process new events; skip historical backlog
    //   on startup.  Change to "earliest" to replay from the beginning.
    // - failOnDataLoss=false    → continue if Kafka offsets were deleted
    //   (e.g. due to log retention) instead of throwing an exception.
    // -------------------------------------------------------------------------
    val kafkaStream = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers",  KafkaConfig.bootstrapServers)
      .option("subscribe",                KafkaConfig.topic)
      .option("startingOffsets",          "latest")
      .option("failOnDataLoss",           "false")
      .load()

    println("[StreamingApp] Kafka source configured")

    // -------------------------------------------------------------------------
    // Step 3: Parse JSON payload
    //
    // Kafka stores message bytes in a binary "value" column.  We:
    //  a) Cast it to String.
    //  b) Parse the JSON using WikiEditEvent.schema via from_json().
    //  c) Flatten the nested "event" struct with select(event.*).
    //  d) Filter out rows where "id" is null (malformed / non-edit messages).
    //  e) Filter for type === "edit" to drop log, categorize, and new-page events.
    // -------------------------------------------------------------------------
    val parsedEvents = kafkaStream
      .select(from_json(col("value").cast("string"), WikiEditEvent.schema).alias("event"))
      .select($"event.*")
      .filter(col("id").isNotNull)
      .filter(col("type") === "edit")

    println("[StreamingApp] JSON parsing and filtering configured")

    // -------------------------------------------------------------------------
    // Step 4: Add event_time column
    //
    // WikiEditEvent.timestamp is Unix epoch SECONDS (Long).
    // TimestampType in Spark expects seconds, so the cast is correct.
    // We then drop rows where the cast produces null (corrupt timestamps).
    // -------------------------------------------------------------------------
    val withEventTime = parsedEvents
      .withColumn("event_time", col("timestamp").cast(TimestampType))
      .filter(col("event_time").isNotNull)

    // -------------------------------------------------------------------------
    // Step 5: Apply watermark
    //
    // A 10-minute watermark means Spark will wait up to 10 minutes beyond
    // the latest observed event time before closing a window.  Events arriving
    // more than 10 minutes late will be silently dropped.
    //
    // This is a reasonable trade-off for a real-time dashboard: we accept
    // slightly stale totals in exchange for bounded memory usage.
    // -------------------------------------------------------------------------
    val watermarkedEvents = withEventTime
      .withWatermark("event_time", "10 minutes")

    println("[StreamingApp] Watermark applied (threshold: 10 minutes)")

    // -------------------------------------------------------------------------
    // Step 6: Start the three analytics streaming jobs
    //
    // Each job reads from the same watermarked DataFrame but writes to its own
    // Delta table and checkpoint directory, so they run independently.
    // -------------------------------------------------------------------------
    TrendingArticlesJob.start(watermarkedEvents, spark)
    println("[StreamingApp] TrendingArticlesJob started")

    ActiveEditorsJob.start(watermarkedEvents, spark)
    println("[StreamingApp] ActiveEditorsJob started")

    EditSpikeDetector.start(watermarkedEvents, spark)
    println("[StreamingApp] EditSpikeDetector started")

    // -------------------------------------------------------------------------
    // Step 7: Block until any query terminates (or an exception propagates)
    //
    // awaitAnyTermination() keeps the driver alive while the micro-batch
    // scheduler runs on background threads.  If a query fails it will throw
    // and the driver will exit with a non-zero code, which monitoring tools
    // (Kubernetes, supervisord, etc.) can use to trigger a restart.
    // -------------------------------------------------------------------------
    println("[StreamingApp] All streaming jobs running — awaiting termination")
    spark.streams.awaitAnyTermination()
  }
}
