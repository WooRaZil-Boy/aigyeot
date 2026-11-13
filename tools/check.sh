#!/usr/bin/env bash
# 시험을 한 번에 돌린다(engine·shared·app·phone JVM, 시연 스크립트, relay, iOS). 저장소 루트에서 ./tools/check.sh
# iOS 시뮬레이터: IOS_SIM=<시뮬레이터 id> ./tools/check.sh. 없으면 켜진 iPhone, 그다음 available 목록의 마지막 iPhone을 쓴다.
# 건너뛰기: npm이 없으면 중계 서버, Xcode(xcodebuild·simctl)를 쓸 수 없으면 iOS를 건너뛰고 마지막 줄에 적는다.
# 실패로 멈춤: 시험 실패, IOS_SIM을 지정했는데 Xcode를 쓸 수 없을 때, 쓸 iPhone 시뮬레이터가 없을 때.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

skipped=()
./gradlew --console=plain :engine:test :shared:test :app:testDebugUnitTest :phone:testDebugUnitTest
python3 tools/demo/selftest.py   # 시연 스크립트가 끝나는 길마다(가짜 adb·curl, 기기 없이)
if ! command -v npm >/dev/null; then
  skipped+=("중계 서버(npm 없음)")
elif [ -f relay/package.json ]; then
  (cd relay && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && npm run typecheck && npm test)
fi
# macOS에는 Xcode가 없어도 /usr/bin/xcodebuild가 있으므로, 쓸 도구(xcodebuild·simctl)가 실제로 도는지로 본다.
if [ -d phone/ios ] && ! { xcodebuild -version >/dev/null 2>&1 && xcrun --find simctl >/dev/null 2>&1; }; then
  # IOS_SIM으로 iOS 시험을 지정했으면 건너뛰지 않고 실패로 멈춘다
  if [ -n "${IOS_SIM:-}" ]; then echo "check: 실패 — IOS_SIM을 지정했지만 Xcode(xcodebuild·simctl)를 쓸 수 없습니다(xcode-select -p 확인)" >&2; exit 1; fi
  skipped+=("iOS(Xcode 없음)")
elif [ -d phone/ios ]; then
  # 켜져 있는 iPhone을 먼저, 없으면 available 목록의 마지막 iPhone을 고른다. 이름이 iPhone으로 시작하는 기기만.
  iphones='s/^ +iPhone[^(]*\(([0-9A-Fa-f-]{36})\).*/\1/p'
  sim="${IOS_SIM:-}"
  [ -n "$sim" ] || sim=$(xcrun simctl list devices booted | sed -nE "$iphones" | sed -n '1p')
  [ -n "$sim" ] || sim=$(xcrun simctl list devices available | sed -nE "$iphones" | sed -n '$p')
  if [ -z "$sim" ]; then echo "check: 실패 — 쓸 수 있는 iPhone 시뮬레이터가 없습니다(xcrun simctl list devices available)" >&2; exit 1; fi
  xcodebuild test -project phone/ios/Aigyeot.xcodeproj -scheme Aigyeot \
    -destination "platform=iOS Simulator,id=$sim" -quiet
fi
if [ ${#skipped[@]} -gt 0 ]; then echo "check: 통과(건너뜀: ${skipped[*]})"; else echo "check: 통과"; fi
