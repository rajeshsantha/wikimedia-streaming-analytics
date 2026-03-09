# Wikimedia Streaming Analytics

A **production-grade real-time data engineering project** that consumes live Wikipedia edit events via Server-Sent Events (SSE), publishes them to Apache Kafka, processes them with Spark Structured Streaming, and writes analytics results to Delta Lake tables.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Tech Stack](#tech-stack)
4. [Prerequisites](#prerequisites)
5. [Project Structure](#project-structure)
6. [Detailed Technical Walkthrough](#detailed-technical-walkthrough)
   - [Data Source: Wikimedia EventStreams](#1-data-source--wikimedia-eventstreams)
   - [Data Model: WikiEditEvent](#2-data-model--wikieditevent)
   - [Configuration: KafkaConfig and application.conf](#3-configuration--kafkaconfig-and-applicationconf)
   - [Ingestion Layer: WikimediaKafkaProducer](#4-ingestion-layer--wikimediakafkaproducer)
   - [SparkSession Factory](#5-sparksession-factory)
   - [Streaming Orchestrator: StreamingApp](#6-streaming-orchestrator--streamingapp)
   - [Analytics Job 1: TrendingArticlesJob](#7-analytics-job-1--trendingarticlesjob)
   - [Analytics Job 2: ActiveEditorsJob](#8-analytics-job-2--activeeditorsjob)
   - [Analytics Job 3: EditSpikeDetector](#9-analytics-job-3--editspikedetector)
   - [Infrastructure: Docker Compose and Kafka KRaft](#10-infrastructure--docker-compose-and-kafka-kraft)
7. [Core Streaming Concepts Explained](#core-streaming-concepts-explained)
   - [Event-Time vs Processing-Time](#event-time-vs-processing-time)
   - [Watermarking](#watermarking)
   - [Window Aggregations (Tumbling vs Sliding)](#window-aggregations-tumbling-vs-sliding)
   - [Stateful Processing with mapGroupsWithState](#stateful-processing-with-mapgroupswithstate)
   - [Checkpointing](#checkpointing)
   - [Exactly-Once Guarantees](#exactly-once-guarantees)
8. [Quick Reference: Script Utilities](#quick-reference--script-utilities)
9. [Getting Started: Step-by-Step](#getting-started--step-by-step)
10. [Querying Results](#querying-results)
11. [Spark Configuration Reference](#spark-configuration-reference)
12. [Stopping the Pipeline](#stopping-the-pipeline)
13. [Troubleshooting](#troubleshooting)

---

## Overview

Every second, thousands of edits are made across all Wikimedia wikis (Wikipedia, Wiktionary, Wikidata, etc.). This project taps into the public [Wikimedia EventStreams](https://wikitech.wikimedia.org/wiki/Event_Platform/EventStreams) SSE feed and builds **three real-time analytics pipelines** on top of it:

| Job | What It Does | Window Type | State Model |
|-----|-------------|-------------|-------------|
| **TrendingArticlesJob** | Sliding-window (10 min / 30 sec) edit counts per article -- continuously refreshed "trending" rankings | Sliding window | Stateless (window aggregation) |
| **ActiveEditorsJob** | Stateful running edit total per editor with 1-hour inactivity expiry | N/A | `mapGroupsWithState` with `EventTimeTimeout` |
| **EditSpikeDetector** | Spike detection: alerts when an article edit rate exceeds 5x its recent 10-minute average | Tumbling (1 min) + stateful | `mapGroupsWithState` with `ProcessingTimeTimeout` |

---

## Architecture

```
+---------------------------------------------------------------------------+
|                        Wikimedia EventStreams                               |
|           https://stream.wikimedia.org/v2/stream/recentchange             |
|                          (SSE / HTTP)                                      |
+-------------------------------------+-------------------------------------+
                                      |  JSON events (one per SSE data line)
                                      v
+---------------------------------------------------------------------------+
|              WikimediaKafkaProducer  (OkHttp SSE + Kafka Client)           |
|    acks=all | idempotence=true | snappy compression | linger=20ms          |
+-------------------------------------+-------------------------------------+
                                      |  ProducerRecord -> topic: wikimedia-events
                                      v
+---------------------------------------------------------------------------+
|               Apache Kafka 3.9.0  (KRaft, no Zookeeper)                    |
|               confluentinc/cp-kafka:7.9.0 -- localhost:9092                |
+-------+-------------------------------------------------------------------+
        |  readStream.format("kafka")
        v
+---------------------------------------------------------------------------+
|           Spark Structured Streaming  (Spark 4.1.1 / Scala 2.13)           |
|                                                                            |
|   JSON parse -> filter type="edit" -> event_time cast -> watermark         |
|                                                                            |
|   +------------------+  +------------------+  +------------------------+  |
|   | TrendingArticles |  |  ActiveEditors   |  | EditSpikeDetector      |  |
|   | sliding window   |  | mapGroupsWithSt. |  | tumbling + stateful    |  |
|   | 10min / 30sec    |  | 1-hour expiry    |  | 5x spike alert         |  |
|   +--------+---------+  +--------+---------+  +----------+-------------+  |
+------------+---------------------+-----------------------+----------------+
             |                     |                       |
             v                     v                       v
+---------------------------------------------------------------------------+
|                         Delta Lake 4.1.0                                   |
|   delta/trending_articles/  delta/active_editors/  delta/edit_spikes/      |
+---------------------------------------------------------------------------+
```

**Data flows top-to-bottom:**

1. **Wikimedia EventStreams** pushes real-time edit events over an HTTP SSE (Server-Sent Events) connection. SSE is a one-way push protocol over plain HTTP where the server keeps the connection open and pushes newline-delimited `data:` lines to the client.
2. **WikimediaKafkaProducer** receives each JSON event via OkHttp's SSE extension and publishes it as-is (raw JSON string) to the `wikimedia-events` Kafka topic. The producer guarantees exactly-once delivery via idempotent writes.
3. **Apache Kafka** acts as a durable, fault-tolerant, replayable message buffer between the producer and consumer. Data is retained for 24 hours (configurable).
4. **Spark Structured Streaming** reads from Kafka, parses JSON, applies watermarking for late-event handling, and fans out to 3 independent analytics jobs running as separate streaming queries.
5. **Delta Lake** stores the final analytics output as ACID-transactional Parquet-based tables on the local filesystem, enabling time-travel queries, schema enforcement, and exactly-once writes.

---

## Tech Stack

| Component | Version | Role in This Project |
|-----------|---------|---------------------|
| **Apache Spark** | 4.1.1 | Structured Streaming engine -- runs micro-batch window aggregations and stateful transformations |
| **Scala** | 2.13.14 | Application language; Spark 4.x dropped Scala 2.12 support |
| **Apache Kafka** | 3.9.0 | Durable message broker between SSE producer and Spark consumer; runs in KRaft mode (no Zookeeper) |
| **Delta Lake** | 4.1.0 | ACID storage layer on top of Parquet; provides exactly-once writes, time-travel, and schema enforcement |
| **OkHttp + OkHttp-SSE** | 4.12.0 | HTTP client that handles SSE framing, auto-reconnect, and connection pooling for the Wikimedia feed |
| **Kafka Client** | 3.9.0 | Java producer API for publishing events from the SSE listener to Kafka |
| **Typesafe Config** | 1.4.3 | HOCON-based configuration library; all tunable parameters live in `application.conf` |
| **Gson** | 2.11.0 | JSON serialisation/deserialisation (available if needed by downstream processors) |
| **Maven** | 3.9+ | Build tool; produces a fat/uber JAR via `maven-shade-plugin` |
| **Docker** | Desktop/Engine | Runs Kafka locally as a single container |
| **Java** | 17+ | Runtime required by Spark 4.x (Java 21 recommended) |

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java (JDK) | 17+ (21 recommended) | `brew install openjdk@21` or [Adoptium](https://adoptium.net) |
| Maven | 3.9+ | `brew install maven` |
| Docker Desktop | latest | [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/) |
| Apache Spark | 4.1.x | `brew install apache-spark` or [spark.apache.org/downloads](https://spark.apache.org/downloads.html) |

Verify installations:
```bash
java -version            # openjdk 17 or later (21 recommended)
mvn -version             # Apache Maven 3.9.x
docker version           # Docker Engine / Desktop
spark-submit --version   # version 4.1.x
```

---

## Project Structure

```
wikimedia-streaming-analytics/
+-- scripts/
|   +-- start-all.sh                    # One-command full pipeline startup
|   +-- start-kafka.sh                  # Start Kafka + create topic
|   +-- start-producer.sh               # Start SSE → Kafka producer
|   +-- start-streaming.sh              # Start Spark streaming app
|   +-- stop-all.sh                     # Stop all components gracefully
|   +-- status.sh                       # Show pipeline health & data stats
|   +-- cleanup.sh                      # Remove state / rebuild
|   +-- build.sh                        # Clean Maven build
+-- docker/
|   +-- docker-compose.yml              # Kafka 3.9 in KRaft mode (single-node)
+-- src/main/
|   +-- resources/
|   |   +-- application.conf            # All config: Kafka, Spark, Delta, checkpoint paths
|   +-- scala/com/wikimedia/streaming/
|       +-- models/
|       |   +-- WikiEditEvent.scala      # Case class + Spark StructType schema
|       +-- producer/
|       |   +-- WikimediaKafkaProducer.scala  # SSE -> Kafka ingestion
|       +-- streaming/
|       |   +-- StreamingApp.scala           # Main entry: parse, filter, watermark, fan-out
|       |   +-- TrendingArticlesJob.scala     # Sliding window aggregation
|       |   +-- ActiveEditorsJob.scala        # Stateful per-editor tracking
|       |   +-- EditSpikeDetector.scala       # Spike detection (tumbling + stateful)
|       +-- utils/
|           +-- KafkaConfig.scala            # Centralised config accessor
|           +-- SparkSessionFactory.scala    # SparkSession builder with Delta config
+-- delta/                               # Delta Lake table data (created at runtime)
+-- checkpoint/                          # Spark checkpoint dirs (created at runtime)
+-- pom.xml                              # Maven build with shade plugin (fat JAR)
+-- README.md
```

---

## Detailed Technical Walkthrough

### 1. Data Source -- Wikimedia EventStreams

**What it is:** Wikimedia provides a free, public [Server-Sent Events (SSE)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) endpoint at:
```
https://stream.wikimedia.org/v2/stream/recentchange
```

Every edit, page creation, log action, or category change across **all** Wikimedia wikis is published as a JSON object in real time.

**SSE Protocol:** Unlike WebSockets (bidirectional), SSE is a **one-way push** protocol over plain HTTP. The server keeps the connection open and pushes lines prefixed with `data:` to the client. If the connection drops, the client can reconnect and the server replays missed events using the `Last-Event-ID` header.

**Event volume:** Approximately 50-200 events per second across all wikis.

**JSON payload example (simplified):**
```json
{
  "id": 1742500123,
  "type": "edit",
  "title": "SpaceX_Starship",
  "user": "ExampleUser",
  "bot": false,
  "minor": false,
  "timestamp": 1709827200,
  "server_name": "en.wikipedia.org",
  "wiki": "enwiki",
  "namespace": 0,
  "comment": "Updated launch date"
}
```

---

### 2. Data Model -- WikiEditEvent

**File:** `src/main/scala/com/wikimedia/streaming/models/WikiEditEvent.scala`

This file serves two critical purposes:

#### a) Scala Case Class

Defines the canonical model with strongly-typed fields:

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | MediaWiki internal revision/event ID |
| `title` | String | Article title (e.g., "SpaceX_Starship") |
| `user` | String | Editor username or IP address |
| `timestamp` | Long | Unix epoch **seconds** when the edit was made |
| `type` | String | Event type: `"edit"`, `"new"`, `"log"`, `"categorize"` |
| `server_name` | String | Wiki hostname (e.g., "en.wikipedia.org") |
| `comment` | String | Edit summary written by the editor |
| `bot` | Boolean | Whether the editor is a registered bot account |
| `minor` | Boolean | Whether the edit was marked as "minor" |
| `namespace` | Int | MediaWiki namespace (0 = main article, 1 = Talk, 2 = User, 4 = Wikipedia) |
| `wiki` | String | Short wiki ID (e.g., "enwiki", "dewiki") |

#### b) Spark SQL Schema (StructType)

The companion object defines a `StructType` schema that mirrors the case class field-by-field. This schema is used by `from_json()` in `StreamingApp` to parse raw JSON strings from Kafka into typed Spark DataFrame rows.

**Why keep both in the same file?** If someone adds a field to the case class but forgets to update the schema (or vice versa), the pipeline will silently drop data or crash. Co-locating them makes drift impossible.

**All fields are nullable** because SSE payloads occasionally omit optional properties. Nulls are filtered out downstream via `filter(col("id").isNotNull)`.

---

### 3. Configuration -- KafkaConfig and application.conf

**Files:**
- `src/main/resources/application.conf` -- HOCON configuration file
- `src/main/scala/com/wikimedia/streaming/utils/KafkaConfig.scala` -- Scala accessor object

#### application.conf (HOCON format)

The configuration is organised into four sections:

| Section | Key | Default Value | Purpose |
|---------|-----|---------------|---------|
| `kafka` | `bootstrap-servers` | `localhost:9092` | Kafka broker address |
| `kafka` | `topic` | `wikimedia-events` | Topic name for raw events |
| `kafka` | `group-id` | `wikimedia-streaming-consumer` | Consumer group for Spark |
| `wikimedia` | `stream-url` | `https://stream.wikimedia.org/v2/stream/recentchange` | SSE endpoint URL |
| `spark` | `app-name` | `WikimediaStreamingAnalytics` | Spark application name |
| `spark` | `master` | `local[*]` | Spark master (`local[*]` = all CPU cores) |
| `spark` | `shuffle-partitions` | `4` | Overrides Spark default of 200 |
| `spark` | `log-level` | `WARN` | Suppresses verbose Spark logs |
| `delta.tables` | `trending-articles` | `delta/trending_articles` | Delta table output path |
| `delta.tables` | `active-editors` | `delta/active_editors` | Delta table output path |
| `delta.tables` | `edit-spikes` | `delta/edit_spikes` | Delta table output path |
| `checkpoint.paths` | `trending-articles` | `checkpoint/trending_articles` | Streaming checkpoint directory |
| `checkpoint.paths` | `active-editors` | `checkpoint/active_editors` | Streaming checkpoint directory |
| `checkpoint.paths` | `edit-spikes` | `checkpoint/edit_spikes` | Streaming checkpoint directory |

**Override at runtime:** Any value can be overridden via JVM system properties, e.g.: `-Dkafka.bootstrap-servers=remote:9092`.

#### KafkaConfig.scala

A singleton Scala `object` that calls `ConfigFactory.load()` once and exposes each configuration key as a typed `val`. This avoids scattering magic strings throughout the codebase. Every class that needs a config value simply references `KafkaConfig.bootstrapServers`, `KafkaConfig.topic`, etc.

---

### 4. Ingestion Layer -- WikimediaKafkaProducer

**File:** `src/main/scala/com/wikimedia/streaming/producer/WikimediaKafkaProducer.scala`

This is a **standalone JVM application** (has its own `main` method) that bridges the Wikimedia SSE feed to Kafka. It runs independently of Spark.

#### How it works step by step:

**Step 1 -- Configure the Kafka Producer:**

| Property | Value | Why |
|----------|-------|-----|
| `bootstrap.servers` | `localhost:9092` | Kafka broker address from config |
| `key.serializer` | `StringSerializer` | Keys are Strings (null in this project, so Kafka round-robins partitions) |
| `value.serializer` | `StringSerializer` | Values are raw JSON strings |
| `acks` | `all` | Wait for ALL in-sync replicas to acknowledge before marking the message as "sent" |
| `enable.idempotence` | `true` | Broker assigns a Producer ID (PID) and sequence number to each message; if a retry causes a duplicate send, the broker deduplicates using PID+sequence |
| `linger.ms` | `20` | Instead of sending each message immediately, wait up to 20ms to accumulate more messages into a single batch -- dramatically improves throughput (fewer network round-trips) with only ~20ms added latency |
| `compression.type` | `snappy` | Snappy provides a good balance of compression ratio and CPU cost; JSON events compress well (60-70% reduction) |
| `retries` | `5` | Retry transient network failures up to 5 times |

**Why `acks=all` + `enable.idempotence=true` together?**
- `acks=all`: The producer waits until all in-sync replicas have written the message. This provides the strongest durability guarantee.
- `enable.idempotence=true`: Each message gets a PID+sequence number. If a network glitch causes a retry, the broker uses PID+sequence to deduplicate.
- Together, these give **exactly-once producer semantics**.

**Step 2 -- Create an OkHttp SSE Client:**

`EventSources.createFactory(client).newEventSource(request, listener)` opens an HTTP connection to the Wikimedia endpoint and handles SSE framing automatically. The listener has four callbacks:

| Callback | When It Fires | What Happens |
|----------|--------------|--------------|
| `onOpen` | SSE connection established | Logs "Connected to ..." |
| `onEvent` | Each SSE event received | Publishes the raw JSON data to Kafka as a `ProducerRecord` |
| `onClosed` | Server closes connection | Logs message; OkHttp auto-reconnects using the retry interval from the SSE stream |
| `onFailure` | Network error or non-2xx HTTP | Logs error with HTTP status code; OkHttp backs off and retries |

The `onEvent` callback publishes events using `producer.send()` which is **non-blocking** -- the actual network send happens on the Kafka producer's I/O thread. A callback logs any send errors asynchronously.

**Step 3 -- Thread Model:**
- The SSE listener runs on **OkHttp's internal dispatcher thread** (not the main thread).
- The main thread calls `Thread.currentThread().join()` to block forever.
- A **JVM shutdown hook** (triggered by Ctrl+C / SIGTERM) flushes the Kafka producer buffer (`producer.flush()`), closes the producer, and shuts down the OkHttp dispatcher cleanly.
- An `AtomicLong` counter tracks events and logs progress every 100 events (thread-safe because `onEvent` runs on the OkHttp dispatcher thread).

---

### 5. SparkSession Factory

**File:** `src/main/scala/com/wikimedia/streaming/utils/SparkSessionFactory.scala`

Creates a `SparkSession` with the following critical configuration:

| Config Key | Value | Why |
|-----------|-------|-----|
| `spark.sql.shuffle.partitions` | `4` | Spark's default of 200 creates 200 tasks per shuffle stage. On a single machine, most partitions end up empty, wasting scheduler overhead. 4 matches typical CPU core counts for local development. |
| `spark.sql.extensions` | `io.delta.sql.DeltaSparkSessionExtension` | Registers Delta Lake's SQL dialect extensions with Spark -- enables DDL statements like `CREATE TABLE ... USING delta`, `OPTIMIZE`, `VACUUM`, `DESCRIBE HISTORY`, and `MERGE INTO`. Without this, Delta-specific SQL fails with a parse error. |
| `spark.sql.catalog.spark_catalog` | `org.apache.spark.sql.delta.catalog.DeltaCatalog` | Replaces Spark's default session catalog with Delta's implementation. This allows Spark to transparently manage Delta tables (read transaction logs, enforce schema, etc.) when referencing tables by name. |
| `spark.sql.streaming.stateStore.providerClass` | `HDFSBackedStateStoreProvider` | Default state store backend -- stores streaming state as Parquet files on the local filesystem (or HDFS/S3 in production). Simple, portable, works out-of-the-box. For production with millions of keys, swap to `RocksDBStateStoreProvider` for lower latency. |
| Log level | `WARN` | Suppresses verbose INFO logs from Spark internals during development. |

---

### 6. Streaming Orchestrator -- StreamingApp

**File:** `src/main/scala/com/wikimedia/streaming/streaming/StreamingApp.scala`

This is the **main entry point** for the Spark application. It orchestrates the entire streaming pipeline in 7 steps:

#### Step 1: Create SparkSession
Calls `SparkSessionFactory.create()` to get a SparkSession with Delta Lake extensions enabled.

#### Step 2: Read from Kafka

```scala
spark.readStream
  .format("kafka")
  .option("kafka.bootstrap.servers", "localhost:9092")
  .option("subscribe", "wikimedia-events")
  .option("startingOffsets", "latest")
  .option("failOnDataLoss", "false")
  .load()
```

| Option | Value | Meaning |
|--------|-------|---------|
| `format("kafka")` | -- | Use the Spark-Kafka integration connector (`spark-sql-kafka-0-10`) |
| `subscribe` | `wikimedia-events` | Subscribe to this Kafka topic |
| `startingOffsets` | `latest` | On first start, begin from the newest messages (skip backlog). Change to `"earliest"` to replay from the beginning. |
| `failOnDataLoss` | `false` | If Kafka offsets have been deleted (e.g., due to log retention), continue rather than throwing an exception |

**What Kafka gives Spark:** Each row in the resulting DataFrame has columns `key`, `value`, `topic`, `partition`, `offset`, `timestamp`, `timestampType`. The actual JSON event is in `value` as binary bytes.

#### Step 3: Parse JSON

```
kafkaStream
  -> cast value from binary to String
  -> from_json(value, WikiEditEvent.schema) aliased as "event"
  -> select event.* to flatten the struct into top-level columns
  -> filter id IS NOT NULL (drop malformed rows where JSON parsing failed)
  -> filter type = "edit" (keep only edit events; discard log, categorize, new-page events)
```

#### Step 4: Add Event-Time Column

The `timestamp` field from MediaWiki is Unix epoch **seconds** (Long). Spark's `TimestampType` interprets Long values as seconds, so `col("timestamp").cast(TimestampType)` works directly. Rows with corrupt/null timestamps are filtered out.

#### Step 5: Apply Watermark

```scala
.withWatermark("event_time", "10 minutes")
```

This tells Spark: *"The watermark is `max(event_time seen so far) - 10 minutes`. Drop any event older than the watermark."* See the [Watermarking](#watermarking) section below for a deep explanation.

#### Step 6: Start Three Analytics Jobs

Each job reads from the same `watermarkedEvents` DataFrame but writes to its own Delta table and checkpoint directory, so they run as independent streaming queries in parallel.

#### Step 7: Await Termination

`spark.streams.awaitAnyTermination()` blocks the main thread. If any query fails, the exception propagates and the Spark driver exits with a non-zero code.

---

### 7. Analytics Job 1 -- TrendingArticlesJob

**File:** `src/main/scala/com/wikimedia/streaming/streaming/TrendingArticlesJob.scala`

#### What it computes
"Which articles are being edited most frequently right now?" -- a continuously refreshed trending ranking.

#### How it works: Sliding Window Aggregation

```scala
watermarkedEvents
  .groupBy(
    window(col("event_time"), "10 minutes", "30 seconds"),
    col("title"),
    col("server_name")
  )
  .agg(count("*").alias("edit_count"))
```

- **Window duration: 10 minutes** -- each window covers a 10-minute span of event time.
- **Slide interval: 30 seconds** -- a new window starts every 30 seconds.
- **Overlap:** Since slide < duration, windows overlap. At any instant, there are `10 min / 30 sec = 20` overlapping windows. Each incoming edit event is assigned to all 20 windows it falls into.
- **Group-by keys:** `(window, title, server_name)` -- this correctly separates e.g. "Python" on `en.wikipedia.org` from "Python" on `de.wikipedia.org`.

**Why sliding windows instead of tumbling?**
Tumbling windows (non-overlapping) would reset every 10 minutes, causing the trending list to "jump" sharply. Sliding windows provide a smooth, continuously updated view -- ideal for real-time dashboards.

**Output columns:**

| Column | Type | Description |
|--------|------|-------------|
| `window_start` | Timestamp | Start of the 10-minute window |
| `window_end` | Timestamp | End of the 10-minute window |
| `article_title` | String | Article title |
| `server_name` | String | Wiki hostname (e.g., "en.wikipedia.org") |
| `edit_count` | Long | Number of edits in this window |

**Output mode: `append`** -- With a watermark, Spark emits a row only **after** the window is finalised (its end time has passed the watermark). This means no partial/updated results -- each row is final. Delta Lake receives clean, append-only inserts.

**Sink:** Delta Lake table at `delta/trending_articles/`.

---

### 8. Analytics Job 2 -- ActiveEditorsJob

**File:** `src/main/scala/com/wikimedia/streaming/streaming/ActiveEditorsJob.scala`

#### What it computes
"Who is actively editing right now, and how many total edits have they made?" -- a real-time leaderboard of editors that automatically expires inactive users after 1 hour.

#### Why mapGroupsWithState is needed

This job **cannot** be implemented with simple window aggregations because:
1. We need a **running total** across all time (not per-window counts).
2. We need to **expire** editors after 1 hour of inactivity to prevent unbounded state growth.
3. We need to emit an **output row** both for active and expired editors (so downstream consumers know when someone went inactive).

#### Internal data types

| Type | Purpose | Fields |
|------|---------|--------|
| `EditorEvent` | Input struct | `user: String`, `event_time: Timestamp` |
| `EditorState` | Persistent state per editor | `user: String`, `totalEdits: Long`, `lastActivityEpochMs: Long` |
| `EditorOutput` | Output row to Delta | `user: String`, `total_edits: Long`, `last_activity: Timestamp`, `is_expired: Boolean` |

#### Processing pipeline

```
watermarkedEvents
  -> select("user", "event_time")         Extract only needed fields
  -> .as[EditorEvent]                      Convert to typed Dataset
  -> .groupByKey(_.user)                   One state object per editor
  -> .mapGroupsWithState(EventTimeTimeout)(updateEditorState)
```

#### State update function (updateEditorState)

For each micro-batch, Spark calls this function once per editor who either has new events or whose timeout has expired:

**Normal path (new events arrive):**
1. Retrieve existing state (or create new with `totalEdits=0`).
2. Add the count of new events to `totalEdits`.
3. Find the latest `event_time` in the batch.
4. Save updated state via `state.update(updatedState)`.
5. Set timeout to `latestEventTime + 1 hour` via `state.setTimeoutTimestamp(...)`.
6. Return `EditorOutput(is_expired = false)`.

**Timeout path (editor inactive for 1+ hour in event time):**
1. `state.hasTimedOut` is `true`.
2. Retrieve the last known state via `state.get`.
3. Call `state.remove()` to free memory.
4. Return `EditorOutput(is_expired = true)`.

**Why EventTimeTimeout instead of ProcessingTimeTimeout?**
- `EventTimeTimeout` is driven by the watermark (event time), so replaying historical data at high speed correctly ages out editors based on event timestamps.
- `ProcessingTimeTimeout` uses wall-clock time, which would give incorrect results during replay.

**Output mode: `update`** -- Emit a row for each editor whose state changed in this micro-batch. `complete` mode would re-emit ALL editors every batch (too expensive). `append` mode is not supported with `mapGroupsWithState`.

**Sink:** Delta Lake table at `delta/active_editors/` via `foreachBatch`. Delta Lake 4.1+ does not support `update` output mode as a direct streaming sink, so each micro-batch is written as an append to Delta using the `foreachBatch` pattern.

---

### 9. Analytics Job 3 -- EditSpikeDetector

**File:** `src/main/scala/com/wikimedia/streaming/streaming/EditSpikeDetector.scala`

#### What it computes
"Is any article suddenly receiving an abnormal burst of edits compared to its recent baseline?" -- alerts when an article's current edit rate exceeds 5x its recent average.

#### Two-Stage Pipeline Architecture

**Stage 1: Tumbling Window Aggregation (stateless)**

```scala
watermarkedEvents
  .groupBy(window(col("event_time"), "1 minute"), col("title"))
  .agg(count("*").alias("edit_count"))
```

Count edits per article per 1-minute tumbling window. Tumbling windows are non-overlapping -- each edit belongs to exactly one window. This produces a stream of `WindowedCount(title, window_start, window_end, edit_count)` records.

```
|<- 1 min ->|<- 1 min ->|<- 1 min ->|<- 1 min ->|
[ window 1  ][ window 2  ][ window 3  ][ window 4  ]
------------------------------------------------------> event time
```

**Stage 2: Stateful Spike Detection (per article)**

```scala
windowedCounts.as[WindowedCount]
  .groupByKey(_.title)
  .mapGroupsWithState(ProcessingTimeTimeout)(detectSpike)
```

**Why stateful processing is needed here:** Stage 2 cannot be expressed as a plain window aggregation because we need to compare ACROSS windows (current vs historical average), not within a single window.

> **Note on timeout type:** `ProcessingTimeTimeout` is used instead of `EventTimeTimeout` because the windowed aggregation in Stage 1 already handles event-time semantics (watermark-based window finalisation). The watermark metadata is consumed by the window aggregation and is no longer available for `mapGroupsWithState` in the second stateful stage. `ProcessingTimeTimeout` correctly expires quiet articles based on wall-clock time.

#### State per article (ArticleSpikeState)

| Field | Type | Description |
|-------|------|-------------|
| `title` | String | Article name (group key) |
| `historicalCounts` | List[Long] | Rolling buffer of up to 10 recent per-minute edit counts |
| `lastUpdateEpochMs` | Long | Epoch ms of the last processed window |

#### Detection algorithm (detectSpike function)

**Normal path:**
1. Receive the latest `WindowedCount` for this article (take the one with the most recent `window_end`).
2. Compute `historicalAverage = sum(historicalCounts) / historicalCounts.size`.
3. **Spike condition:** `historicalCounts.nonEmpty AND currentCount > historicalAverage * 5.0`
   - Must have history (otherwise every article's first edit would be a false "spike").
   - Current count must exceed 5x the historical average.
4. Compute `spike_multiplier = currentCount / historicalAverage` (the actual ratio).
5. Append `currentCount` to the rolling buffer, keep only the last 10 entries via `.takeRight(HistorySize)`.
6. Set timeout to `window_end + 30 minutes` (expire quiet articles).

**Timeout path:** If an article has no edits for 30 minutes, state is removed and a sentinel row is emitted (`is_spike=false, current_edit_count=0`), so downstream consumers know the article is quiet.

#### Output columns

| Column | Type | Description |
|--------|------|-------------|
| `article_title` | String | Article name |
| `window_start` | Timestamp | Start of the 1-minute window |
| `window_end` | Timestamp | End of the 1-minute window |
| `current_edit_count` | Long | Edits in the current window |
| `historical_average` | Double | Average of the last 10 window counts |
| `spike_multiplier` | Double | `current / historical_average` |
| `is_spike` | Boolean | `true` if spike detected |

**Tuning:** `SpikeMultiplier = 5.0` is deliberately aggressive for demo purposes. For production dashboards, lower it to 2-3x to catch more subtle surges.

**Sink:** Delta Lake table at `delta/edit_spikes/` via `foreachBatch`. Delta Lake 4.1+ does not support `update` output mode as a direct streaming sink, so each micro-batch is written as an append to Delta using the `foreachBatch` pattern.

---

### 10. Infrastructure -- Docker Compose and Kafka KRaft

**File:** `docker/docker-compose.yml`

Runs a **single-node Kafka 3.9.0 broker** using Confluent's `cp-kafka:7.9.0` Docker image in **KRaft mode** (Kafka Raft Metadata mode) -- no Zookeeper required.

#### KRaft Mode Explained
- **What:** KRaft replaces Zookeeper for Kafka's internal metadata management (broker registration, topic configs, partition leadership). Metadata is stored in a Kafka-internal topic managed by the Raft consensus protocol.
- **Why:** Zookeeper was a separate system to operate, monitor, and scale. KRaft simplifies Kafka deployment to a single process.
- **Status:** Production-ready since Kafka 3.3; Zookeeper support removed in Kafka 4.0.

#### Container Configuration

| Setting | Value | Purpose |
|---------|-------|---------|
| `KAFKA_PROCESS_ROLES` | `broker,controller` | Combined mode: this node is both a data broker and a metadata controller |
| `KAFKA_NODE_ID` | `1` | Unique identifier for this node in the cluster |
| `KAFKA_CLUSTER_ID` | `MkU3OE...` | Pre-generated UUID for the cluster (normally from `kafka-storage random-uuid`) |
| `KAFKA_CONTROLLER_QUORUM_VOTERS` | `1@kafka:9093` | Raft quorum -- just this one node for development |

#### Network Listeners

| Listener | Port | Who Uses It |
|----------|------|------------|
| `PLAINTEXT_HOST` | 9092 | Host machine (your Scala producer, Spark) connects via `localhost:9092` |
| `PLAINTEXT` | 29092 | Other containers in the Docker network connect via `kafka:29092` |
| `CONTROLLER` | 9093 | Internal KRaft controller traffic (not exposed to host) |

#### Other Settings
- **`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`** -- Topics are auto-created when a producer first writes to them.
- **`KAFKA_LOG_RETENTION_HOURS=24`** -- Log segments are retained for 1 day (dev setting to avoid filling disk).
- **Health check:** Runs `kafka-topics --list` every 10 seconds; the container is "healthy" once it responds.
- **Named volume `kafka-data`** -- Data persists across container restarts (`docker compose down` keeps it; `docker compose down -v` deletes it).

---

## Core Streaming Concepts Explained

### Event-Time vs Processing-Time

| Concept | Definition | Example |
|---------|-----------|---------|
| **Event time** | Timestamp embedded in the event itself -- when the edit *actually happened* on Wikipedia | `12:00:00` |
| **Processing time** | Wall-clock time when the event *arrives at Spark* | `12:00:45` |

**Why event time matters:**
```
Edit happens at 12:00:00 -> network delay -> arrives at Spark at 12:00:45

Window [12:00:00 - 12:10:00] should include this event.
If we used processing time, the event lands in the wrong window.
```

This project uses `col("timestamp").cast(TimestampType)` (the MediaWiki edit timestamp) as event time throughout. All window operations are keyed on `event_time`, ensuring correctness regardless of network delays.

---

### Watermarking

**The problem:** How long should Spark wait for late-arriving events before finalising a window and freeing its state?

**The solution:** `.withWatermark("event_time", "10 minutes")`

```
Watermark formula:
  watermark = max(event_time seen across all partitions) - threshold

Example timeline:
  12:15:00 -- latest event observed
  12:05:00 -- watermark = 12:15:00 - 10 min

  Event with event_time = 12:04:59 -> DROPPED (older than watermark)
  Event with event_time = 12:05:01 -> ACCEPTED
```

**What happens without watermarking:**
- State for every window that has ever existed must be kept in memory forever.
- For a 10-minute sliding window with 30-second slides, 20 windows are open at any instant.
- Without cleanup, the number of windows grows without bound -> **Out of Memory (OOM) crash**.

**Trade-off:** The 10-minute threshold means events arriving more than 10 minutes late are silently dropped. This is acceptable for a real-time dashboard but may need tuning for use cases requiring stricter completeness.

---

### Window Aggregations (Tumbling vs Sliding)

#### Tumbling Windows (used in EditSpikeDetector Stage 1)
```
|<- 1 min ->|<- 1 min ->|<- 1 min ->|<- 1 min ->|
[ window 1  ][ window 2  ][ window 3  ][ window 4  ]
------------------------------------------------------> event time
```
- **Non-overlapping:** Each event belongs to exactly one window.
- **Use case:** Counting edits per article per minute for spike detection -- each minute is an independent measurement.

#### Sliding Windows (used in TrendingArticlesJob)
```
|<-------- 10 min -------->|
[       window 1           ]
        [       window 2           ]
                [       window 3           ]
----------------------------------------------> event time
  <-30s->
```
- **Overlapping:** Each event belongs to `duration / slide = 10min / 30sec = 20` windows.
- **Use case:** Continuously refreshed "trending" ranking -- smoother than tumbling windows which would cause sharp jumps every reset interval.

---

### Stateful Processing with mapGroupsWithState

`mapGroupsWithState` is Spark's API for maintaining **arbitrary per-key state** across micro-batches. Used by both **ActiveEditorsJob** and **EditSpikeDetector**.

**How it works:**
```scala
dataset
  .groupByKey(keyFunction)                          // Define groups (e.g., by user or article)
  .mapGroupsWithState(timeout)(updateFunction)      // Custom state update logic
```

For each micro-batch, Spark calls `updateFunction` with:
1. **The group key** (e.g., username or article title).
2. **Iterator of new events** for that key in this batch.
3. **`GroupState[S]` handle** -- read/write/remove persistent state of type `S`.

**State expiry with EventTimeTimeout:**
```scala
state.setTimeoutTimestamp(latestEventMs + 1 * 60 * 60 * 1000)  // 1 hour
```
When the streaming watermark advances past this timestamp and no new events have arrived for the key, Spark calls the update function one final time with `state.hasTimedOut = true`, allowing cleanup and emission of a final output row.

> **Spark 4.0 note:** Spark 4.0 introduced `transformWithState` as a more flexible and performant successor. For new production systems targeting Spark 4.0+, `transformWithState` is preferred. This project uses `mapGroupsWithState` for broad compatibility and conceptual clarity.

---

### Checkpointing

Each streaming query persists its progress to a checkpoint directory:

```
checkpoint/
+-- trending_articles/
|   +-- commits/           <-- Committed micro-batch IDs
|   +-- offsets/           <-- Kafka partition offsets per batch
|   +-- state/             <-- Serialised aggregation / mapGroupsWithState state
+-- active_editors/
+-- edit_spikes/
```

**Why checkpointing is required:**
- On driver restart, Spark reads the checkpoint to find the last committed Kafka offsets and resumes from exactly that point.
- No events are re-processed or skipped -- this is the foundation of exactly-once semantics.
- Without checkpoints, a restart would lose all in-memory state (window counts, editor totals, spike histories) and re-read Kafka from `latest`, creating data gaps.

---

### Exactly-Once Guarantees

The pipeline achieves **end-to-end exactly-once semantics** through three layers:

| Layer | Mechanism | What It Guarantees |
|-------|-----------|--------------------|
| **Kafka Producer** | `enable.idempotence=true` + `acks=all` | Each message is written to Kafka exactly once, even on retry |
| **Spark Structured Streaming** | Checkpointing (WAL + offset tracking) | Each Kafka message is read and processed exactly once |
| **Delta Lake** | ACID transactions (transaction log) | Each micro-batch output is written atomically; no partial files, no duplicates |

**On failure and restart:**
1. Spark reads the checkpoint to find the last committed micro-batch ID and its Kafka offsets.
2. It re-reads from those offsets (Kafka retains the data).
3. It re-processes the failed batch.
4. Delta's transaction log ensures the same batch ID is never committed twice (idempotent writes).

---

## Quick Reference -- Script Utilities

All operations are wrapped in simple shell scripts under `scripts/`. No need to remember long commands.

### Start

```bash
./scripts/start-all.sh          # One command: Kafka → Producer → Spark (recommended)
./scripts/start-kafka.sh        # Start Kafka only (waits for healthy + creates topic)
./scripts/start-producer.sh     # Start the SSE → Kafka producer only (foreground)
./scripts/start-streaming.sh    # Start Spark streaming only (foreground)
```

### Stop

```bash
./scripts/stop-all.sh           # Stop Spark + Producer + Kafka (keeps data)
./scripts/stop-all.sh --rm      # Stop everything + remove Kafka data volume
```

### Status

```bash
./scripts/status.sh             # Show live status of all components + data sizes
```

Example output:
```
══════════════════════════════════════════════════════════════
  Pipeline Status
══════════════════════════════════════════════════════════════

  Kafka:                ✅ healthy (localhost:9092)
  Kafka Producer:       ✅ running (PID: 12345)
  Spark Streaming:      ✅ running (PID: 12678) — UI: http://localhost:4040

  Delta Lake Tables:
    trending_articles:      ✅ 13 parquet files (328K)
    active_editors:         ✅ 49 parquet files (720K)
    edit_spikes:            ✅ 49 parquet files (876K)

  Checkpoints:
    trending_articles:      ✅ present (1.6M)
    active_editors:         ✅ present (1.0M)
    edit_spikes:            ✅ present (6.3M)

  Fat JAR:              ✅ built (96M)
══════════════════════════════════════════════════════════════
```

### Cleanup

```bash
./scripts/cleanup.sh            # Remove checkpoints + delta tables (keep JAR)
./scripts/cleanup.sh --rebuild  # Remove state + rebuild the fat JAR
./scripts/cleanup.sh --full     # Remove everything (state + JAR + Kafka volume) + rebuild
```

### Build

```bash
./scripts/build.sh              # Clean Maven build of the fat JAR
```

### All Scripts at a Glance

| Script | What It Does |
|--------|-------------|
| `start-all.sh` | Starts Kafka, producer (background), and Spark streaming (foreground) |
| `start-kafka.sh` | Starts Kafka container, waits for healthy, creates `wikimedia-events` topic |
| `start-producer.sh` | Connects to Wikimedia SSE and publishes events to Kafka |
| `start-streaming.sh` | Submits the Spark Structured Streaming app via `spark-submit` |
| `stop-all.sh` | Gracefully stops Spark, producer, and Kafka (in that order) |
| `status.sh` | Shows running/stopped state of every component + Delta table stats |
| `cleanup.sh` | Removes checkpoints, delta tables, and optionally build artifacts |
| `build.sh` | Runs `mvn clean package -DskipTests` to produce the fat JAR |

---

## Getting Started -- Step-by-Step

### 1. Clone the Repository
```bash
git clone https://github.com/rajeshsantha/wikimedia-streaming-analytics.git
cd wikimedia-streaming-analytics
```

### 2. Start Kafka (KRaft mode, no Zookeeper)
```bash
cd docker/
docker compose up -d
```
**What this does:**
- `docker compose up` reads `docker-compose.yml` and starts the Kafka container.
- `-d` runs it in detached (background) mode.
- Kafka starts in KRaft mode -- it manages its own metadata via the Raft protocol, no Zookeeper needed.

Wait for health check:
```bash
docker compose ps
```
Wait until the `kafka` container shows status `healthy` (may take 30-60 seconds).

### 3. Create the Kafka Topic
```bash
docker exec -it kafka kafka-topics \
  --create \
  --topic wikimedia-events \
  --partitions 3 \
  --replication-factor 1 \
  --bootstrap-server localhost:9092
```
**What this does:**
- `docker exec -it kafka` -- run a command inside the running Kafka container.
- `kafka-topics --create` -- creates a new Kafka topic.
- `--topic wikimedia-events` -- topic name (must match `application.conf`).
- `--partitions 3` -- 3 partitions for parallelism (Spark can read from all 3 concurrently).
- `--replication-factor 1` -- single copy (only 1 broker in dev).
- `--bootstrap-server localhost:9092` -- broker address from inside the container.

Verify:
```bash
docker exec -it kafka kafka-topics \
  --list --bootstrap-server localhost:9092
```

### 4. Build the Project
```bash
cd ..           # back to project root
mvn clean package -DskipTests
```
**What this does:**
- `mvn clean` -- deletes the `target/` directory from any previous build.
- `package` -- compiles Scala sources (via `scala-maven-plugin`), then packages them into a fat/uber JAR (via `maven-shade-plugin`).
- `-DskipTests` -- skips running tests to speed up the build.
- **Output:** `target/wikimedia-streaming-analytics-1.0.0.jar` containing all dependencies bundled together (except Spark Core/SQL which are `provided` scope and supplied by `spark-submit` at runtime).

### 5. Start the Kafka Producer

Open a **dedicated terminal** and run:
```bash
mvn exec:java -Dexec.mainClass="com.wikimedia.streaming.producer.WikimediaKafkaProducer"
```
**What this does:**
- `mvn exec:java` -- uses the `exec-maven-plugin` to run a Java/Scala main class directly (without needing `spark-submit`, since this is a plain JVM app not a Spark app).
- `-Dexec.mainClass=...` -- specifies the class to run.
- The producer connects to the Wikimedia SSE feed, receives live edit events, and publishes each one to the `wikimedia-events` Kafka topic.
- **Leave this running** -- it continuously streams events.

Expected output:
```
[WikimediaProducer] Connected to https://stream.wikimedia.org/v2/stream/recentchange
[WikimediaProducer] Published 100 events to topic 'wikimedia-events'
[WikimediaProducer] Published 200 events to topic 'wikimedia-events'
...
```

### 6. Run the Spark Streaming Job

Open a **second terminal** and submit:
```bash
spark-submit \
  --class com.wikimedia.streaming.streaming.StreamingApp \
  --master "local[*]" \
  --packages io.delta:delta-spark_2.13:4.1.0 \
  target/wikimedia-streaming-analytics-1.0.0.jar
```
**What this does:**
- `spark-submit` -- Spark's CLI tool for launching applications on a cluster (or locally).
- `--class ...StreamingApp` -- the main class containing the `main(args)` method.
- `--master "local[*]"` -- run locally using all available CPU cores.
- `--packages io.delta:delta-spark_2.13:4.1.0` -- download Delta Lake 4.1.0 from Maven Central at runtime and add it to the classpath.
- The JAR path is the fat JAR produced by step 4.

The job starts three streaming queries that process Kafka events and write results to Delta Lake.

### 7. Verify Delta Outputs

After 1-2 minutes, check that Delta table directories have been created:
```bash
ls delta/trending_articles/
ls delta/active_editors/
ls delta/edit_spikes/
```

---

## Querying Results

### Spark Shell
```bash
spark-shell \
  --packages io.delta:delta-spark_2.13:4.1.0 \
  --conf spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension \
  --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog
```

```scala
// Trending articles
spark.read.format("delta").load("delta/trending_articles").show(20, truncate = false)

// Active editors
spark.read.format("delta").load("delta/active_editors").show(20, truncate = false)

// Spike events only
spark.read.format("delta").load("delta/edit_spikes").filter("is_spike = true").show(20, truncate = false)
```

### Sample SQL Queries

#### Top trending articles in the last hour
```sql
SELECT article_title, server_name, SUM(edit_count) AS total_edits
FROM delta.`delta/trending_articles`
WHERE window_start >= current_timestamp() - INTERVAL 1 HOUR
GROUP BY article_title, server_name
ORDER BY total_edits DESC
LIMIT 20;
```

#### Most active editors (not expired)
```sql
SELECT user, total_edits, last_activity
FROM delta.`delta/active_editors`
WHERE is_expired = false
ORDER BY total_edits DESC
LIMIT 20;
```

#### Articles with spike activity
```sql
SELECT article_title, window_start, window_end,
       current_edit_count, historical_average,
       ROUND(spike_multiplier, 2) AS spike_ratio
FROM delta.`delta/edit_spikes`
WHERE is_spike = true
ORDER BY window_start DESC
LIMIT 20;
```

#### Edit activity heatmap by hour
```sql
SELECT
  DATE_TRUNC('hour', window_start) AS hour_bucket,
  server_name,
  SUM(edit_count) AS total_edits
FROM delta.`delta/trending_articles`
GROUP BY DATE_TRUNC('hour', window_start), server_name
ORDER BY hour_bucket DESC, total_edits DESC;
```

---

## Spark Configuration Reference

| Parameter | Value | Reason |
|-----------|-------|--------|
| `spark.sql.shuffle.partitions` | `4` | Default 200 wastes resources on a single machine |
| `spark.sql.streaming.stateStore.providerClass` | `HDFSBackedStateStoreProvider` | Default; swap for `RocksDBStateStoreProvider` at production scale |
| `spark.sql.extensions` | `io.delta.sql.DeltaSparkSessionExtension` | Enables Delta SQL (MERGE, OPTIMIZE, VACUUM) |
| `spark.sql.catalog.spark_catalog` | `org.apache.spark.sql.delta.catalog.DeltaCatalog` | Delta-aware catalog for table management |
| `spark.sql.streaming.statefulOperator.checkCorrectness.enabled` | `false` | Allows chained stateful operators (e.g., window aggregation → mapGroupsWithState) in EditSpikeDetector. Safe because the upstream watermark already handles late data. |

---

## Stopping the Pipeline

### Recommended: Use the script
```bash
./scripts/stop-all.sh           # stop Spark + Producer + Kafka (keeps data)
./scripts/stop-all.sh --rm      # also removes the Kafka data volume
```

### Manual approach
```bash
# Stop the Spark job
Ctrl+C   (in the spark-submit terminal)

# Stop the Kafka producer
Ctrl+C   (in the producer terminal)

# Stop Kafka
cd docker/
docker compose down        # keeps data volume
docker compose down -v     # also removes data volume
```

---

## Troubleshooting

### Kafka container not starting
```bash
docker compose logs kafka
# Look for: "KafkaServer id=1 started"
```
Common cause: port 9092 already in use. Change the host port mapping in `docker-compose.yml`.

### No events arriving in Kafka
1. Check the producer logs for HTTP errors.
2. Verify internet access to `stream.wikimedia.org`.
3. Increase the log level: `-Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG`.

### Out of memory (OOM) during streaming
- Increase driver memory: `--driver-memory 2g` in the spark-submit command.
- Reduce the watermark threshold to allow faster state clean-up.
- Consider switching to `RocksDBStateStoreProvider` for the state store.

### Delta table schema mismatch after code changes
Delete the checkpoint directory for the affected query and restart. The query will re-process from the latest Kafka offsets.

### Clean restart (reset all state)
```bash
./scripts/cleanup.sh            # remove checkpoints + delta tables
./scripts/cleanup.sh --full     # also remove build artifacts + Kafka volume + rebuild
```
Or manually:
```bash
rm -rf checkpoint/ delta/
```
This removes all checkpoints and Delta tables. The pipeline will start fresh on next run.
