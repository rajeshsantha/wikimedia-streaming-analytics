# Streaming Data Engineering Concepts

A deep-dive learning guide covering every technical concept used in the
**Wikimedia Streaming Analytics** project — from Kafka fundamentals to
stateful stream processing and exactly-once semantics.

Written for data engineers who want to understand **why** each piece exists,
**how** it works internally, and **where** it appears in this codebase.

---

## Table of Contents

1.  [Project Architecture Overview](#1-project-architecture-overview)
2.  [Apache Kafka Concepts](#2-apache-kafka-concepts)
3.  [Kafka Deployment Using Docker](#3-kafka-deployment-using-docker)
4.  [Spark Structured Streaming Overview](#4-spark-structured-streaming-overview)
5.  [Event Time vs Processing Time](#5-event-time-vs-processing-time)
6.  [Watermarking](#6-watermarking)
7.  [Windowing in Streaming](#7-windowing-in-streaming)
8.  [Stateful Stream Processing](#8-stateful-stream-processing)
9.  [Checkpointing](#9-checkpointing)
10. [Delta Lake Sink](#10-delta-lake-sink)
11. [Exactly-Once Processing](#11-exactly-once-processing)
12. [Handling Failures in Streaming Pipelines](#12-handling-failures-in-streaming-pipelines)
13. [Scaling the Pipeline](#13-scaling-the-pipeline)
14. [Real-World Production Systems](#14-real-world-production-systems)
15. [Key Takeaways](#15-key-takeaways)

---

## 1. Project Architecture Overview

### The Full Data Pipeline

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Wikimedia EventStreams (SSE)                      │
│      https://stream.wikimedia.org/v2/stream/recentchange            │
│       ~50–200 JSON events / second across all wikis                 │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  HTTP Server-Sent Events
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│              WikimediaKafkaProducer  (OkHttp SSE client)            │
│                                                                     │
│  • Receives each JSON event via SSE                                 │
│  • Publishes raw JSON string to Kafka (no transformation)           │
│  • acks=all + idempotence → exactly-once producer delivery          │
│  • Snappy compression, 20 ms linger batching                        │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  ProducerRecord → topic: wikimedia-events
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│              Apache Kafka 3.9  (KRaft mode, Docker)                 │
│                                                                     │
│  • Durable, replayable message log                                  │
│  • 3 partitions × 1 replica (dev setup)                             │
│  • 24-hour retention                                                │
│  • Decouples producer speed from consumer speed                     │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  spark.readStream.format("kafka")
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│           Spark Structured Streaming  (Spark 4.1.1 / Scala 2.13)    │
│                                                                     │
│  StreamingApp.scala orchestrates:                                   │
│    1. JSON parse  →  2. filter type="edit"  →  3. cast timestamp    │
│    4. withWatermark("event_time", "10 minutes")                     │
│                                                                     │
│  ┌──────────────────┐ ┌──────────────────┐ ┌────────────────────┐   │
│  │ TrendingArticles │ │  ActiveEditors   │ │ EditSpikeDetector  │   │
│  │ sliding window   │ │ mapGroupsWithSt. │ │ tumbling+stateful  │   │
│  │ 10min / 30sec    │ │ 1-hour expiry    │ │ 5× spike alert     │   │
│  └────────┬─────────┘ └────────┬─────────┘ └────────┬───────────┘   │
└───────────┼────────────────────┼────────────────────┼───────────────┘
            │                    │                    │
            ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       Delta Lake 4.1.0                              │
│                                                                     │
│  delta/trending_articles/   delta/active_editors/   delta/edit_spikes│
│  ACID transactions  •  schema enforcement  •  time-travel queries   │
└─────────────────────────────────────────────────────────────────────┘
```

### Why Each Component Exists

| Component | Role | Why Not Skip It? |
|-----------|------|------------------|
| **Wikimedia EventStreams** | Live data source — every edit on every wiki, pushed in real time via SSE | Provides a real, high-volume, never-ending stream for testing real-time pipelines |
| **WikimediaKafkaProducer** | Bridges the SSE HTTP feed into Kafka | SSE is one-to-one; Kafka enables fan-out to multiple consumers, replay, and buffering |
| **Apache Kafka** | Durable message bus | Decouples the producer from the consumer. If Spark is down for maintenance, Kafka retains messages. Enables replay, parallel consumption, and back-pressure |
| **Spark Structured Streaming** | Stream processing engine | Provides SQL-like APIs for window aggregations, stateful computations, watermarking, and fault-tolerant micro-batch execution |
| **Delta Lake** | ACID storage layer | Provides exactly-once writes, schema enforcement, time-travel, and MERGE/OPTIMIZE — none of which plain Parquet offers |

### Data Flow in One Sentence

A Wikipedia edit happens → Wikimedia pushes it via SSE → our producer writes
it to Kafka → Spark reads it, parses JSON, applies a watermark, and routes it
to three analytics jobs → each job writes results to a Delta Lake table on
the local filesystem.

---

## 2. Apache Kafka Concepts

### What Is Kafka?

Apache Kafka is a **distributed event streaming platform** — a durable,
ordered, replayable log of records.  Think of it as a database optimised for
sequential writes and sequential reads, not random access.

### Core Concepts

#### Broker

A **broker** is a single Kafka server process.  It receives messages from
producers, stores them on disk, and serves them to consumers.

```
┌──────────────────────────────┐
│         Kafka Broker         │
│                              │
│  ┌─────────┐  ┌─────────┐   │
│  │ Topic A │  │ Topic B │   │
│  │ Part 0  │  │ Part 0  │   │
│  │ Part 1  │  │ Part 1  │   │
│  │ Part 2  │  │ Part 2  │   │
│  └─────────┘  └─────────┘   │
│                              │
│  Disk: /var/lib/kafka/data   │
└──────────────────────────────┘
```

In production, you run 3–5+ brokers for fault tolerance.  This project runs
**one broker** in Docker for local development.

#### Topic

A **topic** is a named category of messages.  This project uses one topic:

```
Topic: wikimedia-events
```

Producers write to it; Spark reads from it.  A topic is like a table name —
it groups related events together.

#### Partition

A topic is split into one or more **partitions**.  Each partition is an
**ordered, immutable, append-only log** of records.

```
Topic: wikimedia-events  (3 partitions)

Partition 0:  [msg0] [msg1] [msg2] [msg3] [msg4] ...
Partition 1:  [msg0] [msg1] [msg2] [msg3] ...
Partition 2:  [msg0] [msg1] [msg2] ...

              oldest ─────────────────────► newest
```

**Why partitions matter:**

1. **Parallelism** — Spark can assign one task per partition, reading them
   concurrently.  With 3 partitions, 3 Spark tasks run in parallel.
2. **Ordering** — Messages within a single partition are strictly ordered.
   Across partitions, there is no global order.
3. **Scalability** — More partitions = more parallel consumers = higher
   throughput.

This project creates the `wikimedia-events` topic with **3 partitions**:

```bash
kafka-topics --create --topic wikimedia-events --partitions 3 --replication-factor 1
```

#### Producer

A **producer** is a client that writes records to a topic.  In this project,
`WikimediaKafkaProducer.scala` is the producer.  It publishes each SSE event
as a `ProducerRecord` with:

- **Key:** `null` (Kafka round-robins across partitions)
- **Value:** Raw JSON string from the SSE feed
- **Topic:** `wikimedia-events`

Key producer settings used in this project:

| Setting | Value | Effect |
|---------|-------|--------|
| `acks` | `all` | Wait for ALL in-sync replicas to confirm the write |
| `enable.idempotence` | `true` | Broker deduplicates retried messages using PID+sequence |
| `linger.ms` | `20` | Batch messages for up to 20 ms before sending (improves throughput) |
| `compression.type` | `snappy` | Compress batches on the wire (~60-70% reduction for JSON) |
| `retries` | `5` | Auto-retry transient network failures |

#### Consumer and Consumer Groups

A **consumer** reads records from topic partitions.  Consumers are organised
into **consumer groups**.  Kafka guarantees that each partition is consumed by
**exactly one consumer** within a group — this is how parallel processing is
achieved without duplicates.

```
Consumer Group: wikimedia-streaming-consumer

  Consumer A ──reads──► Partition 0
  Consumer B ──reads──► Partition 1
  Consumer C ──reads──► Partition 2
```

Spark Structured Streaming creates a consumer group automatically.  Each Spark
executor task acts as a consumer assigned to one or more partitions.

#### Offset

An **offset** is a sequential integer that identifies a record's position
within a partition.  Offsets start at 0 and increment monotonically.

```
Partition 0:  [0] [1] [2] [3] [4] [5] [6] [7] ...
                              ▲
                              │
                     consumer's current offset = 4
                     (next read starts here)
```

Spark tracks committed offsets in its checkpoint directory.  On restart, it
resumes from the last committed offset — no events are skipped or
reprocessed.

#### Replication Factor

The **replication factor** controls how many copies of each partition exist
across different brokers.

```
Replication Factor = 3  (production)

  Broker 1: Partition 0 (leader)    Partition 1 (follower)
  Broker 2: Partition 0 (follower)  Partition 1 (leader)
  Broker 3: Partition 0 (follower)  Partition 1 (follower)
```

- **Leader** handles all reads/writes for the partition.
- **Followers** replicate data from the leader.
- If the leader crashes, a follower is promoted to leader.

This project uses **replication factor 1** (single broker dev setup).
Production systems typically use 3.

#### Log Storage Model

Kafka stores messages on disk as **log segments** — append-only files.  Old
segments are deleted or compacted based on retention policies.

```
/var/lib/kafka/data/wikimedia-events-0/
├── 00000000000000000000.log    ← oldest segment
├── 00000000000000000000.index
├── 00000000000005000.log       ← newer segment
├── 00000000000005000.index
└── ...
```

This project sets `KAFKA_LOG_RETENTION_HOURS=24` — segments older than 24
hours are deleted.

### Why Kafka Instead of Reading the API Directly?

You might ask: "Why not have Spark read the SSE endpoint directly?"

| Concern | Direct SSE → Spark | SSE → Kafka → Spark |
|---------|-------------------|---------------------|
| **Buffering** | If Spark falls behind, events are lost (SSE has no replay) | Kafka retains events for 24h; Spark catches up at its own pace |
| **Replay** | Impossible — SSE is fire-and-forget | Restart Spark with `startingOffsets=earliest` to replay everything |
| **Fan-out** | Only one consumer can read from one SSE connection | Multiple consumers (Spark, dashboards, alerting) read the same topic |
| **Back-pressure** | SSE pushes at whatever rate the server sends | Kafka absorbs bursts; Spark pulls at the rate it can process |
| **Fault tolerance** | SSE connection drops → events lost during reconnect | Kafka retains events; consumer resumes from last committed offset |
| **Exactly-once** | Not possible with SSE alone | Kafka + Spark checkpoints + Delta transactions provide exactly-once |

**Bottom line:** Kafka turns a fragile, single-use SSE connection into a
durable, replayable, multi-consumer event log.

---

## 3. Kafka Deployment Using Docker

### Why Run Kafka in Docker?

Installing Kafka natively requires downloading binaries, configuring JVM
options, managing log directories, and running ZooKeeper (or KRaft).  Docker
wraps all of this into a single container that starts in seconds:

```bash
cd docker/
docker compose up -d      # start
docker compose down        # stop (keep data)
docker compose down -v     # stop + delete data
```

### Docker Concepts Used

#### Container

A **container** is a lightweight, isolated process running inside Docker.  It
has its own filesystem, network stack, and process space — but shares the
host's kernel.  In this project, the Kafka broker runs as one container.

#### docker-compose

`docker-compose.yml` is a declarative file that defines:

- Which image to use (`confluentinc/cp-kafka:7.9.0`)
- Environment variables (KRaft config, listener ports, retention)
- Port mappings (host 9092 → container 9092)
- Volumes (persistent disk for Kafka data)
- Health checks (poll `kafka-topics --list` every 10s)

One `docker compose up -d` command starts everything.

#### Networking

```
┌─────────── Host Machine (macOS) ───────────┐
│                                             │
│  localhost:9092  ──────port mapping────►     │
│                                             │
│  ┌──────── Docker Network ────────────┐     │
│  │                                    │     │
│  │  ┌──────────────────────────────┐  │     │
│  │  │    Kafka Container           │  │     │
│  │  │    hostname: kafka           │  │     │
│  │  │                              │  │     │
│  │  │  0.0.0.0:9092  (PLAINTEXT_  │  │     │
│  │  │                  HOST)       │  │     │
│  │  │  0.0.0.0:29092 (PLAINTEXT)  │  │     │
│  │  │  kafka:9093    (CONTROLLER) │  │     │
│  │  └──────────────────────────────┘  │     │
│  └────────────────────────────────────┘     │
└─────────────────────────────────────────────┘
```

| Listener | Port | Who Connects |
|----------|------|-------------|
| `PLAINTEXT_HOST` | 9092 | Your Scala producer, `spark-submit` (via `localhost`) |
| `PLAINTEXT` | 29092 | Other Docker containers (via `kafka:29092`) |
| `CONTROLLER` | 9093 | Internal KRaft metadata traffic (not exposed to host) |

#### Container Lifecycle

```
docker compose up -d          ──►  Container: created → running
docker compose ps             ──►  Shows status (healthy / unhealthy)
docker compose logs kafka     ──►  View container stdout/stderr
docker compose stop           ──►  SIGTERM → container stops (data preserved)
docker compose down           ──►  Stop + remove container (volume preserved)
docker compose down -v        ──►  Stop + remove container + delete volume
```

### KRaft Mode — Kafka Without ZooKeeper

Historically, Kafka relied on **Apache ZooKeeper** for:

- Broker registration and discovery
- Topic/partition metadata
- Leader election for partitions
- Controller election

Starting with Kafka 2.8, **KRaft** (Kafka Raft Metadata mode) replaces
ZooKeeper entirely.  Metadata is managed internally using the Raft consensus
protocol.

```
Traditional (pre-3.3):              KRaft (3.3+):

┌──────────┐  ┌──────────┐         ┌──────────────────┐
│ ZooKeeper│  │  Kafka   │         │  Kafka Broker +   │
│ Ensemble │◄─│  Broker  │         │  Controller       │
│  (3+ JVM │  │          │         │  (single JVM)     │
│ processes)│  │          │         │                   │
└──────────┘  └──────────┘         └──────────────────┘
  Extra system to operate            One process does both
```

This project uses KRaft mode with a single combined broker+controller node:

```yaml
KAFKA_PROCESS_ROLES: "broker,controller"
KAFKA_NODE_ID: 1
KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093"
```

**Benefits of KRaft:**
- Simpler deployment (one process instead of two)
- Faster controller failover
- No ZooKeeper dependency to manage/monitor/scale
- Production-ready since Kafka 3.3; ZooKeeper removed in Kafka 4.0

### Alternatives to Local Docker Kafka

| Option | Pros | Cons |
|--------|------|------|
| **Docker (this project)** | Fast local setup, reproducible, no install | Not production-grade; single node |
| **Confluent Cloud** | Fully managed, auto-scaling, multi-region | Costs money; requires internet |
| **AWS MSK** | Managed Kafka on AWS, integrates with IAM/S3 | AWS-only; costs money |
| **Native install** | Full control over JVM tuning | Manual setup, manual ZK/KRaft management |

---

## 4. Spark Structured Streaming Overview

### The Core Idea: Unbounded Tables

Spark Structured Streaming treats a live data stream as an **unbounded table**
that grows continuously.  Every new event is like a new row appended to the
table.  You write the same DataFrame/SQL operations you would write for batch
processing — Spark figures out how to execute them incrementally.

```
                  Time ──────────────────────────►

Unbounded Input Table:
  ┌────────────────────────────────────────────────
  │ id │ title         │ user    │ event_time      │
  ├────┼───────────────┼─────────┼─────────────────
  │  1 │ SpaceX        │ Alice   │ 12:00:01       │  ← arrived at t=1
  │  2 │ Python        │ Bob     │ 12:00:02       │  ← arrived at t=2
  │  3 │ SpaceX        │ Carol   │ 12:00:03       │  ← arrived at t=3
  │  4 │ Rust_lang     │ Dave    │ 12:00:04       │  ← arrived at t=4
  │  5 │ SpaceX        │ Eve     │ 12:00:05       │  ← arrived at t=5
  │ .. │ ...           │ ...     │ ...            │  ← keeps growing
  └────────────────────────────────────────────────
```

A query like `SELECT title, COUNT(*) FROM stream GROUP BY title` is
computed **incrementally** — Spark only processes new rows since the last
micro-batch and updates the running totals.

### Micro-Batch Architecture

By default, Spark Structured Streaming uses **micro-batch processing**.  It
polls the source (Kafka) at a regular interval (the **trigger**), reads all
new records since the last batch, processes them as a small DataFrame, and
writes the results to the sink.

```
         trigger               trigger               trigger
           │                     │                     │
           ▼                     ▼                     ▼
  ┌─────────────┐      ┌─────────────┐      ┌─────────────┐
  │ Micro-batch │      │ Micro-batch │      │ Micro-batch │
  │     #1      │      │     #2      │      │     #3      │
  │  50 events  │      │  73 events  │      │  62 events  │
  └──────┬──────┘      └──────┬──────┘      └──────┬──────┘
         │                    │                    │
         ▼                    ▼                    ▼
     Process as           Process as           Process as
     small DataFrame      small DataFrame      small DataFrame
         │                    │                    │
         ▼                    ▼                    ▼
     Write results        Write results        Write results
     to Delta Lake        to Delta Lake        to Delta Lake
```

**In this project:** The default trigger is used (`Trigger.ProcessingTime(0)`)
which means "start the next micro-batch as soon as the previous one finishes."
Typical latency: 1–5 seconds per batch.

### Continuous Processing Mode

Spark also offers a **continuous processing** mode (`Trigger.Continuous`)
with sub-millisecond latency, but it supports only a subset of operations (no
aggregations, no stateful transforms).  This project uses micro-batch because
all three jobs require aggregations or stateful processing.

### Streaming Queries

A **streaming query** is a running instance of a streaming computation.  This
project starts **three streaming queries** from the same input DataFrame:

```scala
TrendingArticlesJob.start(watermarkedEvents, spark)   // query 1
ActiveEditorsJob.start(watermarkedEvents, spark)       // query 2
EditSpikeDetector.start(watermarkedEvents, spark)      // query 3
```

Each query has its own:
- Checkpoint directory (offset tracking + state)
- Output sink (Delta table)
- Query name (`trending_articles`, `active_editors`, `edit_spike_detector`)
- Independent micro-batch execution

They run **in parallel** as background threads in the same Spark driver JVM.

### Output Modes

When writing results, you must choose an output mode:

| Mode | Behaviour | Used By |
|------|-----------|---------|
| **Append** | Only emit new rows that will never change again | `TrendingArticlesJob` (window results are final after watermark) |
| **Update** | Emit only rows whose value changed in this batch | `ActiveEditorsJob`, `EditSpikeDetector` (state updates) |
| **Complete** | Re-emit the entire result table every batch | Not used (too expensive for large state) |

---

## 5. Event Time vs Processing Time

### Definitions

| Concept | Definition | Value in This Project |
|---------|-----------|----------------------|
| **Event time** | When the event **actually happened** at the source | The `timestamp` field from MediaWiki (Unix epoch seconds) |
| **Processing time** | When the event **arrived at Spark** for processing | The wall-clock time on the Spark driver machine |

### Why They Differ

Events travel through multiple hops before reaching Spark.  Each hop adds
latency:

```
  Wikipedia edit happens at 12:00:00.000  (event time)
        │
        ▼
  MediaWiki server processes, writes event  (+50 ms)
        │
        ▼
  SSE pushes to our producer              (+200 ms network)
        │
        ▼
  Producer publishes to Kafka             (+25 ms)
        │
        ▼
  Spark micro-batch reads from Kafka      (+2000 ms trigger wait)
        │
        ▼
  Spark processes the record at 12:00:02.275  (processing time)
```

**The gap:** `event_time = 12:00:00`, `processing_time = 12:00:02`.  The
event is **2.275 seconds late** by processing time.

In practice, network jitter, retries, and producer batching mean events can
arrive seconds to minutes out of order.

### Why Event Time Matters for Analytics

Consider a 1-minute window `[12:00, 12:01)`:

| Approach | What Happens |
|----------|-------------|
| **Processing time** | An edit at `event_time=12:00:59` that arrives at `processing_time=12:01:02` lands in the **wrong** window `[12:01, 12:02)` |
| **Event time** | The same edit correctly lands in `[12:00, 12:01)` based on when it **actually happened** |

Event-time windows give **accurate** analytics regardless of network delays.

### How This Project Uses Event Time

```scala
// StreamingApp.scala — Step 4
val withEventTime = parsedEvents
  .withColumn("event_time", col("timestamp").cast(TimestampType))
```

The MediaWiki `timestamp` (Unix epoch seconds) is cast to Spark's
`TimestampType` and used as the event-time column for all downstream
watermarks and window operations.

---

## 6. Watermarking

### The Problem: How Long to Wait?

When Spark computes a window aggregation keyed on event time, it must decide:

> *"When is it safe to finalise a window and free its memory?"*

Without an answer, Spark would keep **every window** that has ever existed in
memory forever — because a late event for any past window could still arrive.
This leads to unbounded state growth and eventual Out-of-Memory (OOM) crashes.

### The Solution: Watermarks

A **watermark** is a moving threshold that defines the maximum allowed
lateness:

```
watermark = max(event_time seen so far) − threshold
```

In this project:

```scala
// StreamingApp.scala — Step 5
val watermarkedEvents = withEventTime
  .withWatermark("event_time", "10 minutes")
```

This means: *"Events arriving more than 10 minutes late (relative to the
latest event time observed) will be dropped."*

### Visual Example

```
Time ──────────────────────────────────────────────────►

Events arriving:
  12:14:30  event_time = 12:14:30  ← latest seen
  12:14:31  event_time = 12:03:00  ← 11m30s late → DROPPED (older than watermark)
  12:14:32  event_time = 12:05:00  ← 9m32s late  → ACCEPTED (within threshold)
  12:14:33  event_time = 12:14:33  ← on-time     → ACCEPTED

max(event_time) = 12:14:33
watermark       = 12:14:33 − 10 min = 12:04:33

  Events with event_time < 12:04:33 → DROPPED
  Events with event_time ≥ 12:04:33 → ACCEPTED
```

### What Happens to Windows

With a 10-minute window `[12:00, 12:10)`:

```
  Window end = 12:10:00
  Watermark  = 12:04:33  (not yet past window end)
    → Window is still OPEN, accepting events

  --- more events arrive, watermark advances to 12:10:01 ---

  Watermark  = 12:10:01  (past window end)
    → Window is CLOSED and FINALISED
    → Result is emitted (append mode) or updated (update mode)
    → State for this window is FREED from memory
```

### Effect on State and Memory

| Without Watermark | With 10-min Watermark |
|-------------------|----------------------|
| Every window ever created stays in memory | Windows older than `watermark` are cleaned up |
| Memory grows linearly with time | Memory is bounded: only windows within the last ~20 minutes are in memory |
| Eventually OOM crash | Runs indefinitely with stable memory |

### Trade-Off

The watermark threshold is a balance between **completeness** and **memory**:

| Threshold | Late Events Accepted | Memory Usage |
|-----------|---------------------|-------------|
| 1 minute | Only events up to 1 min late | Very low (few open windows) |
| 10 minutes (this project) | Events up to 10 min late | Moderate |
| 1 hour | Events up to 1 hour late | High (many open windows in memory) |
| No watermark | All events (unbounded wait) | Grows forever → OOM |

For a real-time dashboard, 10 minutes is a good balance — most events arrive
within seconds, so very few are dropped.

---

## 7. Windowing in Streaming

### What Are Windows?

A **window** groups events into fixed-size time buckets based on their event
time.  Windows let you ask questions like *"How many edits happened in the
last 10 minutes?"*

### Tumbling Windows

A **tumbling window** is a fixed-size, non-overlapping time bucket.  Every
event belongs to **exactly one** window.

```
|◄── 1 min ──►|◄── 1 min ──►|◄── 1 min ──►|◄── 1 min ──►|
[  window 1   ][  window 2   ][  window 3   ][  window 4   ]
──────────────────────────────────────────────────────────► event time
```

**Used by:** `EditSpikeDetector` Stage 1 — counts edits per article per
1-minute window:

```scala
// EditSpikeDetector.scala — Stage 1
watermarkedEvents
  .groupBy(window(col("event_time"), "1 minute"), col("title"))
  .agg(count("*").alias("edit_count"))
```

**When to use tumbling windows:**
- Each event should be counted exactly once
- Windows are independent measurements (e.g., per-minute counts for spike
  detection)

### Sliding Windows

A **sliding window** is defined by two parameters: **duration** (window size)
and **slide interval** (how often a new window starts).  Because slide <
duration, windows **overlap** — a single event belongs to multiple windows.

```
|◄──────── 10 minutes ────────►|
[          window 1             ]
        [          window 2             ]
                [          window 3             ]
                        [          window 4             ]
──────────────────────────────────────────────────────────► event time
   ◄─30s─►
```

With `duration=10min` and `slide=30sec`, there are `10min / 30sec = 20`
overlapping windows at any instant.  Each event is assigned to all 20 windows
it falls into.

**Used by:** `TrendingArticlesJob` — continuously refreshed trending rankings:

```scala
// TrendingArticlesJob.scala
watermarkedEvents
  .groupBy(
    window(col("event_time"), "10 minutes", "30 seconds"),
    col("title"),
    col("server_name")
  )
  .agg(count("*").alias("edit_count"))
```

**Why sliding over tumbling for trending?**

| Tumbling (10 min) | Sliding (10 min / 30 sec) |
|-------------------|--------------------------|
| Results reset every 10 minutes | Results refresh every 30 seconds |
| Trending list "jumps" at boundaries | Smooth, continuously updated ranking |
| 1 window per 10-min period | 20 overlapping windows — same event counted in all 20 |

Sliding windows are ideal for dashboards where you want smooth, real-time
updates.

### Session Windows

A **session window** groups events that arrive close together in time.  A new
window starts when the **gap** between consecutive events exceeds a threshold.

```
 Events:  ● ● ●    ●●     ●  ●●●●           ●●  ●
          ├─────┤  ├──┤   ├──────┤           ├────┤
         session1 sess.2  session 3          session 4
                  ◄─gap─►                  ◄──gap──►
```

**Not used in this project**, but useful for:
- User session analytics ("how long was a user active on a website?")
- Device connectivity tracking ("when was a sensor online?")

### How Windows Interact with Watermarks

Windows are finalised when the watermark advances past the window's end time:

```
Window [12:00, 12:10)

  watermark at 12:09:59 → window still OPEN (accepting late events)
  watermark at 12:10:00 → window FINALISED → result emitted → state freed
```

In **append mode** (used by TrendingArticlesJob), Spark only writes the result
**after** the window is finalised.  This guarantees each row is complete and
final — no partial results, no overwrites.

---

## 8. Stateful Stream Processing

### What Is "State" in Streaming?

In batch processing, all data is available at once.  In streaming, data
arrives incrementally.  To compute results that span multiple micro-batches
(running totals, averages over time, session tracking), Spark must remember
**state** between batches.

```
Micro-batch 1:  Alice edits 3 articles  →  state: {Alice: 3 edits}
Micro-batch 2:  Alice edits 2 more      →  state: {Alice: 5 edits}
Micro-batch 3:  No Alice events         →  state: {Alice: 5 edits}  (unchanged)
Micro-batch 4:  Alice edits 1 more      →  state: {Alice: 6 edits}
```

Without state, each batch would start from zero and you could only compute
per-batch counts — not running totals.

### Built-in Stateful Operations

Spark handles state automatically for certain operations:

| Operation | State Managed By Spark |
|-----------|----------------------|
| `groupBy().count()` in streaming | Spark maintains per-group running counts |
| `window().agg()` with watermark | Spark maintains per-window partial aggregates |
| `dropDuplicates()` | Spark remembers seen keys |

### Custom State: mapGroupsWithState

When the built-in operations are not enough, `mapGroupsWithState` lets you
define **arbitrary per-key state** with full control over:

1. What data to store per key
2. When to update it
3. When to expire it
4. What output to emit

#### How It Works

```scala
dataset
  .groupByKey(keyFunction)                        // Define groups
  .mapGroupsWithState(timeout)(updateFunction)    // Custom logic
```

For each micro-batch, Spark calls `updateFunction` with three arguments:

```
┌──────────────────────────────────────────────────────────────┐
│  updateFunction(key, newEvents, state)                       │
│                                                              │
│  key:       The group key (e.g., username "Alice")           │
│  newEvents: Iterator of new events for this key in this      │
│             micro-batch                                      │
│  state:     GroupState[S] handle — read/write/remove the     │
│             persistent state of type S                       │
│                                                              │
│  Returns:   One output record                                │
└──────────────────────────────────────────────────────────────┘
```

#### Used in This Project

**ActiveEditorsJob** — tracks running edit count per editor:

```scala
case class EditorState(user: String, totalEdits: Long, lastActivityEpochMs: Long)

editorEvents
  .groupByKey(_.user)
  .mapGroupsWithState(GroupStateTimeout.EventTimeTimeout)(updateEditorState)
```

State lifecycle for one editor:

```
Batch 1:  Alice → 3 new events
  state.getOption → None (first time)
  state.update(EditorState("Alice", 3, 1709827200000))
  state.setTimeoutTimestamp(1709827200000 + 3600000)  // +1 hour
  output: EditorOutput("Alice", 3, ..., is_expired=false)

Batch 2:  Alice → 2 new events
  state.get → EditorState("Alice", 3, ...)
  state.update(EditorState("Alice", 5, 1709827260000))
  output: EditorOutput("Alice", 5, ..., is_expired=false)

Batch N:  No Alice events, watermark past timeout
  state.hasTimedOut → true
  state.get → EditorState("Alice", 5, ...)
  state.remove()  ← frees memory
  output: EditorOutput("Alice", 5, ..., is_expired=true)
```

**EditSpikeDetector** — maintains a rolling history of per-minute edit counts:

```scala
case class ArticleSpikeState(title: String, historicalCounts: List[Long], ...)

windowedCounts
  .groupByKey(_.title)
  .mapGroupsWithState(GroupStateTimeout.ProcessingTimeTimeout)(detectSpike)
```

### State Store: Where Is State Kept?

Spark persists state in a **state store** backed by the local filesystem (or
HDFS/S3 in production):

```
checkpoint/active_editors/state/
├── 0/                          ← partition 0
│   ├── 1.delta                 ← incremental state update for batch 1
│   ├── 2.delta
│   ├── 3.delta
│   └── 10.snapshot             ← full state snapshot (periodic)
├── 1/                          ← partition 1
└── 2/                          ← partition 2
```

**Two state store backends:**

| Backend | Description | Best For |
|---------|-------------|---------|
| `HDFSBackedStateStoreProvider` (this project) | Stores state as versioned Parquet-like files | Small to medium state; simple setup |
| `RocksDBStateStoreProvider` | Uses embedded RocksDB key-value store | Large state (millions of keys); lower latency |

### Why State Must Be Managed Carefully

| Risk | Consequence | Mitigation |
|------|-------------|-----------|
| Unbounded state growth | OOM crash | Use timeouts to expire inactive keys |
| State too large for memory | Slow processing, GC pressure | Use RocksDB backend, increase memory |
| Schema changes to state class | Deserialization failure on restart | Delete checkpoint + restart from scratch |

---

## 9. Checkpointing

### What Is Checkpointing?

**Checkpointing** is how Spark Structured Streaming achieves fault tolerance.
After each micro-batch completes, Spark writes a **checkpoint** — a durable
record of exactly which data has been processed and what state exists.

### What Gets Checkpointed?

```
checkpoint/trending_articles/
├── commits/                ← Which micro-batch IDs have been committed
│   ├── 0                   ← Batch 0 committed successfully
│   ├── 1
│   └── 2
├── offsets/                ← Kafka offsets consumed per batch
│   ├── 0                   ← Batch 0: {partition0: offset 100, p1: 95, p2: 88}
│   ├── 1
│   └── 2
├── sources/                ← Source-specific metadata (e.g., initial offsets)
│   └── 0/
└── state/                  ← Streaming state (window aggregates, mapGroupsWithState)
    ├── 0/                  ← State for partition 0
    ├── 1/
    └── 2/
```

| Directory | Purpose |
|-----------|---------|
| `commits/` | Records which batch IDs completed successfully — prevents re-processing |
| `offsets/` | Records exactly which Kafka offsets were read in each batch — enables replay |
| `state/` | Serialised aggregation / stateful operator data — survives restarts |

### How Recovery Works

```
Normal execution:
  Batch 5: read offsets [100, 95, 88] → process → write to Delta → commit checkpoint
  Batch 6: read offsets [150, 140, 130] → process → write to Delta → commit checkpoint
  Batch 7: read offsets [200, 185, 175] → process → ███ CRASH ███

Recovery after restart:
  Read checkpoint → last committed batch = 6, offsets = [150, 140, 130]
  Resume from batch 7: read offsets [150, 140, 130] → reprocess → write → commit
```

Key points:
1. **No events are skipped** — Spark resumes from the last committed offset
2. **No events are duplicated** — if a batch was partially written to Delta,
   Delta's transaction log prevents duplicate commits
3. **State is restored** — window aggregates, editor counts, spike histories
   are all recovered from the state store

### Checkpoint Rules

| Rule | Reason |
|------|--------|
| Each streaming query must have its own checkpoint directory | Mixing checkpoints from different queries corrupts state |
| Never share a checkpoint between different queries | Even if the query logic is the same |
| Deleting a checkpoint = starting from scratch | All state and offset tracking is lost |
| Checkpoint directories should be on durable storage in production | Local disk is fine for development; use HDFS/S3 in production |

This project uses three separate checkpoint directories:

```
checkpoint/trending_articles/
checkpoint/active_editors/
checkpoint/edit_spikes/
```

---

## 10. Delta Lake Sink

### What Is Delta Lake?

**Delta Lake** is an open-source storage layer that adds **ACID transactions**
on top of Parquet files.  It turns a directory of Parquet files into a
reliable, queryable table.

### Why Not Plain Parquet?

| Feature | Plain Parquet | Delta Lake |
|---------|-------------|-----------|
| **ACID transactions** | ❌ Partial writes leave corrupt state | ✅ Atomic commits via transaction log |
| **Schema enforcement** | ❌ Any schema can be written | ✅ Rejects writes that don't match the schema |
| **Schema evolution** | ❌ Manual migration | ✅ Add columns with `mergeSchema` |
| **Time travel** | ❌ Only latest version | ✅ Query any historical version |
| **Streaming writes** | ❌ No built-in idempotency | ✅ Exactly-once micro-batch writes |
| **MERGE / UPSERT** | ❌ Not supported | ✅ `MERGE INTO` for upserts |
| **OPTIMIZE / VACUUM** | ❌ Manual file management | ✅ Compaction and cleanup commands |

### How Delta Lake Works Internally

A Delta table is just a directory containing:

```
delta/trending_articles/
├── _delta_log/                    ← Transaction log (the source of truth)
│   ├── 00000000000000000000.json  ← Commit 0: added file part-00000.parquet
│   ├── 00000000000000000001.json  ← Commit 1: added file part-00001.parquet
│   ├── 00000000000000000002.json  ← Commit 2: added file part-00002.parquet
│   └── ...
├── part-00000-...snappy.parquet   ← Data file
├── part-00001-...snappy.parquet
├── part-00002-...snappy.parquet
└── ...
```

**The transaction log** (`_delta_log/`) is an ordered sequence of JSON files
(one per commit).  Each file records which Parquet files were added or removed
in that commit.  To read the table, Delta replays the log to determine the
current set of valid files.

### Streaming Writes in This Project

Each of the three streaming queries writes to its own Delta table:

| Query | Delta Table Path | Output Mode | Write Pattern |
|-------|-----------------|-------------|--------------|
| `trending_articles` | `delta/trending_articles/` | Append (direct) | Finalised window results written once |
| `active_editors` | `delta/active_editors/` | Update via foreachBatch | Each micro-batch appended to Delta |
| `edit_spike_detector` | `delta/edit_spikes/` | Update via foreachBatch | Each micro-batch appended to Delta |

**foreachBatch pattern** (used by ActiveEditors and EditSpikeDetector):

```scala
activeEditors.toDF()
  .writeStream
  .outputMode("update")
  .foreachBatch { (batchDF: DataFrame, batchId: Long) =>
    if (!batchDF.isEmpty) {
      batchDF.write
        .format("delta")
        .mode("append")
        .save(KafkaConfig.activeEditorsTablePath)
    }
  }
  .option("checkpointLocation", KafkaConfig.activeEditorsCheckpoint)
  .start()
```

This pattern is used because Delta Lake 4.1+ does not support the `update`
output mode as a direct streaming sink.  Each micro-batch is written as an
atomic Delta commit.

### Querying Delta Tables

```scala
// Read current state
spark.read.format("delta").load("delta/trending_articles").show()

// Time travel — query the table as it was 1 hour ago
spark.read.format("delta")
  .option("timestampAsOf", "2026-03-07 18:00:00")
  .load("delta/trending_articles")
  .show()
```

---

## 11. Exactly-Once Processing

### What Does "Exactly-Once" Mean?

In a streaming system, there are three delivery guarantees:

| Guarantee | Description | Risk |
|-----------|-------------|------|
| **At-most-once** | Events may be lost, never duplicated | Data loss |
| **At-least-once** | Events are never lost, but may be duplicated | Duplicate results |
| **Exactly-once** | Each event is processed exactly one time | No loss, no duplicates |

Exactly-once is the gold standard and the hardest to achieve.

### How This Project Achieves Exactly-Once

The guarantee is built from three interlocking layers:

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: Kafka Producer (exactly-once writes to Kafka)     │
│                                                             │
│  acks=all + enable.idempotence=true                         │
│  • Producer gets a unique PID (Producer ID) from the broker │
│  • Each message gets a monotonic sequence number            │
│  • On retry, broker checks PID+seq — rejects duplicates     │
│  • Result: each SSE event → exactly one Kafka message       │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Layer 2: Spark Checkpointing (exactly-once processing)     │
│                                                             │
│  • Before processing: record which Kafka offsets to read    │
│  • Process the micro-batch                                  │
│  • After processing: commit the batch ID + offsets           │
│  • On crash: re-read from last committed offsets            │
│  • Result: each Kafka message → processed exactly once      │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Layer 3: Delta Lake Transactions (exactly-once output)     │
│                                                             │
│  • Each micro-batch write is an atomic Delta transaction    │
│  • If the batch fails mid-write, the incomplete transaction │
│    is not visible to readers                                │
│  • If the batch is retried, Delta checks the commit log     │
│    and rejects duplicate batch IDs                          │
│  • Result: each micro-batch → exactly one Delta commit      │
└─────────────────────────────────────────────────────────────┘
```

### End-to-End Guarantee

```
SSE event happens
    │  ← Producer idempotence: written to Kafka exactly once
    ▼
Kafka message
    │  ← Spark checkpoint: read + processed exactly once
    ▼
Processing result
    │  ← Delta transaction: written to output exactly once
    ▼
Delta Lake row ← each SSE event → exactly one output row
```

### What "Exactly-Once" Does NOT Cover

- **Events lost before reaching the producer** (e.g., SSE connection drops
  before the producer receives the event) — this is an upstream data loss, not
  a processing failure.
- **Semantic deduplication** (e.g., a user submits the same Wikipedia edit
  twice) — each edit event has a unique `id`, so they are different events.

---

## 12. Handling Failures in Streaming Pipelines

### Scenario 1: Kafka Crashes

```
Producer ──► [Kafka DOWN] ──► Spark
```

**What happens:**
- **Producer:** `send()` calls fail.  With `retries=5`, the producer retries
  automatically.  If Kafka is down for longer, events are buffered in the
  producer's memory (up to `buffer.memory`, default 32 MB) and sent when Kafka
  recovers.
- **Spark:** The Kafka source keeps trying to connect.  Micro-batches stall
  (no new data) until Kafka comes back.
- **Data loss?** Only if the producer's buffer overflows AND Kafka is down for
  an extended period.

**Recovery:** Once Kafka comes back, both the producer and Spark automatically
reconnect.  Spark resumes from the last committed offset — no manual
intervention needed.

### Scenario 2: Spark Crashes

```
Producer ──► Kafka ──► [Spark DOWN]
```

**What happens:**
- **Kafka:** Continues receiving and storing messages.  Messages are retained
  for 24 hours (configured by `KAFKA_LOG_RETENTION_HOURS`).
- **Producer:** Unaffected — keeps publishing to Kafka.
- **Data loss?** No.  Kafka retains all messages.

**Recovery:** Restart Spark.  It reads its checkpoint to find the last
committed Kafka offsets and resumes processing from exactly that point.
All messages that arrived during the downtime are processed.

```
Before crash:  committed offset = 5000
Kafka current offset after downtime = 8000

On restart:
  Spark reads checkpoint → resume from offset 5000
  Processes offsets 5000 → 8000 (the backlog)
  Catches up to real-time
```

### Scenario 3: Network Failures

```
Producer ──╳── Kafka    (producer can't reach Kafka)
Kafka ──╳── Spark       (Spark can't reach Kafka)
```

**What happens:**
- Both producer and Spark retry automatically.
- Producer buffers events in memory during the outage.
- Spark micro-batches stall until the connection is restored.

**Recovery:** Fully automatic once the network is restored.

### Scenario 4: Delta Lake Write Failure

```
Spark processes batch → writes to Delta → [disk full / permission error]
```

**What happens:**
- The micro-batch fails.
- The checkpoint is NOT committed (because the write failed).
- Spark retries the batch from the same offsets.

**Recovery:** Fix the disk issue, restart Spark.  It re-reads the last
committed checkpoint and re-processes the failed batch.

### Summary: Failure Recovery Matrix

| Component | Fails | Data Lost? | Recovery |
|-----------|-------|-----------|----------|
| Kafka broker | Crashes, restarts | No (data on disk + replication) | Automatic reconnect |
| Producer | Crashes | Minimal (in-flight buffer only) | Restart producer; Kafka retains data |
| Spark driver | Crashes | No (Kafka retains data) | Restart Spark; resumes from checkpoint |
| Network | Temporary outage | No (buffers + retries) | Automatic on reconnect |
| Delta write | Fails (disk, perms) | No (batch retried) | Fix issue, restart Spark |

---

## 13. Scaling the Pipeline

### Current Setup: Local Single-Machine

```
┌──────────────────────────────────────┐
│            macOS Laptop              │
│                                      │
│  ┌────────┐  ┌──────┐  ┌─────────┐  │
│  │ Kafka  │  │Spark │  │ Delta   │  │
│  │(Docker)│  │local │  │ (local  │  │
│  │1 broker│  │[*]   │  │  disk)  │  │
│  └────────┘  └──────┘  └─────────┘  │
└──────────────────────────────────────┘
```

### Scaling Kafka

| Dimension | How to Scale | Effect |
|-----------|-------------|--------|
| **More partitions** | `--partitions 12` (or higher) | More parallel consumers; higher throughput |
| **More brokers** | Add broker nodes to the cluster | Partitions spread across brokers; fault tolerance with replication |
| **Higher replication** | `--replication-factor 3` | Survives broker failures without data loss |
| **Larger retention** | `KAFKA_LOG_RETENTION_HOURS=168` (7 days) | More replay window; more disk needed |

```
Production Kafka cluster:

  Broker 1 ─┐
  Broker 2 ─┤── topic: wikimedia-events (12 partitions, RF=3)
  Broker 3 ─┤   Each partition replicated across 3 brokers
  Broker 4 ─┤
  Broker 5 ─┘
```

### Scaling Spark

| Dimension | How to Scale | Effect |
|-----------|-------------|--------|
| **More executors** | `--num-executors 10` on YARN/K8s | More parallel tasks; processes larger batches faster |
| **More cores per executor** | `--executor-cores 4` | Each executor handles more partitions concurrently |
| **More memory** | `--executor-memory 8g` | Can hold more state in memory (larger windows, more keys) |
| **More shuffle partitions** | `spark.sql.shuffle.partitions=50` | Better parallelism for aggregations |

```
Production Spark cluster (YARN or Kubernetes):

  Driver ──────┬── Executor 1 (4 cores, 8 GB)  ──► reads Kafka partitions 0-2
               ├── Executor 2 (4 cores, 8 GB)  ──► reads Kafka partitions 3-5
               ├── Executor 3 (4 cores, 8 GB)  ──► reads Kafka partitions 6-8
               └── Executor 4 (4 cores, 8 GB)  ──► reads Kafka partitions 9-11
```

**Rule of thumb:** Number of Kafka partitions should be ≥ number of Spark
executor cores for optimal parallelism.

### Scaling Delta Lake

| Dimension | How to Scale | Effect |
|-----------|-------------|--------|
| **Cloud storage** | Write to S3/GCS/ADLS instead of local disk | Unlimited storage, multi-reader access |
| **OPTIMIZE** | Periodically compact small files into larger ones | Faster reads (fewer files to scan) |
| **Z-ORDER** | Cluster data by frequently queried columns | Faster filter queries (data skipping) |
| **Partitioning** | `PARTITIONED BY (date)` | Reduces scan scope for time-filtered queries |

### Fully Scaled Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                    Production Architecture                     │
│                                                               │
│  Wikimedia SSE ──► Producer (3 instances, load-balanced)      │
│       │                                                       │
│       ▼                                                       │
│  Kafka Cluster (5 brokers, 12 partitions, RF=3)              │
│       │                                                       │
│       ▼                                                       │
│  Spark on Kubernetes (4 executors × 4 cores = 16 tasks)      │
│       │                                                       │
│       ▼                                                       │
│  Delta Lake on S3 (auto-compaction, partitioned by date)     │
│       │                                                       │
│       ▼                                                       │
│  Dashboard (Grafana / Superset querying Delta via Spark SQL) │
└───────────────────────────────────────────────────────────────┘
```

---

## 14. Real-World Production Systems

### How Companies Use Similar Architectures

| Company | Pipeline | Scale |
|---------|----------|-------|
| **Uber** | Trip events → Kafka → Flink/Spark → HDFS/Hudi | Trillions of events/day; real-time pricing, ETA, fraud detection |
| **Netflix** | Playback events → Kafka → Flink → Iceberg/S3 | Billions of events/day; real-time recommendations, A/B testing |
| **LinkedIn** | User activity → Kafka → Samza/Spark → HDFS | Kafka was invented at LinkedIn; powers notifications, feed ranking |
| **Spotify** | Listening events → Kafka → Flink → BigQuery | Real-time "what's trending", personalisation |
| **Airbnb** | Booking/search events → Kafka → Spark → Hudi/Delta | Real-time pricing, search ranking |

### Comparison: Spark Streaming vs Flink vs Kafka Streams

| Feature | Spark Structured Streaming | Apache Flink | Kafka Streams |
|---------|---------------------------|-------------|--------------|
| **Processing model** | Micro-batch (default) or continuous | True event-at-a-time streaming | True event-at-a-time streaming |
| **Latency** | 100ms–few seconds | Milliseconds | Milliseconds |
| **State management** | State store (HDFS or RocksDB) | Built-in RocksDB, checkpointed to distributed FS | Built-in RocksDB, changelog topics in Kafka |
| **Exactly-once** | ✅ Via checkpointing + transactional sinks | ✅ Via checkpointing + two-phase commit | ✅ Via Kafka transactions |
| **SQL support** | ✅ Full Spark SQL / DataFrame API | ✅ Flink SQL | ❌ No SQL (Java/Scala DSL only) |
| **Deployment** | YARN, K8s, standalone, local | YARN, K8s, standalone | Embedded in any JVM application (no cluster needed) |
| **Batch + streaming** | ✅ Unified API | ✅ Unified API | ❌ Streaming only |
| **Community** | Massive (Spark ecosystem) | Growing rapidly | Medium (Kafka ecosystem) |
| **Best for** | Analytics, ETL, ML pipelines | Low-latency event processing, CEP | Lightweight stream processing within Kafka ecosystem |

#### When to Choose Each

| Use Case | Best Choice | Why |
|----------|------------|-----|
| Real-time analytics dashboards | **Spark Structured Streaming** | SQL-friendly, integrates with Delta/Iceberg, good for aggregations |
| Sub-second alerting / CEP | **Apache Flink** | True event-at-a-time with lowest latency |
| Kafka topic-to-topic transformations | **Kafka Streams** | No separate cluster needed; deploys as a library |
| Unified batch + streaming ETL | **Spark** or **Flink** | Both support the same API for batch and streaming |
| Machine learning on streams | **Spark Structured Streaming** | MLlib integration, DataFrame API |

### This Project in Context

This project is architecturally similar to what Uber, Netflix, and LinkedIn
run in production — the same conceptual pipeline:

```
Event Source → Message Bus → Stream Processor → Lakehouse Storage
```

The difference is scale: this project runs on a laptop; production systems run
on thousands of machines processing billions of events per day.  But the
**concepts** — watermarking, checkpointing, exactly-once semantics, stateful
processing, windowed aggregations — are identical.

---

## 15. Key Takeaways

### The 10 Most Important Concepts

| # | Concept | One-Line Summary |
|---|---------|-----------------|
| 1 | **Kafka as a buffer** | Decouple producers from consumers with a durable, replayable log |
| 2 | **Event time over processing time** | Always use the timestamp from the source, not when Spark received the event |
| 3 | **Watermarks bound memory** | Without watermarks, streaming state grows forever and eventually OOM-crashes |
| 4 | **Sliding windows for trends** | Overlapping windows give smooth, continuously updated rankings |
| 5 | **Tumbling windows for counting** | Non-overlapping windows give independent, per-period measurements |
| 6 | **mapGroupsWithState for custom logic** | When built-in aggregations aren't enough, use custom stateful functions |
| 7 | **Checkpoints enable recovery** | Spark can restart from exactly where it left off — no data loss, no duplicates |
| 8 | **Delta Lake gives ACID** | Atomic, consistent, isolated, durable writes on top of Parquet files |
| 9 | **Exactly-once = Kafka idempotence + Spark checkpoints + Delta transactions** | Three layers working together to guarantee each event is processed once |
| 10 | **State must be bounded** | Always set timeouts on stateful operators to prevent unbounded growth |

### Mental Model for Streaming Pipelines

```
Source → Buffer → Process → Sink

  Source:  Where events originate (API, database CDC, IoT sensors)
  Buffer:  Kafka — absorbs bursts, enables replay, decouples components
  Process: Spark — windows, aggregations, state, watermarks, exactly-once
  Sink:    Delta Lake — ACID writes, schema enforcement, time travel
```

Every production streaming system follows this pattern.  The specific
technologies vary, but the concepts are universal:

- Decouple with a message bus
- Process with event-time semantics
- Bound state with watermarks
- Recover with checkpoints
- Write atomically to a transactional sink

### What to Learn Next

| Topic | Why |
|-------|-----|
| **Apache Flink** | Alternative to Spark with true per-event processing and lower latency |
| **Change Data Capture (CDC)** | Streaming database changes (Debezium) into Kafka |
| **Data lakehouse (Iceberg/Hudi)** | Alternatives to Delta Lake with broader engine support |
| **Kafka Connect** | Declarative connectors for moving data in/out of Kafka without code |
| **Stream processing patterns** | Event sourcing, CQRS, saga pattern, exactly-once joins |
| **Observability** | Monitoring streaming lag, throughput, state size, and backpressure |

---

*This document accompanies the [Wikimedia Streaming Analytics](README.md)
project.  See the README for setup instructions, running commands, and
querying Delta tables.*

