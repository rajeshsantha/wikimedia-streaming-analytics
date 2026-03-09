#!/usr/bin/env bash
# =============================================================================
# start-streaming.sh — Start the Spark Structured Streaming application
#
# Launches all 3 streaming queries (TrendingArticles, ActiveEditors,
# EditSpikeDetector) via spark-submit.  Runs in the foreground.
# Press Ctrl+C to stop all queries gracefully.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
JAR="$PROJECT_ROOT/target/wikimedia-streaming-analytics-1.0.0.jar"
DELTA_PKG="io.delta:delta-spark_2.13:4.1.0"

echo "══════════════════════════════════════════════════════════════"
echo "  Starting Spark Structured Streaming"
echo "══════════════════════════════════════════════════════════════"

# --- Build if JAR is missing ------------------------------------------------
if [ ! -f "$JAR" ]; then
  echo "⚠️  Fat JAR not found — building project first..."
  cd "$PROJECT_ROOT"
  mvn clean package -DskipTests -q
  echo "✅ Build complete"
fi

# --- Pre-flight checks ------------------------------------------------------
if ! command -v spark-submit &>/dev/null; then
  echo "❌ spark-submit not found. Install Apache Spark 4.1.x first."
  exit 1
fi

# Check Kafka is reachable
if ! docker inspect --format='{{.State.Health.Status}}' kafka 2>/dev/null | grep -q healthy; then
  echo "⚠️  Kafka container is not healthy. Run ./start-kafka.sh first."
fi

# --- Launch Spark -----------------------------------------------------------
echo "🚀 Submitting StreamingApp to Spark..."
echo "   3 queries: trending_articles, active_editors, edit_spike_detector"
echo "   Delta tables → $PROJECT_ROOT/delta/"
echo "   Checkpoints  → $PROJECT_ROOT/checkpoint/"
echo "   Spark UI     → http://localhost:4040"
echo "   Press Ctrl+C to stop."
echo ""

cd "$PROJECT_ROOT"
spark-submit \
  --class com.wikimedia.streaming.streaming.StreamingApp \
  --master "local[*]" \
  --packages "$DELTA_PKG" \
  "$JAR"

