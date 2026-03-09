package com.wikimedia.streaming.producer

import com.wikimedia.streaming.utils.KafkaConfig
import okhttp3.{OkHttpClient, Request, Response}
import okhttp3.sse.{EventSource, EventSourceListener, EventSources}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}

import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import scala.util.{Failure, Try}

/**
 * WikimediaKafkaProducer — consumes Wikimedia recent-change events via SSE
 * and publishes them to a Kafka topic.
 *
 * === Design decisions ===
 *
 * **OkHttp SSE** is used instead of a raw HTTP connection because:
 *  - `okhttp-sse` handles SSE framing (event/data/id/retry fields) automatically.
 *  - OkHttp's connection pool and dispatcher manage reconnects transparently.
 *  - It interoperates seamlessly with OkHttp's interceptors for logging/auth.
 *
 * **Kafka producer settings**:
 *  - `acks=all` + `enable.idempotence=true` — every message is acknowledged by
 *    all in-sync replicas and the broker deduplicates retries, giving
 *    exactly-once delivery semantics at the producer side.
 *  - `linger.ms=20` — batches messages for up to 20 ms before sending,
 *    increasing throughput without meaningful latency cost for a streaming feed.
 *  - `compression.type=snappy` — Snappy compresses well and is CPU-efficient;
 *    reduces network bandwidth without noticeable latency overhead.
 *  - `retries=5` — automatically retries transient send failures.
 *
 * **Thread model**: OkHttp runs the SSE listener on its own dispatcher thread.
 * The main thread is kept alive with `Thread.currentThread().join()`.
 * A shutdown hook flushes/closes the producer and shuts down the OkHttp
 * dispatcher to release resources cleanly on SIGINT / SIGTERM.
 */
object WikimediaKafkaProducer {

  def main(args: Array[String]): Unit = {

    // -------------------------------------------------------------------------
    // Kafka producer
    // -------------------------------------------------------------------------
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,       KafkaConfig.bootstrapServers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringSerializer")
    // Acknowledge from all in-sync replicas for durability
    props.put(ProducerConfig.ACKS_CONFIG,                    "all")
    // Idempotence prevents duplicate messages on retry
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,      "true")
    // Batch for up to 20 ms to increase throughput
    props.put(ProducerConfig.LINGER_MS_CONFIG,               "20")
    // Snappy is fast and produces decent compression ratios
    props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,        "snappy")
    // Retry up to 5 times on transient network errors
    props.put(ProducerConfig.RETRIES_CONFIG,                 "5")

    val producer = new KafkaProducer[String, String](props)

    // Counter for progress logging — thread-safe because the SSE listener
    // callback runs on an OkHttp dispatcher thread.
    val eventCounter = new AtomicLong(0L)

    // -------------------------------------------------------------------------
    // OkHttp SSE client
    // -------------------------------------------------------------------------
    val client = new OkHttpClient.Builder().build()

    val request = new Request.Builder()
      .url(KafkaConfig.wikimediaStreamUrl)

      .header("User-Agent", "WikimediaStreamingAnalytics/1.0 (https://github.com/rajeshsantha/wikimedia-streaming-analytics)")
      .build()

    val listener = new EventSourceListener {

      /** Called once when the SSE connection is established. */
      override def onOpen(eventSource: EventSource, response: Response): Unit =
        println(s"[WikimediaProducer] Connected to ${KafkaConfig.wikimediaStreamUrl}")

      /**
       * Called for every SSE event received from the server.
       *
       * Each `data` payload is a raw JSON string representing one
       * Wikimedia recent-change event.  We publish it verbatim to Kafka;
       * schema parsing happens on the Spark consumer side.
       */
      override def onEvent(
          eventSource: EventSource,
          id:          String,
          `type`:      String,
          data:        String
      ): Unit = {
        // Skip comment / heartbeat lines (empty data)
        if (data == null || data.trim.isEmpty) return

        val record = new ProducerRecord[String, String](KafkaConfig.topic, data)

        // send() is non-blocking; the callback logs any errors asynchronously
        producer.send(record, (_, exception) => {
          if (exception != null)
            System.err.println(s"[WikimediaProducer] Send error: ${exception.getMessage}")
        })

        // Log progress every 100 events so the operator knows data is flowing
        val count = eventCounter.incrementAndGet()
        if (count % 100 == 0)
          println(s"[WikimediaProducer] Published $count events to topic '${KafkaConfig.topic}'")
      }

      /**
       * Called when the server closes the connection.
       * OkHttp's SSE implementation reconnects automatically using the
       * retry interval specified in the SSE stream (or a default back-off).
       */
      override def onClosed(eventSource: EventSource): Unit =
        println("[WikimediaProducer] SSE connection closed — OkHttp will reconnect")

      /**
       * Called on network errors or non-2xx HTTP responses.
       * We log the HTTP status code (if available) to aid debugging, then
       * OkHttp backs off and retries the connection.
       */
      override def onFailure(
          eventSource: EventSource,
          t:           Throwable,
          response:    Response
      ): Unit = {
        val status = Option(response).map(r => s" (HTTP ${r.code})").getOrElse("")
        System.err.println(
          s"[WikimediaProducer] SSE failure$status: ${Option(t).map(_.getMessage).getOrElse("unknown")}"
        )
      }
    }

    // Open the SSE connection — runs asynchronously on OkHttp's dispatcher
    EventSources.createFactory(client).newEventSource(request, listener)
    println(s"[WikimediaProducer] Streaming from ${KafkaConfig.wikimediaStreamUrl} → topic '${KafkaConfig.topic}'")

    // -------------------------------------------------------------------------
    // Graceful shutdown hook — flushes the Kafka producer and releases OkHttp
    // resources when the JVM receives SIGINT or SIGTERM.
    // -------------------------------------------------------------------------
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      println(s"\n[WikimediaProducer] Shutting down — total events published: ${eventCounter.get()}")
      Try(producer.flush())  match {
        case Failure(e) => System.err.println(s"[WikimediaProducer] Flush error: ${e.getMessage}")
        case _ =>
      }
      producer.close()
      client.dispatcher.executorService.shutdown()
      println("[WikimediaProducer] Shutdown complete")
    }))

    // Block the main thread indefinitely — actual work runs on OkHttp threads
    Thread.currentThread().join()
  }
}
