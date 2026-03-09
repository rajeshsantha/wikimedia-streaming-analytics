package com.wikimedia.streaming.streaming

import com.wikimedia.streaming.utils.KafkaConfig
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.{GroupState, GroupStateTimeout, StreamingQuery}
import org.apache.spark.sql.types.TimestampType

import java.sql.Timestamp

/**
 * ActiveEditorsJob — tracks the running edit count for every active editor
 * using Spark Structured Streaming's stateful `mapGroupsWithState` operator.
 *
 * ============================================================================
 * STATEFUL PROCESSING with mapGroupsWithState
 * ============================================================================
 *
 * === Why mapGroupsWithState? ===
 * A simple `groupBy(user).count()` in streaming mode would give the TOTAL
 * edits since the stream started, with no notion of "active recently".  We
 * need to:
 *  1. Maintain a per-user running total that can be queried at any time.
 *  2. Mark editors as "expired" (no edits in the last hour) so the sink doesn't
 *     grow without bound.
 *  3. Return an output row for both active and newly-expired editors.
 *
 * `mapGroupsWithState` gives full control over the lifecycle of each group's
 * state object.  For each micro-batch Spark calls the update function with:
 *  - The group key (user name).
 *  - An iterator of new events for that user in this batch.
 *  - A `GroupState[EditorState]` handle to read/write/remove persistent state.
 *
 * === State expiry (EventTimeTimeout) ===
 * `GroupStateTimeout.EventTimeTimeout` expires a group's state when the
 * streaming watermark advances past the timeout timestamp set for that group.
 * We set the timeout to `latestEventTime + 1 hour`, so an editor whose last
 * edit was more than 1 hour ago (in event time) will be cleaned up.
 *
 * On expiry, `state.hasTimedOut` is true; we retrieve the last known state,
 * remove it, and emit a final output row with `is_expired = true`.
 *
 * === EventTimeTimeout vs ProcessingTimeTimeout ===
 * - `EventTimeTimeout` is driven by the watermark (event time), so replaying
 *   historical data at high speed correctly ages out editors based on the
 *   events' own timestamps.  Recommended for event-time pipelines.
 * - `ProcessingTimeTimeout` is driven by wall-clock time.  Easier to reason
 *   about in simple cases, but produces incorrect results when replaying data.
 *
 * === Spark 4.0 note ===
 * Spark 4.0 introduced `transformWithState` as a more flexible and
 * performant successor to `mapGroupsWithState`.  For production systems
 * targeting Spark 4.0+, `transformWithState` is preferred.  We use
 * `mapGroupsWithState` here for broad compatibility and conceptual clarity.
 */
object ActiveEditorsJob {

  // ---------------------------------------------------------------------------
  // Internal data types
  // ---------------------------------------------------------------------------

  /**
   * Persistent state stored for each editor between micro-batches.
   *
   * @param user             MediaWiki username or IP address.
   * @param totalEdits       Running total edits observed since tracking started.
   * @param lastActivityEpochMs Epoch milliseconds of the most recent edit seen.
   */
  case class EditorState(
      user: String,
      totalEdits: Long,
      lastActivityEpochMs: Long
  )

  /**
   * Output row emitted to the Delta sink for each editor.
   *
   * @param user          MediaWiki username.
   * @param total_edits   Cumulative edit count.
   * @param last_activity Timestamp of the most recent edit.
   * @param is_expired    True when the editor's state has just been cleaned up
   *                      due to inactivity; the Delta row marks them as inactive.
   */
  case class EditorOutput(
      user: String,
      total_edits: Long,
      last_activity: Timestamp,
      is_expired: Boolean
  )

  /**
   * Lightweight event struct used to convert the generic DataFrame into a
   * typed Dataset before calling groupByKey.
   */
  case class EditorEvent(user: String, event_time: Timestamp)

  // ---------------------------------------------------------------------------
  // State update function
  // ---------------------------------------------------------------------------

