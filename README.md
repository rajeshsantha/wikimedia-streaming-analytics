# 📡 Wikimedia Streaming Analytics

A **production-grade real-time data engineering project** that consumes live Wikipedia edit events via Server-Sent Events (SSE), publishes them to Apache Kafka, processes them with Spark Structured Streaming, and writes analytics results to Delta Lake tables.

---

## Overview

Every second, thousands of edits are made across all Wikimedia wikis (Wikipedia, Wiktionary, Wikidata, …).  This project taps into the public [Wikimedia EventStreams](https://wikitech.wikimedia.org/wiki/Event_Platform/EventStreams) feed and builds three real-time analytics pipelines on top of it:

| Job | What it does |
|-----|-------------|
| **TrendingArticlesJob** | Sliding-window (10 min / 30 sec) edit counts per article |
| **ActiveEditorsJob** | Stateful running edit total per editor with 1-hour inactivity expiry |
| **EditSpikeDetector** | Z-score-style spike detection: alerts when an article's edit rate exceeds 5× its recent average |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                       Wikimedia EventStreams                         │
│          https://stream.wikimedia.org/v2/stream/recentchange        │
│                         (SSE / HTTP)                                 │
└────────────────────────────┬────────────────────────────────────────┘
                             │  JSON events (one per line)
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│             WikimediaKafkaProducer  (OkHttp SSE + Kafka)            │
│   • acks=all, idempotence, snappy compression, linger=20ms          │
└────────────────────────────┬────────────────────────────────────────┘
                             │  ProducerRecord → topic: wikimedia-events
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│              Apache Kafka 3.9.0  (KRaft, no Zookeeper)              │
│              confluentinc/cp-kafka:7.9.0  — localhost:9092          │
└──────┬──────────────────────────────────────────────────────────────┘
       │  readStream.format("kafka")
       ▼
┌─────────────────────────────────────────────────────────────────────┐
│          Spark Structured Streaming  (Spark 4.0.0 / Scala 2.13)     │
│                                                                     │
│  JSON parse → filter type="edit" → event_time cast → watermark      │
│                                                                     │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │ TrendingArticles │  │  ActiveEditors   │  │ EditSpike        │  │
│  │ sliding window   │  │ mapGroupsWithSt. │  │ Detector         │  │
│  │ 10min / 30sec    │  │ 1-hour expiry    │  │ 5× spike alert   │  │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘  │
└───────────┼────────────────────┼────────────────────┼─────────────┘
            │                    │                    │
            ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       Delta Lake 4.0.0                              │
│  delta/trending_articles/   delta/active_editors/   delta/edit_spikes/ │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Tech Stack

| Component | Version | Notes |
|-----------|---------|-------|
| Apache Spark | **4.0.0** | Structured Streaming engine |
| Scala | **2.13** (2.13.14) | Spark 4.x dropped Scala 2.12 |
| Apache Kafka | **3.9.0** | KRaft mode — no Zookeeper |
| Delta Lake | **4.0.0** | delta-spark_2.13, compatible with Spark 4.0+ |
| Maven | 3.9+ | Build tool |
| Docker | Desktop / Engine | Runs Kafka locally |
| OkHttp SSE | 4.12.0 | Wikimedia EventStreams consumer |
| Java | **17+** | Required by Spark 4.0 |

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java (JDK) | 17 + | `brew install openjdk@17` or [Adoptium](https://adoptium.net) |
| Maven | 3.9 + | `brew install maven` |
| Docker Desktop | latest | [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/) |
| Apache Spark | 4.0.0 | `brew install apache-spark` or [spark.apache.org/downloads](https://spark.apache.org/downloads.html) |

Verify:
```bash
java -version        # openjdk 17 or later
mvn -version         # Apache Maven 3.9.x
docker version       # Docker Engine / Desktop
spark-submit --version  # version 4.0.0
```

---

## Project Structure

```
wikimedia-streaming-analytics/
├── docker/
│   └── docker-compose.yml          # Kafka 3.9 in KRaft mode
├── src/main/
│   ├── resources/
│   │   └── application.conf        # All config (Kafka, Spark, Delta paths)
│   └── scala/com/wikimedia/streaming/
│       ├── models/
│       │   └── WikiEditEvent.scala  # Case class + Spark schema
│       ├── producer/
│       │   └── WikimediaKafkaProducer.scala  # SSE → Kafka
│       ├── streaming/
│       │   ├── StreamingApp.scala       # Main entry point
│       │   ├── TrendingArticlesJob.scala
│       │   ├── ActiveEditorsJob.scala
│       │   └── EditSpikeDetector.scala
│       └── utils/
│           ├── KafkaConfig.scala        # Centralised config accessor
│           └── SparkSessionFactory.scala
├── delta/                           # Delta Lake table data (git-ignored)
├── checkpoint/                      # Spark checkpoint dirs (git-ignored)
├── pom.xml
└── README.md
```

---

## Getting Started

### 1. Clone the repository
```bash
git clone https://github.com/rajeshsantha/wikimedia-streaming-analytics.git
cd wikimedia-streaming-analytics
```

### 2. Start Kafka (KRaft mode, no Zookeeper)
```bash
cd docker/
docker compose up -d
docker compose ps      # wait until kafka is "healthy"
```

### 3. Create the Kafka topic
```bash
docker exec -it kafka kafka-topics \
  --create \
  --topic wikimedia-events \
  --partitions 3 \
  --replication-factor 1 \
  --bootstrap-server localhost:9092
```

Verify:
```bash
docker exec -it kafka kafka-topics \
  --list --bootstrap-server localhost:9092
```

### 4. Build the project
```bash
cd ..           # back to project root
mvn clean package -DskipTests
```

This produces `target/wikimedia-streaming-analytics-1.0.0.jar` (fat JAR with all dependencies).

### 5. Start the Kafka producer

The producer connects to the Wikimedia SSE feed and forwards every event to Kafka.  Run it in a **dedicated terminal** and leave it running.

```bash
mvn exec:java -Dexec.mainClass="com.wikimedia.streaming.producer.WikimediaKafkaProducer"
```

You should see output like:
```
[WikimediaProducer] Connected to https://stream.wikimedia.org/v2/stream/recentchange
[WikimediaProducer] Published 100 events to topic 'wikimedia-events'
[WikimediaProducer] Published 200 events to topic 'wikimedia-events'
```

### 6. Run the Spark Streaming job

Open a **second terminal** and submit the Spark job:

```bash
$SPARK_HOME/bin/spark-submit \
  --class com.wikimedia.streaming.streaming.StreamingApp \
  --master "local[*]" \
  --packages io.delta:delta-spark_2.13:4.0.0 \
  target/wikimedia-streaming-analytics-1.0.0.jar
```

The job will print progress as micro-batches complete.

### 7. Verify Delta outputs

After a minute or two you should see Delta table directories:

```bash
ls delta/trending_articles/
ls delta/active_editors/
ls delta/edit_spikes/
```

### 8. Query the results with Spark Shell

```bash
$SPARK_HOME/bin/spark-shell \
  --packages io.delta:delta-spark_2.13:4.0.0 \
  --conf spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension \
  --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog
```

```scala
spark.read.format("delta").load("delta/trending_articles").show(20, truncate = false)
spark.read.format("delta").load("delta/active_editors").show(20, truncate = false)
spark.read.format("delta").load("delta/edit_spikes").filter("is_spike = true").show(20, truncate = false)
```

---

## Sample SQL Queries

### Top trending articles in the last hour
```sql
SELECT article_title, server_name, SUM(edit_count) AS total_edits
FROM delta.`delta/trending_articles`
WHERE window_start >= current_timestamp() - INTERVAL 1 HOUR
GROUP BY article_title, server_name
ORDER BY total_edits DESC
LIMIT 20;
```

### Most active editors (not expired)
```sql
SELECT user, total_edits, last_activity
FROM delta.`delta/active_editors`
WHERE is_expired = false
ORDER BY total_edits DESC
LIMIT 20;
```

### Articles with spike activity
```sql
SELECT article_title, window_start, window_end,
       current_edit_count, historical_average,
       ROUND(spike_multiplier, 2) AS spike_ratio
FROM delta.`delta/edit_spikes`
WHERE is_spike = true
ORDER BY window_start DESC
LIMIT 20;
```

### Edit activity heatmap by hour
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

## Streaming Concepts Deep Dive

### Event-Time Processing

**Processing time** is the wall-clock time when a record arrives at Spark.  
**Event time** is the timestamp embedded in the record itself — when the edit actually happened.

Event-time processing gives *correct* results:

```
Event happens at 12:00:00  →  network delay  →  arrives at Spark at 12:00:45
                                                                 ↑ processing time

Window [12:00:00 – 12:10:00]  should include this event.
If we used processing time, the event lands in [11:50:00 – 12:00:00] — wrong!
```

This project uses `col("timestamp").cast(TimestampType)` (the edit timestamp from MediaWiki) as the event time throughout.

---

### Watermarking

**The problem**: how long should Spark wait for late events before finalising a window?

**The solution**: `.withWatermark("event_time", "10 minutes")`

```
Watermark = max(event_time seen so far) − 10 minutes

Timeline:
  12:15:00 — latest event observed
  12:05:00 — watermark (15 − 10 = 05)

  Event with event_time = 12:04:59 → DROPPED (before watermark)
  Event with event_time = 12:05:01 → ACCEPTED
```

**What gets accepted**: events with `event_time ≥ watermark`  
**What gets dropped**: events with `event_time < watermark` (silently, no error)  
**Without watermarking**: state grows unbounded → OOM crash

---

### Window Aggregations

**Tumbling windows** (non-overlapping) — used in EditSpikeDetector:
```
|← 1 min →|← 1 min →|← 1 min →|
[window 1 ][window 2 ][window 3 ]
───────────────────────────────► event time
```
Each event belongs to exactly one window.

**Sliding windows** (overlapping) — used in TrendingArticlesJob:
```
|←──── 10 min ────→|
[    window 1      ]
      [    window 2      ]
            [    window 3      ]
───────────────────────────────────► event time
←30s→←30s→←30s→
```
Each event belongs to `duration / slide = 10min / 30sec = 20` windows simultaneously — perfect for continuously refreshed "trending" rankings.

---

### Stateful Processing

`mapGroupsWithState` lets you maintain arbitrary per-key state across micro-batches:

```scala
editorEvents
  .groupByKey(_.user)
  .mapGroupsWithState(GroupStateTimeout.EventTimeTimeout)(updateEditorState)
```

For each micro-batch Spark calls `updateEditorState` with:
- The group key (user name)
- All new events for that user in this batch
- A `GroupState[EditorState]` handle (read / write / remove)

**State expiry**: we call `state.setTimeoutTimestamp(latestEventMs + 1 hour)`.  
When no events arrive for an editor within 1 hour of event time, Spark calls the function with `state.hasTimedOut = true`, allowing us to clean up.

> **Spark 4.0 note**: Spark 4.0 introduced `transformWithState` as a more
> flexible and performant successor.  For new production systems targeting
> Spark 4.0+, `transformWithState` is preferred.

---

### Checkpointing

Each streaming query persists its state to a checkpoint directory:

```
checkpoint/
├── trending_articles/
│   ├── commits/           ← committed micro-batch IDs
│   ├── offsets/           ← Kafka partition offsets per batch
│   └── state/             ← serialised aggregation state
├── active_editors/
└── edit_spikes/
```

**Why required**: if the Spark driver restarts, it reads the checkpoint to find the last committed Kafka offsets and resumes from exactly that point — no events are re-processed or skipped.

---

### Exactly-Once Guarantees

The pipeline achieves end-to-end exactly-once semantics through three layers:

| Layer | Mechanism | Guarantee |
|-------|-----------|-----------|
| **Kafka producer** | `enable.idempotence=true` + `acks=all` | No duplicate messages on retry |
| **Spark Structured Streaming** | Checkpointing (WAL + offset tracking) | Read each Kafka message exactly once |
| **Delta Lake** | ACID transactions (transaction log) | Writes are atomic; no partial files |

On failure, Spark re-runs the failed micro-batch using the last committed Kafka offsets.  Delta's transaction log ensures idempotent writes (the same batch ID is never committed twice).

---

## Spark Configuration Reference

| Parameter | Value | Reason |
|-----------|-------|--------|
| `spark.sql.shuffle.partitions` | `4` | Default 200 wastes resources on a single machine |
| `spark.sql.streaming.stateStore.providerClass` | `HDFSBackedStateStoreProvider` | Default; swap for `RocksDBStateStoreProvider` at production scale |
| `spark.sql.extensions` | `io.delta.sql.DeltaSparkSessionExtension` | Enables Delta SQL (MERGE, OPTIMIZE, VACUUM) |
| `spark.sql.catalog.spark_catalog` | `org.apache.spark.sql.delta.catalog.DeltaCatalog` | Delta-aware catalog for table management |

---

## Stopping the Pipeline

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
Common cause: port 9092 already in use.  Change the host port mapping in `docker-compose.yml`.

### No events arriving in Kafka
1. Check the producer logs for HTTP errors.
2. Verify internet access to `stream.wikimedia.org`.
3. Increase the log level: `-Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG`.

### Out of memory (OOM) during streaming
- Increase driver memory: `--driver-memory 2g` in the spark-submit command.
- Reduce the watermark threshold to allow faster state clean-up.
- Consider switching to `RocksDBStateStoreProvider` for the state store.

### Delta table schema mismatch after code changes
Delete the checkpoint directory for the affected query and restart.  The query will re-process from the latest Kafka offsets.

---

## License

MIT — see [LICENSE](LICENSE) for details.
