package com.wikimedia.streaming.streaming

import com.wikimedia.streaming.utils.KafkaConfig
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.StreamingQuery

/**
 * TrendingArticlesJob — detects the most-edited articles over recent time
 * using a sliding window aggregation.
 *
 * ============================================================================
 * SLIDING WINDOWS — explained
 * ============================================================================
 *
 * A sliding window is defined by two parameters:
 *
 *   window duration   — how long each window covers in event time
 *   slide interval    — how often a new window starts
 *
 * Because slide < duration the windows OVERLAP: a single event contributes
 * to multiple windows simultaneously.  This is ideal for "trending" use-cases
 * because you get a continuously refreshed view rather than a snapshot that
 * resets every N minutes.
 *
 * Example with duration=10min, slide=30sec:
 *
 *   |← 10 min →|
 *   [  window 1  ]
 *         [  window 2  ]
 *               [  window 3  ]
 *                     [  window 4  ]
 *   ────────────────────────────────► event time
 *
 * At any moment there are 10min / 30sec = 20 overlapping windows open.
 * Each micro-batch updates all 20 windows for every incoming edit event.
 *
 * === Why sliding over tumbling for trending? ===
 * Tumbling windows (non-overlapping) would give "edits in the last 10 minutes"
 * but the result would jump sharply every 10 minutes.  Sliding windows produce
 * a smoother, continuously updated ranking that is much more useful in a
 * real-time dashboard.
 *
 * === Output mode: append ===
 * With a watermark, Spark can use "append" output mode for window aggregations:
 * a row is emitted (and written to Delta) only AFTER the window is finalised
 * (i.e. its end time has passed the watermark).  This avoids writing partial
 * results and then overwriting them, which would require a more expensive
 * "update" or "complete" mode.
 */
object TrendingArticlesJob {

  /**
   * Starts the trending-articles streaming query.
   *
   * @param watermarkedEvents The watermarked edit-event stream from StreamingApp.
   * @param spark             Active SparkSession.
   * @return The running [[StreamingQuery]] handle (returned for testing; callers
   *         don't need to hold it — the query runs in the background).
   */
  def start(watermarkedEvents: DataFrame, spark: SparkSession): StreamingQuery = {

    // -------------------------------------------------------------------------
    // Sliding window aggregation
    //
    // window("event_time", "10 minutes", "30 seconds"):
    //   - window duration = 10 minutes: each window covers a 10-minute span
    //   - slide interval  = 30 seconds: a new window starts every 30 seconds
    //
    // groupBy(window, title, server_name):
    //   Count edits per article per wiki per window, so the output correctly
    //   separates e.g. "Python" on en.wikipedia from "Python" on de.wikipedia.
    // -------------------------------------------------------------------------
    val trendingArticles = watermarkedEvents
      .groupBy(
        window(col("event_time"), "10 minutes", "30 seconds"),
        col("title"),
        col("server_name")
      )
      .agg(count("*").alias("edit_count"))
      .select(
        col("window.start").alias("window_start"),
        col("window.end").alias("window_end"),
        col("title").alias("article_title"),
        col("server_name"),
        col("edit_count")
      )

    // -------------------------------------------------------------------------
    // Write to Delta Lake
    //
    // outputMode("append"): emit a row only after the window is finalised
    //   (requires a watermark — guaranteed by the caller).
    // checkpointLocation: Spark persists committed micro-batch IDs and Kafka
    //   offsets here so the query can resume exactly where it left off after
    //   a restart (exactly-once delivery).
    // queryName: shown in the Spark UI and logs for easy identification.
    // -------------------------------------------------------------------------
    watermarkedEvents
      .groupBy(
        window(col("event_time"), "10 minutes", "30 seconds"),
        col("title"),
        col("server_name")
      )
      .agg(count("*").alias("edit_count"))
      .select(
        col("window.start").alias("window_start"),
        col("window.end").alias("window_end"),
        col("title").alias("article_title"),
        col("server_name"),
        col("edit_count")
      )
      .writeStream
      .format("delta")
      .outputMode("append")
      .option("checkpointLocation", KafkaConfig.trendingArticlesCheckpoint)
      .queryName("trending_articles")
      .start(KafkaConfig.trendingArticlesTablePath)
  }
}
