#!/usr/bin/env bash
# =============================================================================
# build.sh — Clean build of the fat JAR
# =============================================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
echo "Building Project..."
cd "$PROJECT_ROOT"
mvn clean package -DskipTests
JAR="$PROJECT_ROOT/target/wikimedia-streaming-analytics-1.0.0.jar"
if [ -f "$JAR" ]; then
JAR_SIZE=$(du -h "$JAR" | cut -f1)
echo "Built: target/wikimedia-streaming-analytics-1.0.0.jar ($JAR_SIZE)"
else
echo "Build failed"
exit 1
fi
