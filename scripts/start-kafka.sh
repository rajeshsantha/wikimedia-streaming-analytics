#!/usr/bin/env bash
# =============================================================================
# start-kafka.sh — Start Kafka (KRaft mode) and create the wikimedia-events topic
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DOCKER_DIR="$PROJECT_ROOT/docker"
TOPIC="wikimedia-events"
BOOTSTRAP="localhost:9092"

echo "══════════════════════════════════════════════════════════════"
echo "  Starting Kafka (KRaft mode, no Zookeeper)"
echo "══════════════════════════════════════════════════════════════"

# --- Start container --------------------------------------------------------
cd "$DOCKER_DIR"
docker compose up -d

# --- Wait for healthy -------------------------------------------------------
echo ""
echo "⏳ Waiting for Kafka to become healthy..."
MAX_WAIT=60
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
  STATUS=$(docker inspect --format='{{.State.Health.Status}}' kafka 2>/dev/null || echo "not-found")
  if [ "$STATUS" = "healthy" ]; then
    echo "✅ Kafka is healthy (waited ${WAITED}s)"
    break
  fi
  sleep 2
  WAITED=$((WAITED + 2))
done

if [ "$STATUS" != "healthy" ]; then
  echo "❌ Kafka did not become healthy within ${MAX_WAIT}s (status: $STATUS)"
  echo "   Check logs with: docker compose -f $DOCKER_DIR/docker-compose.yml logs kafka"
  exit 1
fi

# --- Create topic if it doesn't exist ---------------------------------------
EXISTING=$(docker exec kafka kafka-topics --list --bootstrap-server "$BOOTSTRAP" 2>/dev/null || true)
if echo "$EXISTING" | grep -qw "$TOPIC"; then
  echo "✅ Topic '$TOPIC' already exists"
else
  echo "📦 Creating topic '$TOPIC' (3 partitions, RF=1)..."
  docker exec kafka kafka-topics \
    --create \
    --topic "$TOPIC" \
    --partitions 3 \
    --replication-factor 1 \
    --bootstrap-server "$BOOTSTRAP"
  echo "✅ Topic '$TOPIC' created"
fi

echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  Kafka ready at $BOOTSTRAP"
echo "══════════════════════════════════════════════════════════════"

