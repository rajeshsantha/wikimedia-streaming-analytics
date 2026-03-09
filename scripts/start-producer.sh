#!/usr/bin/env bash
# =============================================================================
# start-producer.sh — Start the Wikimedia SSE → Kafka producer
#
# Runs in the foreground so you can see live event counts.
# Press Ctrl+C to stop gracefully (flushes buffered events to Kafka).
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
JAR="$PROJECT_ROOT/target/wikimedia-streaming-analytics-1.0.0.jar"

echo "══════════════════════════════════════════════════════════════"
echo "  Starting Wikimedia Kafka Producer"
echo "══════════════════════════════════════════════════════════════"

# --- Build if JAR is missing ------------------------------------------------
if [ ! -f "$JAR" ]; then
  echo "⚠️  Fat JAR not found — building project first..."
  cd "$PROJECT_ROOT"
  mvn clean package -DskipTests -q
  echo "✅ Build complete"
fi

# --- Launch producer --------------------------------------------------------
echo "🚀 Connecting to Wikimedia EventStreams and publishing to Kafka..."
echo "   Press Ctrl+C to stop."
echo ""

cd "$PROJECT_ROOT"
mvn -q exec:java -Dexec.mainClass="com.wikimedia.streaming.producer.WikimediaKafkaProducer"

