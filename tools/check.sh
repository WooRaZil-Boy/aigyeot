#!/usr/bin/env bash
# 구현 계획 7장 ①·② 시험을 한 번에 돌린다. 저장소 루트에서 ./tools/check.sh
# 사용법: iOS 시험을 돌릴 시뮬레이터를 정하려면 IOS_SIM=<시뮬레이터 id> ./tools/check.sh (없으면 켜진 iPhone, 그다음 가장 새 런타임의 iPhone)
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

./gradlew --console=plain :engine:test :shared:test :app:testDebugUnitTest :phone:testDebugUnitTest
if [ -f relay/package.json ]; then
  (cd relay && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && npm run typecheck && npm test)
fi
if [ -d phone/ios ] && command -v xcodebuild >/dev/null; then
  # 켜져 있는 iPhone을 먼저, 없으면 available에서 가장 새 런타임의 iPhone을 고른다(목록이 런타임 오름차순이라 마지막 것).
  # 이름이 iPhone으로 시작하는 기기만. 옛 런타임은 지금 Xcode가 못 쓸 수 있다(11-05: iOS 27.0 기기를 골라 xcodebuild가 실패).
  iphones='s/^ +iPhone[^(]*\(([0-9A-Fa-f-]{36})\).*/\1/p'
  sim="${IOS_SIM:-}"
  [ -n "$sim" ] || sim=$(xcrun simctl list devices booted | sed -nE "$iphones" | sed -n '1p')
  [ -n "$sim" ] || sim=$(xcrun simctl list devices available | sed -nE "$iphones" | sed -n '$p')
  if [ -z "$sim" ]; then echo "check: 실패 — 쓸 수 있는 iPhone 시뮬레이터가 없습니다(xcrun simctl list devices available)" >&2; exit 1; fi
  xcodebuild test -project phone/ios/Aigyeot.xcodeproj -scheme Aigyeot \
    -destination "platform=iOS Simulator,id=$sim" -quiet
fi
echo "check: 통과"
