#!/usr/bin/env bash
# 시연 스크립트 공용 함수. 다른 스크립트가 source 한다(직접 실행하지 않는다).
# 환경 변수: SERIAL(기본 emulator-5560), VC_PORT(기본 8088)
SERIAL="${SERIAL:-emulator-5560}"
VC_PORT="${VC_PORT:-8088}"
PKG=kr.aigyeot.app
VC_URL="http://127.0.0.1:$VC_PORT"
VC_PID=/tmp/aigyeot-demo-vc.pid
command -v adb >/dev/null 2>&1 || PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
command -v adb >/dev/null 2>&1 || { echo "adb를 찾지 못했다. PATH에 platform-tools를 넣어 달라"; exit 1; }

die() { echo "실패: $*" >&2; exit 1; }
a() { adb -s "$SERIAL" "$@"; }

# 기기가 Pleos 에뮬레이터인지 확인한다.
need_pleos() {
  a get-state >/dev/null 2>&1 || die "$SERIAL 기기가 연결돼 있지 않다(adb devices 확인)"
  local fp; fp=$(a shell getprop ro.build.fingerprint | tr -d '\r')
  case "$fp" in Pleos/*) ;; *) die "$SERIAL 은 Pleos 에뮬레이터가 아니다(fingerprint=$fp)";; esac
}

# 세션 신호를 1초마다 보내는 백그라운드를 시작한다(3초 안에 계속 보내야 차량 입력이 먹는다).
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
  die "VC 세션이 활성화되지 않았다(8초). 에뮬레이터가 막 켜졌다면 잠시 뒤 다시 시도"
}

vc_stop() {
  [ -f "$VC_PID" ] && { kill "$(cat "$VC_PID")" 2>/dev/null; rm -f "$VC_PID"; }
  return 0
}

# vc PUT "/vehicle/gear?gear=Park" : 한 줄 보내고 오류가 있으면 멈춘다. 응답 본문은 버린다.
vc() {
  local out; out=$(curl -sf -m4 -X "$1" "$VC_URL$2") || die "VC 요청 실패(연결 불가 또는 HTTP 4xx·5xx): $1 $2 (forward·세션 확인)"
  case "$out" in *'"error"'*) die "VC 오류: $1 $2 → ${out:0:120}";; esac
}

# 공조 전원 상태(true/false)
hvac_power() { curl -s -m3 "$VC_URL/vehicle/hvac" | python3 -c 'import sys,json;print(str(json.load(sys.stdin)["power_on"]).lower())'; }

# 기준값: 기어 P, 정지, 뒷문 닫힘, 공조 전원 켬, 운전석 22도.
set_baseline() {
  vc PUT "/vehicle/gear?gear=Park"
  # 브레이크를 한 번 밟았다 뗀다: 장면 E 끝(brake=100) 뒤 brake=0만 보내면 앱이 받는 BRAKE_PEDAL_STATUS가 true로 남는 일이 있었다(그러면 운전이 바쁘다고 보고 S06·S07을 미룬다)
  vc PUT "/vehicle/controls?speed=0&accelerator=0&brake=30&steering=0"
  sleep 1
  vc PUT "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0"
  vc PUT "/vehicle/body?door_rl=0&door_rr=0"
  vc PUT "/vehicle/hvac?power=1&driver_temp=22.0"
  sleep 1
  [ "$(hvac_power)" = true ] || die "공조 전원이 켜지지 않았다(VC power=1 뒤에도 false)"
}

# 화면의 uiautomator 덤프(XML)를 찍는다.
ui_dump() {
  # 앞 덤프를 먼저 지운다. Android 14 dump는 화면이 멈추지 않아 실패해도 종료 코드 0이라, 남은 파일을 읽으면 옛 좌표를 누른다
  a shell rm -f /sdcard/aigyeot-ui.xml
  a shell uiautomator dump /sdcard/aigyeot-ui.xml >/dev/null 2>&1 || die "uiautomator dump 실패(화면이 켜져 있는지 확인)"
  a shell cat /sdcard/aigyeot-ui.xml 2>/dev/null || die "uiautomator dump가 파일을 남기지 않았다(화면이 멈추지 않음)"
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

# 덤프에서 글자 $1(있으면 $2 다음의 $1)을 찾을 때까지 최대 UI_WAIT_S초(기본 10), 1.5초마다 다시 찾는다(에뮬레이터를 켠 직후·맥 부하가 높을 때는 화면이 늦게 뜬다).
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
# 사라지고 내비가 드러나는 일이 있다(demo_1111.txt). 그래서 한 번 시도가 어디서 막히든 차량 설정 열기부터 다시 한다(최대 세 번). 이미 $1이 골라져 있어도 다시 눌러 괜찮다
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

# ambient_switch 한 번 시도. 막히면 이유를 찍고 1(다시 시도할 일), 차량 설정 실행 자체가 오류면 멈춘다
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

# 인자 경로를 절대 경로로(스크립트가 cd 하기 전에 부른다). 없는 경로는 그대로 둔다.
abspath() { local d; d=$(cd "$(dirname "$1")" 2>/dev/null && pwd) && echo "$d/$(basename "$1")" || echo "$1"; }
