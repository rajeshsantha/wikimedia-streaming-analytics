package com.wikimedia.streaming.utils

import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.SparkSession

/**
 * SparkSessionFactory — constructs a correctly configured [[SparkSession]].
 *
 * All Spark configuration is centralised here so that every entry point
 * (StreamingApp, tests, REPL) obtains a session with identical settings.
 * Each configuration key is explained in detail below.
 */
object SparkSessionFactory {

  /** Typesafe Config — reads spark section from application.conf */
  private val config = ConfigFactory.load()

  /**
   * Creates and returns a [[SparkSession]] wired for Delta Lake and
   * Structured Streaming.
   *
   * Configuration notes:
   *
   * === spark.sql.shuffle.partitions ===
   * Controls the number of partitions used after a shuffle (join, aggregation,
   * etc.).  Spark's default is 200, which is appropriate for large clusters but
   * creates excessive overhead on a single-machine dev setup — most partitions
   * end up empty, wasting scheduler cycles.  Setting this to 4 (or the number
   * of available CPU cores) gives much better local performance.
   *
   * === spark.sql.extensions ===
   * Registers Delta Lake's SQL dialect extensions with Spark.
   * `io.delta.sql.DeltaSparkSessionExtension` enables DDL statements such as
   * `CREATE TABLE … USING delta`, `OPTIMIZE`, `VACUUM`, `DESCRIBE HISTORY`,
   * and the `MERGE INTO` command that Delta adds on top of standard SQL.
   * Without this, Delta-specific SQL will fail with a parse error.
   *
   * === spark.sql.catalog.spark_catalog ===
   * Replaces Spark's default session catalog with Delta's
   * `DeltaCatalog` implementation.  This allows Spark to transparently manage
   * Delta tables (reading transaction logs, enforcing schema, etc.) when you
   * reference tables by name in SQL queries.  It also enables the
   * `USING delta` syntax in `CREATE TABLE`.
   *
   * === spark.sql.streaming.stateStore.providerClass ===
   * Selects the backend used to persist streaming state (e.g. the aggregation
   * state for windowed counts, or the custom state kept by
   * `mapGroupsWithState`).
   *
   * `HDFSBackedStateStoreProvider` (the default) stores state as Parquet files
   * on the local filesystem (or HDFS/S3 in production).  It is simple,
   * portable, and works out-of-the-box.
   *
   * For production workloads with very large state (millions of keys),
   * consider replacing this with
   * `org.apache.spark.sql.execution.streaming.state.RocksDBStateStoreProvider`
   * which keeps state in an embedded RocksDB instance for much lower read/write
   * latency and better memory efficiency.
   *
   * @return A fully configured [[SparkSession]] ready for streaming.
   */
  def create(): SparkSession = {
    val appName         = config.getString("spark.app-name")
    val master          = config.getString("spark.master")
    val shufflePartitions = config.getInt("spark.shuffle-partitions")
    val logLevel        = config.getString("spark.log-level")

    val spark = SparkSession.builder()
      .appName(appName)
      .master(master)
      // Reduce shuffles to a sensible number for local development
      .config("spark.sql.shuffle.partitions", shufflePartitions.toString)
      // Enable Delta SQL extensions (MERGE, OPTIMIZE, VACUUM, etc.)
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      // Replace default catalog with Delta-aware catalog
      .config("spark.sql.catalog.spark_catalog",
              "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      // Use HDFS-backed state store (default); swap for RocksDB in production
      .config("spark.sql.streaming.stateStore.providerClass",
              "org.apache.spark.sql.execution.streaming.state.HDFSBackedStateStoreProvider")
      .getOrCreate()

    // Suppress noisy log messages; set in config (default WARN)
    spark.sparkContext.setLogLevel(logLevel)

    spark
  }
}
