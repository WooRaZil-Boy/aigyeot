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

# 실내 조명 '무드 조명' 스위치를 차량 설정 화면에서 누른다(VC ambient_mode로는 앱이 읽는 스위치가 안 켜진다, featD_1110).
# $1 = 켜기 | 끄기. 차량 설정 → '라이트' → 아래로 스크롤 → '무드 조명' 다음의 [끄기|켜기|자동] 중 $1을 덤프에서 찾아 누른다.
ambient_switch() {
  local want="$1" out xy i
  out=$(a shell am start -n ai.umos.vehiclecontrol/.VehicleControlActivity 2>&1) || die "차량 설정을 열지 못했다: ${out:0:160}"
  case "$out" in *Error*|*Exception*) die "차량 설정을 열지 못했다: ${out:0:160}";; esac
  sleep 2
  xy=$(ui_dump | ui_pos "라이트"); [ -n "$xy" ] || die "차량 설정 화면에서 '라이트' 항목을 찾지 못했다(화면 덤프에 없음)"
  a shell input tap $xy || { echo "실패: '라이트' 탭 실패(adb input tap $xy)" >&2; return 1; }
  sleep 1
  xy=""
  for i in 1 2 3 4 5 6; do
    xy=$(ui_dump | ui_pos "$want" "무드 조명")
    [ -n "$xy" ] && break
    a shell input swipe 1900 1150 1900 250 500 || { echo "실패: 스크롤 실패(adb input swipe)" >&2; return 1; }
    sleep 1
  done
  [ -n "$xy" ] || die "'무드 조명' 다음의 '$want'를 찾지 못했다(스크롤 6번까지 시도)"
  a shell input tap $xy || { echo "실패: '$want' 탭 실패(adb input tap $xy)" >&2; return 1; }
  sleep 1   # 마지막 명령이 탭이 아니게 되므로 위 || 가 실패 코드를 지킨다
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
