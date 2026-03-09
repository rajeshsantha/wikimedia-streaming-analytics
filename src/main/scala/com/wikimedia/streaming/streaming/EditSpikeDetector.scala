package com.wikimedia.streaming.streaming

import com.wikimedia.streaming.utils.KafkaConfig
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.{GroupState, GroupStateTimeout, StreamingQuery}

import java.sql.Timestamp

/**
 * EditSpikeDetector — identifies articles that suddenly receive an abnormally
 * high number of edits compared to their recent historical rate.
 *
 * ============================================================================
 * SPIKE DETECTION algorithm
 * ============================================================================
 *
 * The algorithm runs in two Spark streaming stages:
 *
 * Stage 1 — Tumbling window aggregation (stateless):
 *   Count edits per article per 1-minute tumbling window.
 *   A tumbling window is non-overlapping, so each edit belongs to exactly one
 *   window:
 *
 *   |← 1 min →|← 1 min →|← 1 min →|← 1 min →|
 *   [window 1 ][window 2 ][window 3 ][window 4 ]
 *   ─────────────────────────────────────────► event time
 *
 * Stage 2 — Stateful spike detection (mapGroupsWithState per article):
 *   For each article we maintain a rolling buffer of up to [[HistorySize]] (10)
 *   recent per-minute edit counts.  When a new window count arrives we compare
 *   it against the historical average:
 *
 *     is_spike = current_count > HistoryAverage × SpikeMultiplier (5×)
 *
 *   We only flag a spike when there IS a history (otherwise every article's
 *   first edit would appear to be a spike).
 *
 * === Why stateful processing is needed ===
 * Stage 2 cannot be expressed as a plain window aggregation because we need
 * to compare ACROSS windows (current vs history), not within a single window.
 * `mapGroupsWithState` lets us accumulate a rolling list of past counts and
 * make cross-window comparisons while Spark manages state persistence and
 * clean-up automatically.
 *
 * === State expiry ===
 * We set an EventTimeTimeout of 30 minutes.  If an article has no edits for
 * 30 minutes its state is discarded, freeing memory.  On expiry we emit a
 * sentinel row so consumers can mark the article as "quiet again".
 *
 * === SpikeMultiplier choice ===
 * 5× is deliberately aggressive for a demo — lower to 2–3× for production
 * dashboards where you want to surface more subtle traffic surges.
 */
object EditSpikeDetector {

  /** Number of historical window counts to retain per article. */
  val HistorySize: Int = 10

  /** Minimum ratio of current count to historical average to declare a spike. */
  val SpikeMultiplier: Double = 5.0

  // ---------------------------------------------------------------------------
  // Internal data types
  // ---------------------------------------------------------------------------

  /**
   * Persistent state for one article across micro-batches.
   *
   * @param title              Article title (group key).
   * @param historicalCounts   Rolling buffer of recent per-minute edit counts.
   * @param lastUpdateEpochMs  Epoch milliseconds of the last processed window.
   */
  case class ArticleSpikeState(
      title: String,
      historicalCounts: List[Long],
      lastUpdateEpochMs: Long
  )

  /**
   * Output row written to the Delta spike table.
   *
   * @param article_title      Article title.
   * @param window_start       Start of the 1-minute window that triggered detection.
   * @param window_end         End of the 1-minute window.
   * @param current_edit_count Edit count in the current window.
   * @param historical_average Average of the last [[HistorySize]] window counts.
   * @param spike_multiplier   current / historical_average (or 0 if no history).
   * @param is_spike           True when current > historical_average × [[SpikeMultiplier]].
   */
  case class SpikeEvent(
      article_title: String,
      window_start: Timestamp,
      window_end: Timestamp,
      current_edit_count: Long,
      historical_average: Double,
      spike_multiplier: Double,
      is_spike: Boolean
  )

  /**
   * Intermediate aggregation result from the Stage 1 tumbling window.
   *
   * @param title       Article title.
   * @param window_start Window start timestamp.
   * @param window_end   Window end timestamp.
   * @param edit_count  Total edits in the window.
   */
  case class WindowedCount(
      title: String,
      window_start: Timestamp,
      window_end: Timestamp,
      edit_count: Long
  )

  // ---------------------------------------------------------------------------
  // State update function
  // ---------------------------------------------------------------------------

