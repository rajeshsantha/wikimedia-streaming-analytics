package com.wikimedia.streaming.models

import org.apache.spark.sql.types._

/**
 * WikiEditEvent — canonical model for a Wikimedia recent-change SSE event.
 *
 * Each field mirrors a property present in the JSON payload published by
 * the Wikimedia EventStreams API at
 * https://stream.wikimedia.org/v2/stream/recentchange
 *
 * The companion object's [[WikiEditEvent.schema]] StructType must stay in
 * sync with these fields; it is used by [[com.wikimedia.streaming.streaming.StreamingApp]]
 * to parse raw JSON strings from Kafka into typed rows.
 */
case class WikiEditEvent(
    /** Internal revision / event ID assigned by MediaWiki. */
    id: Long,

    /** Article title (URL-decoded, spaces replaced by underscores). */
    title: String,

    /** MediaWiki username or IP address of the editor. */
    user: String,

    /**
     * Unix epoch seconds at which the edit was made.
     * Stored as Long here; cast to TimestampType in the streaming pipeline
     * so that Spark's event-time engine can perform window operations.
     */
    timestamp: Long,

    /**
     * Type of recent-change event.
     * Common values: "edit" (page edited), "new" (page created),
     * "log" (log action), "categorize" (category membership changed).
     * The streaming jobs filter on type === "edit".
     */
    `type`: String,

    /** Hostname of the wiki that produced the event (e.g. "en.wikipedia.org"). */
    server_name: String,

    /** Edit summary written by the editor (may be empty). */
    comment: String,

    /** True if the edit was made by a registered bot account. */
    bot: Boolean,

    /** True if the editor marked the change as "minor". */
    minor: Boolean,

    /**
     * MediaWiki namespace integer.
     * 0 = main article space, 1 = Talk, 2 = User, 4 = Wikipedia, etc.
     */
    namespace: Int,

    /** Short wiki identifier (e.g. "enwiki", "dewiki", "commonswiki"). */
    wiki: String
)

object WikiEditEvent {

  /**
   * Spark SQL schema matching the [[WikiEditEvent]] case class.
   *
   * Keeping schema and case class in the same file ensures they never drift
   * apart.  The schema is passed to [[org.apache.spark.sql.functions.from_json]]
   * in [[com.wikimedia.streaming.streaming.StreamingApp]] to parse the raw
   * JSON bytes received from Kafka.
   *
   * All fields are nullable (default) because SSE payloads occasionally omit
   * optional properties; nulls are filtered out downstream.
   */
  val schema: StructType = StructType(Seq(
    StructField("id",          LongType,    nullable = true),
    StructField("title",       StringType,  nullable = true),
    StructField("user",        StringType,  nullable = true),
    StructField("timestamp",   LongType,    nullable = true),
    StructField("type",        StringType,  nullable = true),
    StructField("server_name", StringType,  nullable = true),
    StructField("comment",     StringType,  nullable = true),
    StructField("bot",         BooleanType, nullable = true),
    StructField("minor",       BooleanType, nullable = true),
    StructField("namespace",   IntegerType, nullable = true),
    StructField("wiki",        StringType,  nullable = true)
  ))
}
