#!/usr/bin/env bash
# =============================================================================
# stop-all.sh — Gracefully stop all running pipeline components
#
# Stops (in order):
#   1. Spark streaming job (if running)
#   2. Kafka producer process (if running)
#   3. Kafka Docker container (keeps data volume by default)
#
# Usage:
#   ./stop-all.sh           # stop everything, keep Kafka data
#   ./stop-all.sh --rm      # stop everything + remove Kafka data volume
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DOCKER_DIR="$PROJECT_ROOT/docker"
REMOVE_VOLUME=false

if [[ "${1:-}" == "--rm" ]]; then
  REMOVE_VOLUME=true
fi

echo "══════════════════════════════════════════════════════════════"
echo "  Stopping Pipeline"
echo "══════════════════════════════════════════════════════════════"
echo ""

# --- Stop Spark Streaming ---------------------------------------------------
echo "▶ Stopping Spark streaming jobs..."
SPARK_PIDS=$(pgrep -f "StreamingApp" 2>/dev/null || true)
if [ -n "$SPARK_PIDS" ]; then
  echo "  Sending SIGTERM to Spark PIDs: $SPARK_PIDS"
  kill $SPARK_PIDS 2>/dev/null || true
  sleep 2
  # Force-kill if still alive
  for PID in $SPARK_PIDS; do
    if kill -0 "$PID" 2>/dev/null; then
      echo "  Force-killing PID $PID"
      kill -9 "$PID" 2>/dev/null || true
    fi
  done
  echo "  ✅ Spark stopped"
else
  echo "  (not running)"
fi
echo ""

# --- Stop Kafka Producer ----------------------------------------------------
echo "▶ Stopping Kafka producer..."
# Try PID file first
if [ -f /tmp/wikimedia-producer.pid ]; then
  PRODUCER_PID=$(cat /tmp/wikimedia-producer.pid)
  if kill -0 "$PRODUCER_PID" 2>/dev/null; then
    kill "$PRODUCER_PID" 2>/dev/null || true
    echo "  ✅ Producer (PID $PRODUCER_PID) stopped"
  else
    echo "  (PID $PRODUCER_PID no longer running)"
  fi
  rm -f /tmp/wikimedia-producer.pid
else
  # Fallback: find by class name
  PRODUCER_PIDS=$(pgrep -f "WikimediaKafkaProducer" 2>/dev/null || true)
  if [ -n "$PRODUCER_PIDS" ]; then
    kill $PRODUCER_PIDS 2>/dev/null || true
    echo "  ✅ Producer stopped (PIDs: $PRODUCER_PIDS)"
  else
    echo "  (not running)"
  fi
fi
echo ""

# --- Stop Kafka -------------------------------------------------------------
echo "▶ Stopping Kafka container..."
cd "$DOCKER_DIR"
if docker ps -q -f name=kafka 2>/dev/null | grep -q .; then
  if [ "$REMOVE_VOLUME" = true ]; then
    docker compose down -v
    echo "  ✅ Kafka stopped + data volume removed"
  else
    docker compose down
    echo "  ✅ Kafka stopped (data volume preserved)"
  fi
else
  echo "  (not running)"
fi

echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  All components stopped"
if [ "$REMOVE_VOLUME" = false ]; then
  echo "  Tip: use ./stop-all.sh --rm to also remove Kafka data"
fi
echo "══════════════════════════════════════════════════════════════"