  /**
   * Detects spikes for one article and updates the rolling history state.
   *
   * @param title   Article title (group key).
   * @param events  New [[WindowedCount]] records received in this micro-batch.
   * @param state   Handle to persistent [[ArticleSpikeState]].
   * @return        One [[SpikeEvent]] output row.
   */
  private def detectSpike(
      title:  String,
      events: Iterator[WindowedCount],
      state:  GroupState[ArticleSpikeState]
  ): SpikeEvent = {

    if (state.hasTimedOut) {
      // -----------------------------------------------------------------------
      // Timeout path — article has been quiet for 30 minutes.
      // Remove state and emit a sentinel "not a spike" row.
      // -----------------------------------------------------------------------
      state.remove()
      SpikeEvent(
        article_title      = title,
        window_start       = new Timestamp(0L),
        window_end         = new Timestamp(0L),
        current_edit_count = 0L,
        historical_average = 0.0,
        spike_multiplier   = 0.0,
        is_spike           = false
      )

    } else {
      // -----------------------------------------------------------------------
      // Normal path — process the latest windowed count.
      // -----------------------------------------------------------------------
      val eventList = events.toList

      // Take the most recent window event (by window_end)
      val latest = eventList.maxBy(_.window_end.getTime)

      val currentCount    = latest.edit_count
      val currentState    = state.getOption.getOrElse(
        ArticleSpikeState(title, List.empty, latest.window_start.getTime)
      )

      // Compute historical average from the rolling buffer
      val historicalAvg: Double =
        if (currentState.historicalCounts.isEmpty) 0.0
        else currentState.historicalCounts.sum.toDouble / currentState.historicalCounts.size

      // Spike condition: history must exist AND current is > 5× the average
      val isSpike =
        currentState.historicalCounts.nonEmpty &&
        currentCount > (historicalAvg * SpikeMultiplier)

      val ratio =
        if (historicalAvg == 0.0) 0.0
        else currentCount.toDouble / historicalAvg

      // Update rolling buffer — keep only the last HistorySize counts
      val updatedHistory = (currentState.historicalCounts :+ currentCount)
        .takeRight(HistorySize)

      val updatedState = ArticleSpikeState(
        title             = title,
        historicalCounts  = updatedHistory,
        lastUpdateEpochMs = latest.window_end.getTime
      )
      state.update(updatedState)

      // Set a 30-minute processing-time timeout from now
      state.setTimeoutDuration("30 minutes")

      SpikeEvent(
        article_title      = title,
        window_start       = latest.window_start,
        window_end         = latest.window_end,
        current_edit_count = currentCount,
        historical_average = historicalAvg,
        spike_multiplier   = ratio,
        is_spike           = isSpike
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Starts the edit-spike-detection streaming query.
   *
   * @param watermarkedEvents The watermarked edit-event stream from StreamingApp.
   * @param spark             Active SparkSession.
   * @return The running [[StreamingQuery]] handle.
   */
  def start(watermarkedEvents: DataFrame, spark: SparkSession): StreamingQuery = {
    import spark.implicits._

    // -------------------------------------------------------------------------
    // Stage 1: 1-minute tumbling window aggregation
    //
    // Count edits per article per minute — this is the "current window count"
    // that feeds into the spike detector.  Tumbling windows are non-overlapping
    // so each edit is counted in exactly one window.
    // -------------------------------------------------------------------------
    val windowedCounts = watermarkedEvents
      .groupBy(
        window(col("event_time"), "1 minute"),
        col("title")
      )
      .agg(count("*").alias("edit_count"))
      .select(
        col("title"),
        col("window.start").alias("window_start"),
        col("window.end").alias("window_end"),
        col("edit_count").cast("long")
      )
      .as[WindowedCount]

    // -------------------------------------------------------------------------
    // Stage 2: Stateful spike detection per article
    //
    // groupByKey(_.title) — one state object per article
    // mapGroupsWithState(EventTimeTimeout) — expire quiet articles automatically
    // -------------------------------------------------------------------------
    val spikeEvents = windowedCounts
      .groupByKey(_.title)
      .mapGroupsWithState(GroupStateTimeout.ProcessingTimeTimeout)(detectSpike)

    // -------------------------------------------------------------------------
    // Write to Delta Lake via foreachBatch
    //
    // outputMode("update"): emit a row for each article whose state was updated
    // in this micro-batch.  This includes both spikes and non-spike updates,
    // giving consumers a full audit trail.
    //
    // Delta Lake 4.1+ does not support "update" output mode as a direct sink,
    // so we use foreachBatch to write each micro-batch as an append to Delta.
    // -------------------------------------------------------------------------
    spikeEvents.toDF()
      .writeStream
      .outputMode("update")
      .foreachBatch { (batchDF: org.apache.spark.sql.DataFrame, batchId: Long) =>
        if (!batchDF.isEmpty) {
          batchDF.write
            .format("delta")
            .mode("append")
            .save(KafkaConfig.editSpikesTablePath)
        }
      }
      .option("checkpointLocation", KafkaConfig.editSpikesCheckpoint)
      .queryName("edit_spike_detector")
      .start()
  }
}
