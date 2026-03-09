#!/usr/bin/env bash
# =============================================================================
# status.sh — Show the status of all pipeline components
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "══════════════════════════════════════════════════════════════"
echo "  Pipeline Status"
echo "══════════════════════════════════════════════════════════════"
echo ""

# --- Kafka ------------------------------------------------------------------
printf "  %-22s" "Kafka:"
KAFKA_STATUS=$(docker inspect --format='{{.State.Health.Status}}' kafka 2>/dev/null || echo "not running")
if [ "$KAFKA_STATUS" = "healthy" ]; then
  echo "✅ healthy (localhost:9092)"
elif [ "$KAFKA_STATUS" = "not running" ]; then
  echo "❌ not running"
else
  echo "⚠️  $KAFKA_STATUS"
fi

# --- Producer ---------------------------------------------------------------
printf "  %-22s" "Kafka Producer:"
PRODUCER_PIDS=$(pgrep -f "WikimediaKafkaProducer" 2>/dev/null || true)
if [ -n "$PRODUCER_PIDS" ]; then
  echo "✅ running (PID: $PRODUCER_PIDS)"
else
  echo "❌ not running"
fi

# --- Spark Streaming --------------------------------------------------------
printf "  %-22s" "Spark Streaming:"
SPARK_PIDS=$(pgrep -f "StreamingApp" 2>/dev/null || true)
if [ -n "$SPARK_PIDS" ]; then
  echo "✅ running (PID: $SPARK_PIDS) — UI: http://localhost:4040"
else
  echo "❌ not running"
fi

echo ""

# --- Delta Tables -----------------------------------------------------------
echo "  Delta Lake Tables:"
for TABLE in trending_articles active_editors edit_spikes; do
  TABLE_DIR="$PROJECT_ROOT/delta/$TABLE"
  printf "    %-24s" "$TABLE:"
  if [ -d "$TABLE_DIR" ]; then
    FILE_COUNT=$(find "$TABLE_DIR" -name "*.parquet" 2>/dev/null | wc -l | tr -d ' ')
    TOTAL_SIZE=$(du -sh "$TABLE_DIR" 2>/dev/null | cut -f1)
    echo "✅ ${FILE_COUNT} parquet files (${TOTAL_SIZE})"
  else
    echo "—  (not created yet)"
  fi
done

echo ""

# --- Checkpoints ------------------------------------------------------------
echo "  Checkpoints:"
for CP in trending_articles active_editors edit_spikes; do
  CP_DIR="$PROJECT_ROOT/checkpoint/$CP"
  printf "    %-24s" "$CP:"
  if [ -d "$CP_DIR" ]; then
    TOTAL_SIZE=$(du -sh "$CP_DIR" 2>/dev/null | cut -f1)
    echo "✅ present (${TOTAL_SIZE})"
  else
    echo "—  (not created yet)"
  fi
done

echo ""

# --- Build ------------------------------------------------------------------
printf "  %-22s" "Fat JAR:"
JAR="$PROJECT_ROOT/target/wikimedia-streaming-analytics-1.0.0.jar"
if [ -f "$JAR" ]; then
  JAR_SIZE=$(du -h "$JAR" | cut -f1)
  echo "✅ built ($JAR_SIZE)"
else
  echo "❌ not built — run: mvn clean package -DskipTests"
fi

echo ""
echo "══════════════════════════════════════════════════════════════"

