#!/usr/bin/env bash
# 시연 전 한 번. 사용법: prepare.sh [APK경로]   (SERIAL=emulator-5560 환경 변수로 기기 변경)
# 기본 APK는 저장소의 debug 산출물이다.
set -u
ARG="${1:-}"; HERE="$(cd "$(dirname "$0")" && pwd)"   # cd 전에 인자 경로를 잡는다
cd "$HERE" && . ./vc.sh
[ -n "$ARG" ] && ARG="$(cd "$OLDPWD" && abspath "$ARG")"
ROOT="$(cd ../.. && pwd)"
APK="${ARG:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
[ -f "$APK" ] || die "APK가 없다: $APK (./gradlew :app:assembleDebug 먼저)"

need_pleos
echo "[1/6] Pleos 에뮬레이터 확인: $SERIAL"

# 어느 사용자에 있든(user 0 포함) 지운다. adb uninstall은 모든 사용자에서 지운다.
if a shell pm list packages -u | grep -q 'package:dev.probe.pleos$'; then
  a uninstall dev.probe.pleos >/dev/null || die "프로브(dev.probe.pleos) 삭제 실패"
  a shell pm list packages -u | grep -q 'package:dev.probe.pleos$' && die "프로브가 아직 남아 있다"
  echo "[2/6] 프로브 삭제함"
else echo "[2/6] 프로브 없음"; fi

a install -r "$APK" >/dev/null || die "APK 설치 실패: $APK"
a shell pm list packages --user 10 | grep -q "package:$PKG\$" || die "$PKG 가 user 10에 설치되지 않았다"
echo "[3/6] 설치 확인(user 10)"

# 매니페스트에서 dangerous 인 것(런타임 권한)만 준다. CAR_SPEED는 프로브와 같은 방식(pm grant)으로 준다.
for p in android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS android.car.permission.CAR_SPEED; do
  a shell pm grant --user 10 "$PKG" "$p" >/dev/null 2>&1 || die "권한 부여 실패: $p"
  a shell dumpsys package "$PKG" | awk '/^ *User 10:/{u=1;next} /^ *User [0-9]+:/{u=0} u' | grep -q "$p: granted=true" \
    || die "권한이 user 10에서 granted=true가 아니다: $p"
done
echo "[4/6] 권한 부여(RECORD_AUDIO, POST_NOTIFICATIONS, CAR_SPEED)"

vc_start
echo "[5/6] forward $VC_PORT, VC 세션 신호 유지 시작(pid $(cat "$VC_PID"))"

set_baseline
echo "[6/6] 기준값: 기어 P, 정지, 뒷문 닫힘, 공조 전원 켬, 22도"
app_front
echo "완료. 앱을 띄웠다."