  /**
   * Updates the per-editor state and produces an output row.
   *
   * Called once per editor per micro-batch (or on timeout).
   *
   * @param user   The editor's username (group key).
   * @param events New edit events received in this micro-batch for this user.
   * @param state  Handle to persistent GroupState[EditorState].
   * @return       One EditorOutput row.
   */
  private def updateEditorState(
      user:   String,
      events: Iterator[EditorEvent],
      state:  GroupState[EditorState]
  ): EditorOutput = {

    if (state.hasTimedOut) {
      // -----------------------------------------------------------------------
      // Timeout path — editor has been inactive for > 1 hour (event time).
      // Retrieve the last known state, clean it up, and emit an expired row.
      // -----------------------------------------------------------------------
      val lastState = state.get
      state.remove()
      EditorOutput(
        user          = user,
        total_edits   = lastState.totalEdits,
        last_activity = new Timestamp(lastState.lastActivityEpochMs),
        is_expired    = true
      )

    } else {
      // -----------------------------------------------------------------------
      // Normal path — process new events.
      // -----------------------------------------------------------------------
      val eventList = events.toList

      // Accumulate with any pre-existing state
      val currentState = state.getOption.getOrElse(EditorState(user, 0L, 0L))
      val newEditCount = currentState.totalEdits + eventList.size

      // Find the most recent event_time in this batch
      val latestEventMs = eventList
        .map(_.event_time.getTime)
        .foldLeft(currentState.lastActivityEpochMs)(math.max)

      // Persist the updated state
      val updatedState = EditorState(
        user                = user,
        totalEdits          = newEditCount,
        lastActivityEpochMs = latestEventMs
      )
      state.update(updatedState)

      // Set an event-time timeout: if no new events arrive within 1 hour of
      // the latest event time, the state will be cleaned up automatically.
      state.setTimeoutTimestamp(latestEventMs + 60L * 60L * 1000L)

      EditorOutput(
        user          = user,
        total_edits   = newEditCount,
        last_activity = new Timestamp(latestEventMs),
        is_expired    = false
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Starts the active-editors streaming query.
   *
   * @param watermarkedEvents The watermarked edit-event stream from StreamingApp.
   * @param spark             Active SparkSession.
   * @return The running [[StreamingQuery]] handle.
   */
  def start(watermarkedEvents: DataFrame, spark: SparkSession): StreamingQuery = {
    import spark.implicits._

    // Convert the generic DataFrame to a typed Dataset so we can use
    // groupByKey / mapGroupsWithState with case classes.
    val editorEvents: org.apache.spark.sql.Dataset[EditorEvent] = watermarkedEvents
      .select(
        col("user"),
        col("event_time")
      )
      .as[EditorEvent]

    // -------------------------------------------------------------------------
    // Stateful transformation
    //
    // groupByKey(_.user)               — one state object per editor
    // mapGroupsWithState(EventTimeTimeout) — use event-time watermark for expiry
    // -------------------------------------------------------------------------
    val activeEditors = editorEvents
      .groupByKey(_.user)
      .mapGroupsWithState(GroupStateTimeout.EventTimeTimeout)(updateEditorState)

    // -------------------------------------------------------------------------
    // Write to Delta Lake via foreachBatch
    //
    // outputMode("update"): emit a row for each group whose state changed in
    // this micro-batch (both active and newly-expired editors).
    // "complete" would re-emit ALL groups every batch — far too expensive.
    // "append" is not allowed with mapGroupsWithState.
    //
    // Delta Lake 4.1+ does not support "update" output mode as a direct sink,
    // so we use foreachBatch to write each micro-batch as an append to Delta.
    // -------------------------------------------------------------------------
    activeEditors.toDF()
      .writeStream
      .outputMode("update")
      .foreachBatch { (batchDF: org.apache.spark.sql.DataFrame, batchId: Long) =>
        if (!batchDF.isEmpty) {
          batchDF.write
            .format("delta")
            .mode("append")
            .save(KafkaConfig.activeEditorsTablePath)
        }
      }
      .option("checkpointLocation", KafkaConfig.activeEditorsCheckpoint)
      .queryName("active_editors")
      .start()
  }
}
