#!/usr/bin/env bash
# =============================================================================
# start-all.sh — One-command startup: Kafka → Producer → Spark Streaming
#
# Starts Kafka and the producer in the background, then launches the Spark
# streaming job in the foreground.  Ctrl+C stops Spark; use stop-all.sh to
# tear down everything.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "══════════════════════════════════════════════════════════════"
echo "  Starting Full Pipeline"
echo "══════════════════════════════════════════════════════════════"
echo ""

# --- Step 1: Kafka ----------------------------------------------------------
echo "▶ Step 1/3: Kafka"
"$SCRIPT_DIR/start-kafka.sh"
echo ""

# --- Step 2: Producer (background) -----------------------------------------
echo "▶ Step 2/3: Kafka Producer (background)"
echo "   Logs → /tmp/wikimedia-producer.log"
nohup "$SCRIPT_DIR/start-producer.sh" > /tmp/wikimedia-producer.log 2>&1 &
PRODUCER_PID=$!
echo "   PID: $PRODUCER_PID"
# Save PID for stop-all.sh
echo "$PRODUCER_PID" > /tmp/wikimedia-producer.pid
sleep 3
echo ""

# --- Step 3: Spark Streaming (foreground) -----------------------------------
echo "▶ Step 3/3: Spark Streaming (foreground)"
echo "   Press Ctrl+C to stop the Spark job."
echo ""
"$SCRIPT_DIR/start-streaming.sh"

