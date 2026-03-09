#!/usr/bin/env bash
# =============================================================================
# cleanup.sh — Remove pipeline state and/or rebuild from scratch
#
# Usage:
#   ./cleanup.sh                 # Remove checkpoints + delta tables only
#   ./cleanup.sh --full          # Also remove Kafka data, build artifacts, logs
#   ./cleanup.sh --rebuild       # Clean + rebuild the fat JAR
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DOCKER_DIR="$PROJECT_ROOT/docker"

MODE="${1:-state}"   # default: just state
REBUILD=false

case "${1:-}" in
  --full)   MODE="full" ;;
  --rebuild) MODE="state"; REBUILD=true ;;
  *)        MODE="state" ;;
esac

echo "══════════════════════════════════════════════════════════════"
echo "  Cleanup (mode: $MODE)"
echo "══════════════════════════════════════════════════════════════"
echo ""

# --- Always: Remove checkpoints + delta tables ------------------------------
echo "▶ Removing Spark checkpoints..."
if [ -d "$PROJECT_ROOT/checkpoint" ]; then
  rm -rf "$PROJECT_ROOT/checkpoint"
  echo "  ✅ checkpoint/ removed"
else
  echo "  (not present)"
fi

echo "▶ Removing Delta Lake tables..."
if [ -d "$PROJECT_ROOT/delta" ]; then
  rm -rf "$PROJECT_ROOT/delta"
  echo "  ✅ delta/ removed"
else
  echo "  (not present)"
fi
echo ""

# --- Full mode: also clean build, Kafka data, logs -------------------------
if [ "$MODE" = "full" ]; then
  echo "▶ Removing Maven build artifacts..."
  if [ -d "$PROJECT_ROOT/target" ]; then
    rm -rf "$PROJECT_ROOT/target"
    echo "  ✅ target/ removed"
  else
    echo "  (not present)"
  fi

  echo "▶ Removing Kafka data volume..."
  cd "$DOCKER_DIR"
  if docker volume ls -q 2>/dev/null | grep -q "docker_kafka-data"; then
    docker volume rm docker_kafka-data 2>/dev/null || true
    echo "  ✅ Kafka volume removed"
  else
    echo "  (not present)"
  fi

  echo "▶ Removing producer log..."
  rm -f /tmp/wikimedia-producer.log /tmp/wikimedia-producer.pid
  echo "  ✅ Log files removed"

  echo ""
  REBUILD=true
fi

# --- Rebuild if requested ---------------------------------------------------
if [ "$REBUILD" = true ]; then
  echo "▶ Rebuilding fat JAR..."
  cd "$PROJECT_ROOT"
  mvn clean package -DskipTests -q
  echo "  ✅ Build complete: target/wikimedia-streaming-analytics-1.0.0.jar"
fi

echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  Cleanup complete"
echo "══════════════════════════════════════════════════════════════"
echo ""
echo "  Next steps:"
echo "    ./scripts/start-all.sh      # start the full pipeline"
echo "    ./scripts/start-kafka.sh    # start Kafka only"
echo "    ./scripts/start-streaming.sh # start Spark only"

