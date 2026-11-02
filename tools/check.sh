#!/usr/bin/env bash
# 구현 계획 7장 ①·② 시험을 한 번에 돌린다. 저장소 루트에서 ./tools/check.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

./gradlew --console=plain :engine:test :shared:test :app:testDebugUnitTest :phone:testDebugUnitTest
if [ -f relay/package.json ]; then (cd relay && npm test); fi
echo "check: 통과"
