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

# 앱을 user 10 앞으로 가져온다.
app_front() {
  local out rc
  out=$(a shell am start --user 10 -n "$PKG/.MainActivity" 2>&1); rc=$?
  if [ $rc -ne 0 ]; then die "앱을 띄우지 못했다(종료 코드 $rc): ${out:0:160}"; fi
  case "$out" in *Error*|*Exception*) die "앱을 띄우지 못했다: ${out:0:160}";; esac
}

# 인자 경로를 절대 경로로(스크립트가 cd 하기 전에 부른다). 없는 경로는 그대로 둔다.
abspath() { local d; d=$(cd "$(dirname "$1")" 2>/dev/null && pwd) && echo "$d/$(basename "$1")" || echo "$1"; }
