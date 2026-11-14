#!/usr/bin/env bash
# 시연 스크립트 공용 함수. 다른 스크립트가 source 한다(직접 실행하지 않는다).
# set -e를 쓰지 않는다: source하는 스크립트의 셸 옵션을 바꾸게 되고, 실패해도 이어 가야 하는 호출(vc_try·정리)이 있어 함수가 die·종료 코드로 직접 판단한다.
# 환경 변수: SERIAL(기본 emulator-5560), VC_PORT(기본 8088), UI_WAIT_S(화면 글자 기다리기, 기본 10초),
#           ADB_TIMEOUT(adb 호출 하나의 시간 제한, 기본 60초), VC_PID(세션 신호 루프 pid 파일, 기본 기기·포트마다 하나)
#
# 기기·차량과 주고받는 곳의 오류 종류와 처리(모든 스크립트 공통. tools/demo/selftest.py가 종류마다 가짜 adb·curl로 확인한다)
#   호출 실패(종료 코드≠0)·응답 대신 오류 글·출력이 중간에 끊김 → 기기에서 읽는 값은 dev_read로 읽는다. 출력 끝에 표식을 붙여 표식까지 온 것만 쓴다
#   응답 없음(멈춤)                    → adb는 a()가 ADB_TIMEOUT초 뒤 끊는다. VC 요청은 curl -m4
#   VC 요청 실패                       → vc는 멈추고, 되돌리기(정차·기준 상태)는 vc_try로 요청마다 따로 보내 끝에 알린다
#   앞 실행이 남긴 것(로그·결과 파일·pid 파일) → 결과는 이 실행 뒤에 생긴 것만 본다(scene-result.sh). pid 파일은 기기·포트마다 따로 둔다
#   환경 차이(기기·포트·경로의 공백과 특수문자) → 기기 셸에 넘기는 경로는 q()로 인용한다
#   도중에 끊음(Ctrl-C·TERM·멈춤)       → 차를 움직인 스크립트는 끝날 때 정차를 보낸다(drive.sh). 기다리는 스크립트는 남긴 프로세스를 거둔다
SERIAL="${SERIAL:-emulator-5560}"
VC_PORT="${VC_PORT:-8088}"
ADB_TIMEOUT="${ADB_TIMEOUT:-60}"
PKG=kr.aigyeot.app
VC_URL="http://127.0.0.1:$VC_PORT"
VC_PID="${VC_PID:-/tmp/aigyeot-demo-vc-$SERIAL-$VC_PORT.pid}"   # 세션 신호 루프의 pid 파일(기기·포트마다 하나. selftest.py는 임시 폴더로 바꾼다)
command -v adb >/dev/null 2>&1 || PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
command -v adb >/dev/null 2>&1 || { echo "adb를 찾지 못했다. PATH에 platform-tools를 넣어 달라"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3를 찾지 못했다(macOS는 Xcode Command Line Tools에 들어 있다)"; exit 1; }

die() { echo "실패: $*" >&2; exit 1; }
# adb 호출 하나. ADB_TIMEOUT초 안에 끝나지 않으면 끊는다(종료 코드 142). perl이 없으면 시간 제한 없이 부른다
if command -v perl >/dev/null 2>&1; then
  a() { perl -e 'alarm shift; exec @ARGV or die "exec: $!"' "$ADB_TIMEOUT" adb -s "$SERIAL" "$@"; }
else
  a() { adb -s "$SERIAL" "$@"; }
fi
# 기기 셸에 넘길 값 하나를 작은따옴표로 인용한다(공백·특수문자가 있어도 인자 하나로 간다)
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
# 기기 셸 명령 $1을 돌려 그 출력을 찍는다. 명령이 성공하고 출력이 끝까지 왔을 때만 0(출력 끝의 표식으로 가린다).
# 실패·응답 없음·중간에 끊긴 출력은 1이고 아무것도 찍지 않는다
dev_read() {
  local out; out="$(a shell "$1 && echo __end__" 2>/dev/null | tr -d '\r')"
  [ "${out##*$'\n'}" = __end__ ] || return 1
  [ "$out" = __end__ ] || printf '%s\n' "${out%$'\n'__end__}"
}

# 기기가 Pleos 에뮬레이터인지 확인한다.
need_pleos() {
  a get-state >/dev/null 2>&1 || die "$SERIAL 기기가 연결돼 있지 않다(adb devices 확인)"
  local fp; fp=$(a shell getprop ro.build.fingerprint | tr -d '\r')
  case "$fp" in Pleos/*) ;; *) die "$SERIAL 은 Pleos 에뮬레이터가 아니다(fingerprint=$fp)";; esac
}

# 아이곁 debug 빌드가 user 10에 깔려 있는지 확인한다. 장면 재생기와 run-as는 debug 빌드에만 있다. $1 = 무엇을 하려는지(안내 글에 넣는다)
need_debug_app() {
  local pkgs flags
  pkgs="$(dev_read "pm list packages --user 10")" || die "설치된 앱 목록을 읽지 못했다(adb 연결 확인)"
  printf '%s\n' "$pkgs" | grep -q "^package:$PKG\$" || die "$PKG 가 user 10에 설치돼 있지 않다(prepare.sh 먼저)"
  flags="$(dev_read "dumpsys package $PKG")" || die "앱 정보를 읽지 못했다(adb 연결 확인)"
  printf '%s\n' "$flags" | grep -q 'DEBUGGABLE' || die "$1: debug 빌드에만 있다. 지금 깔린 것은 release 빌드다(tools/demo/README.md 'debug로 돌리기'대로 debug를 설치)"
}

# 세션 신호를 1~2초마다 보내는 백그라운드를 시작한다(세션 신호가 끊기면 차량 입력이 먹지 않는다).
vc_start() {
  vc_stop
  a forward "tcp:$VC_PORT" tcp:8088 >/dev/null || die "adb forward 실패"   # 에뮬레이터 쪽은 늘 8088
  nohup bash -c "while :; do curl -s -m1 -X POST '$VC_URL/browser/session?client=demo' >/dev/null; sleep 1; done" >/dev/null 2>&1 &
  echo $! > "$VC_PID"
  local i
  for i in 1 2 3 4 5 6 7 8; do
    sleep 1
    curl -s -m2 "$VC_URL/vehicle/hvac" | grep -q '"emulator_active":true' && return 0
  done
  vc_stop
  die "VC 세션이 활성화되지 않았다(8번 확인). 에뮬레이터와 forward를 확인하고 다시 실행"
}

vc_stop() {
  [ -f "$VC_PID" ] && { kill "$(cat "$VC_PID")" 2>/dev/null; rm -f "$VC_PID"; }
  return 0
}

# vc_try PUT "/vehicle/gear?gear=Park" : 한 줄 보낸다. 실패하면 까닭을 찍고 1을 돌려준다(멈추지 않는다). 응답 본문은 버린다.
# 되돌리기처럼 하나가 실패해도 나머지를 마저 보내야 하는 곳에서 쓴다.
vc_try() {
  local out; out=$(curl -sf -m4 -X "$1" "$VC_URL$2") || { echo "실패: VC 요청 실패(연결 불가 또는 HTTP 4xx·5xx): $1 $2 (forward·세션 확인)" >&2; return 1; }
  case "$out" in *'"error"'*) echo "실패: VC 오류: $1 $2 → ${out:0:120}" >&2; return 1;; esac
}
# vc: vc_try와 같고, 실패하면 멈춘다.
vc() { vc_try "$@" || exit 1; }

# 공조 전원 상태(true/false)
hvac_power() { curl -s -m3 "$VC_URL/vehicle/hvac" | python3 -c 'import sys,json;print(str(json.load(sys.stdin)["power_on"]).lower())'; }

# 기준값: 기어 P, 정지, 뒷문 닫힘, 공조 전원 켬, 운전석 22도.
# 요청 하나가 실패해도 나머지를 마저 보내고(되돌리기), 하나라도 실패했으면 끝에 멈춘다.
set_baseline() {
  local rc=0
  vc_try PUT "/vehicle/gear?gear=Park" || rc=1
  # 브레이크를 한 번 밟았다 뗀다: 장면 E 끝(brake=100) 뒤 brake=0만 보내면 앱이 받는 BRAKE_PEDAL_STATUS가 true로 남는 일이 있었다(그러면 운전이 바쁘다고 보고 급하지 않은 안내를 미룬다)
  vc_try PUT "/vehicle/controls?speed=0&accelerator=0&brake=30&steering=0" || rc=1
  sleep 1
  vc_try PUT "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0" || rc=1
  vc_try PUT "/vehicle/body?door_rl=0&door_rr=0" || rc=1
  vc_try PUT "/vehicle/hvac?power=1&driver_temp=22.0" || rc=1
  sleep 1
  [ "$(hvac_power)" = true ] || { echo "실패: 공조 전원이 켜지지 않았다(VC power=1 뒤에도 false)" >&2; rc=1; }
  [ $rc = 0 ] || die "기준 상태로 다 되돌리지 못했다(위 실패 줄 확인)"
}

# 화면의 uiautomator 덤프(XML)를 찍는다.
ui_dump() {
  # 앞 덤프를 먼저 지운다. dump는 실패해도 종료 코드 0인 일이 있어, 남은 파일을 읽으면 옛 좌표를 누른다
  a shell rm -f /sdcard/aigyeot-ui.xml
  a shell uiautomator dump /sdcard/aigyeot-ui.xml >/dev/null 2>&1 || die "uiautomator dump 실패(화면이 켜져 있는지 확인)"
  a shell cat /sdcard/aigyeot-ui.xml 2>/dev/null || die "uiautomator dump가 파일을 남기지 않았다(화면 상태 확인)"
}

# stdin의 덤프에서 text가 $1인 노드의 bounds 중앙 "x y"를 찍는다. $2가 있으면 그 text 노드 다음에 처음 나오는 것만. 없으면 빈 출력.
ui_pos() {
  python3 -c '
import sys, re
xml = sys.stdin.read(); want = sys.argv[1]; after = sys.argv[2] if len(sys.argv) > 2 else ""
seen = not after
for m in re.finditer(r"text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", xml):
    t = m.group(1)
    if not seen:
        seen = (t == after)
        continue
    if t == want:
        x1, y1, x2, y2 = map(int, m.groups()[1:]); print((x1 + x2) // 2, (y1 + y2) // 2); break
' "$@"
}

# 덤프에서 글자 $1(있으면 $2 다음의 $1)을 약 UI_WAIT_S초(기본 10) 동안 찾는다. 덤프 사이에 1.5초 쉬고, 시간이 지나면 마지막으로 한 번 더 찾는다.
# 그 사이의 덤프 실패도 다시 한다. 마지막 한 번은 덤프 실패 메시지를 그대로 보인다. 찾으면 "x y", 못 찾으면 빈 출력
ui_wait() {
  local xy end=$((SECONDS + ${UI_WAIT_S:-10}))
  while [ $SECONDS -lt $end ]; do
    xy=$(ui_dump 2>/dev/null | ui_pos "$@")
    [ -n "$xy" ] && { echo "$xy"; return 0; }
    sleep 1.5
  done
  ui_dump | ui_pos "$@"
}

# 실내 조명 '무드 조명' 스위치를 차량 설정 화면에서 누른다(VC ambient_mode로는 앱이 읽는 스위치가 안 켜진다, featD_1110).
# $1 = 켜기 | 끄기. 차량 설정 → '라이트' → 아래로 스크롤 → '무드 조명' 다음의 [끄기|켜기|자동] 중 $1을 덤프에서 찾아 누른다.
# 에뮬레이터를 켠 직후에는 운전자 쪽 Pleos UI 서비스가 뒤늦게 뜨며 시스템 창 닫기(closeSystemDialog)를 보내, 열어 둔 차량 설정이 아무 단계에서나
# 사라지고 내비가 드러나는 일이 있다(demo_1111.txt). 그래서 한 번 시도가 막히면 차량 설정 열기부터 다시 한다(최대 세 번, 차량 설정 실행 자체가 오류면 멈춘다). 이미 $1이 골라져 있어도 다시 눌러 괜찮다
ambient_switch() {
  local i
  for i in 1 2 3; do
    ambient_try "$1" && return 0
    [ $i -lt 3 ] && echo "다시: 차량 설정을 처음부터 다시 연다($((i + 1))/3)" >&2
  done
  die "무드 조명 '$1'을 누르지 못했다(차량 설정을 세 번 열어 봄, 위 메시지 확인)"
}

# 화면 덤프($1)에 차량 설정 왼쪽 메뉴 '라이트'가 없으면 참(차량 설정이 사라짐). 덤프가 비면(덤프 실패) 거짓으로 보고 계속한다.
# 스크롤 고리에서만 쓴다: 덤프가 계속 비어도 그 고리는 결국 '찾지 못했다'로 다시 시도하므로 거짓 성공이 되지 않는다
vc_gone() { [ -n "$1" ] && [ -z "$(printf '%s' "$1" | ui_pos "라이트")" ]; }

# ambient_switch 한 번 시도. 막히면 1을 돌려준다(다시 시도할 일). 차량 설정 실행 자체가 오류면 멈춘다
ambient_try() {
  local want="$1" out xy x i
  out=$(a shell am start -n ai.umos.vehiclecontrol/.VehicleControlActivity 2>&1) || die "차량 설정을 열지 못했다: ${out:0:160}"
  case "$out" in *Error*|*Exception*) die "차량 설정을 열지 못했다: ${out:0:160}";; esac
  sleep 1   # 화면이 들어오는 움직임이 끝날 틈
  xy=$(UI_WAIT_S=5 ui_wait "라이트")
  [ -n "$xy" ] || { echo "차량 설정 화면에서 '라이트' 항목을 찾지 못했다(약 5초)" >&2; return 1; }
  a shell input tap $xy || return 1
  sleep 1
  # '라이트'를 누르면 맨 위(실외 조명)부터 보여 '무드 조명'은 스크롤해야 나온다. 스크롤마다 차량 설정이 아직 있는지 본다
  for i in 1 2 3 4 5 6; do
    x=$(ui_dump 2>/dev/null)   # 덤프가 한 번 실패해도 멈추지 않고 스크롤하며 다시 찾는다
    xy=$(printf '%s' "$x" | ui_pos "$want" "무드 조명")
    [ -n "$xy" ] && break
    vc_gone "$x" && { echo "차량 설정이 사라졌다('무드 조명'을 찾는 중)" >&2; return 1; }
    a shell input swipe 1900 1150 1900 250 500 || return 1
    sleep 1
  done
  [ -n "$xy" ] || { echo "'무드 조명' 다음의 '$want'를 찾지 못했다(스크롤 6번)" >&2; return 1; }
  a shell input tap $xy || return 1
  sleep 1
  # 누른 뒤에도 '라이트'가 보여야 성공으로 친다(덤프 실패는 ui_wait이 다시 찍는다). 누르기 직전 덤프에서 차량 설정이 열려 있었고 그 사이 다시 여는 일은 없으니,
  # 누른 뒤에도 보이면 탭이 차량 설정에 들어간 것이다. 안 보이면 누르는 순간 닫혀 다른 화면을 눌렀을 수 있어 다시 한다
  [ -n "$(UI_WAIT_S=3 ui_wait "라이트" 2>/dev/null)" ] || { echo "차량 설정이 사라졌다('$want'를 누른 직후)" >&2; return 1; }
  return 0
}
ambient_on() { ambient_switch 켜기; }
ambient_off() { ambient_switch 끄기; }

# 밝기·색은 VC로 바꾼다. 같은 body PUT이 VC 기본 밝기 100을 다시 내므로 기준값 뒤에 한 번 맞춘다. 기본 70·흰색(원래 값).
ambient_level() { vc PUT "/vehicle/body?ambient_brightness_pct=${1:-70}&ambient_color=%23${2:-ffffff}"; }

# 앱을 user 10 앞으로 가져온다.
app_front() {
  local out rc
  out=$(a shell am start --user 10 -n "$PKG/.MainActivity" 2>&1); rc=$?
  if [ $rc -ne 0 ]; then die "앱을 띄우지 못했다(종료 코드 $rc): ${out:0:160}"; fi
  case "$out" in *Error*|*Exception*) die "앱을 띄우지 못했다: ${out:0:160}";; esac
}

# 인자 경로를 절대 경로로 바꾼다(부르는 쪽이 원래 폴더 기준으로 부른다). 폴더가 없으면 그대로 둔다.
abspath() { local d; d=$(cd "$(dirname "$1")" 2>/dev/null && pwd) && echo "$d/$(basename "$1")" || echo "$1"; }
