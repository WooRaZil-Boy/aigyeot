#!/usr/bin/env bash
# 구현 계획 7장 ①·② 시험을 한 번에 돌린다. 저장소 루트에서 ./tools/check.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

./gradlew --console=plain :engine:test :shared:test :app:testDebugUnitTest :phone:testDebugUnitTest
if [ -f relay/package.json ]; then
  (cd relay && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && npm run typecheck && npm test)
fi
if [ -d phone/ios ] && command -v xcodebuild >/dev/null; then
  # 켜져 있는 iPhone을 먼저, 없으면 available의 첫 iPhone을 고른다(이름이 iPhone으로 시작하는 기기만).
  sim=$({ xcrun simctl list devices booted; xcrun simctl list devices available; } | sed -nE 's/^ +iPhone[^(]*\(([0-9A-Fa-f-]{36})\).*/\1/p' | sed -n '1p')
  if [ -z "$sim" ]; then echo "check: 실패 — 쓸 수 있는 iPhone 시뮬레이터가 없습니다(xcrun simctl list devices available)" >&2; exit 1; fi
  xcodebuild test -project phone/ios/Aigyeot.xcodeproj -scheme Aigyeot \
    -destination "platform=iOS Simulator,id=$sim" -quiet
fi
echo "check: 통과"
