#!/usr/bin/env bash
# 시연 전 한 번. 사용법: prepare.sh [APK경로]   (SERIAL=emulator-5560 환경 변수로 기기 변경)
# 인자가 없으면 저장소의 debug 산출물을 설치한다. 단계마다 [n/8] 줄을 찍고, 실패하면 이유를 찍고 멈춘다.
set -u
ARG="${1:-}"; HERE="$(cd "$(dirname "$0")" && pwd)"   # cd 전에 인자 경로를 잡는다
cd "$HERE" && . ./vc.sh
[ -n "$ARG" ] && ARG="$(cd "$OLDPWD" && abspath "$ARG")"
ROOT="$(cd ../.. && pwd)"
APK="${ARG:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
[ -f "$APK" ] || die "APK가 없다: $APK (./gradlew :app:assembleDebug 먼저)"

need_pleos
# 부팅 직후(adb에 device로 뜬 뒤에도 패키지 서비스·운전자 사용자가 아직 없다)에 돌려도 설치 전에 멈추지 않게 기다린다.
boot_ready() { [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && a shell pm path android >/dev/null 2>&1 \
  && [ "$(a shell am get-current-user 2>/dev/null | tr -d '\r')" = 10 ] && a shell pm list packages --user 10 >/dev/null 2>&1; }
if ! boot_ready; then
  echo "  에뮬레이터 부팅이 끝나기를 기다린다(최대 5분)"
  end=$((SECONDS + 300))
  until boot_ready; do [ "$SECONDS" -lt "$end" ] || die "5분이 지나도 부팅이 끝나지 않았다: $SERIAL"; sleep 2; done
fi
echo "[1/8] Pleos 에뮬레이터 확인: $SERIAL"
# 갓 만든 AVD는 첫 부팅에서 -timezone 인자를 줘도 부팅 약 20초 뒤 위치 기반 자동 시간대가 America/Los_Angeles로 되돌린다(실측). 자동을 끄고 맞춘다.
set_seoul() {
  a shell settings put global auto_time_zone 0 >/dev/null 2>&1
  a shell cmd alarm set-timezone Asia/Seoul >/dev/null 2>&1
  [ "$(a shell getprop persist.sys.timezone 2>/dev/null | tr -d '\r')" = Asia/Seoul ]
}
set_seoul || echo "  시간대를 Asia/Seoul로 바꾸지 못했다(차 시계가 한국 시간이 아닐 수 있다)"

# 실측 앱이 있으면 어느 사용자에 있든(user 0 포함) 지우고, 모든 사용자 목록에서 없어졌는지 확인한다.
has_probe() { local l; l="$(dev_read "pm list packages -u")" || die "설치된 앱 목록을 읽지 못했다(adb 연결 확인)"; printf '%s\n' "$l" | grep -q '^package:dev.probe.pleos$'; }
if has_probe; then
  a uninstall dev.probe.pleos >/dev/null || die "프로브(dev.probe.pleos) 삭제 실패"
  has_probe && die "프로브가 아직 남아 있다"
  echo "[2/8] 프로브 삭제함"
else echo "[2/8] 프로브 없음"; fi

ADB_TIMEOUT=300 a install -r "$APK" >/dev/null || die "APK 설치 실패: $APK"   # 큰 APK라 시간 제한을 늘린다
l="$(dev_read "pm list packages --user 10")" || die "설치된 앱 목록을 읽지 못했다(adb 연결 확인)"
printf '%s\n' "$l" | grep -q "^package:$PKG\$" || die "$PKG 가 user 10에 설치되지 않았다"
info="$(dev_read "dumpsys package $PKG")" || die "앱 정보를 읽지 못했다(adb 연결 확인)"
KIND=release; printf '%s\n' "$info" | grep -q 'DEBUGGABLE' && KIND=debug
echo "[3/8] 설치 확인(user 10): $KIND 빌드, $APK"

# 매니페스트에서 dangerous 인 것(런타임 권한)만 준다. CAR_SPEED는 프로브와 같은 방식(pm grant)으로 준다.
for p in android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS android.car.permission.CAR_SPEED; do
  a shell pm grant --user 10 "$PKG" "$p" >/dev/null 2>&1 || die "권한 부여 실패: $p"
  info="$(dev_read "dumpsys package $PKG")" || die "앱 정보를 읽지 못했다(adb 연결 확인)"
  printf '%s\n' "$info" | awk '/^ *User 10:/{u=1;next} /^ *User [0-9]+:/{u=0} u' | grep -q "$p: granted=true" \
    || die "권한이 user 10에서 granted=true가 아니다: $p"
done
echo "[4/8] 권한 부여(RECORD_AUDIO, POST_NOTIFICATIONS, CAR_SPEED)"

vc_start
echo "[5/8] forward $VC_PORT, VC 세션 신호 유지 시작(pid $(cat "$VC_PID"))"

set_baseline
echo "[6/8] 기준값: 기어 P, 정지, 뒷문 닫힘, 공조 전원 켬, 22도"
ambient_on   # 차량 설정 '무드 조명' 켜기(꺼져 있으면 앱이 조명 낮춤을 건너뛴다). 실패하면 ambient_switch가 이유를 찍고 멈춘다
ambient_level 70 ffffff   # 기준값 요청이 VC 기본 밝기 100을 다시 내므로 그 뒤에 70·흰색으로
echo "[7/8] 무드 조명 켜기, 밝기 70·흰색"
set_seoul || echo "  경고: 시간대가 Asia/Seoul이 아니다(차 시계가 한국 시간이 아닐 수 있다)"
app_front
echo "[8/8] 앱을 띄웠다."
