package com.wikimedia.streaming.utils

import com.typesafe.config.ConfigFactory

/**
 * KafkaConfig — centralised configuration accessor.
 *
 * All values are loaded once from `application.conf` (on the classpath) via
 * Typesafe Config's `ConfigFactory.load()`.  Storing them in an `object`
 * means they are evaluated lazily on first access and shared across the JVM.
 *
 * Import this object wherever bootstrap servers, topic names, or Delta /
 * checkpoint paths are needed rather than hard-coding strings.
 */
object KafkaConfig {

  /** Root config loaded from src/main/resources/application.conf */
  private val config = ConfigFactory.load()

  // ---------------------------------------------------------------------------
  // Kafka
  // ---------------------------------------------------------------------------

  /** Comma-separated list of Kafka broker addresses (host:port). */
  val bootstrapServers: String = config.getString("kafka.bootstrap-servers")

  /** Kafka topic that the SSE producer writes to and Spark consumes from. */
  val topic: String = config.getString("kafka.topic")

  /** Consumer group ID used by the Spark Structured Streaming Kafka source. */
  val groupId: String = config.getString("kafka.group-id")

  // ---------------------------------------------------------------------------
  // Wikimedia
  // ---------------------------------------------------------------------------

  /** Full URL of the Wikimedia EventStreams SSE endpoint. */
  val wikimediaStreamUrl: String = config.getString("wikimedia.stream-url")

  // ---------------------------------------------------------------------------
  // Delta Lake table paths
  // ---------------------------------------------------------------------------

  /** Local / HDFS path where the trending-articles Delta table is stored. */
  val trendingArticlesTablePath: String = config.getString("delta.tables.trending-articles")

  /** Local / HDFS path where the active-editors Delta table is stored. */
  val activeEditorsTablePath: String = config.getString("delta.tables.active-editors")

  /** Local / HDFS path where the edit-spikes Delta table is stored. */
  val editSpikesTablePath: String = config.getString("delta.tables.edit-spikes")

  // ---------------------------------------------------------------------------
  // Checkpoint paths (one per streaming query — must be distinct)
  // ---------------------------------------------------------------------------

  /** Checkpoint directory for the TrendingArticlesJob streaming query. */
  val trendingArticlesCheckpoint: String = config.getString("checkpoint.paths.trending-articles")

  /** Checkpoint directory for the ActiveEditorsJob streaming query. */
  val activeEditorsCheckpoint: String = config.getString("checkpoint.paths.active-editors")

  /** Checkpoint directory for the EditSpikeDetector streaming query. */
  val editSpikesCheckpoint: String = config.getString("checkpoint.paths.edit-spikes")
}
