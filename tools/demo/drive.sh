#!/usr/bin/env bash
# 짧은 주행 한 번: VC 연결과 기준 상태(기어 P·정지)를 다시 잡고 앱을 앞으로 가져온 뒤, 기어 D·30km/h로 달리고
# 정차(기어 P)해 뒷문을 열었다 닫는다. 아기 탑승 세션이 열려 있었으면 뒷문을 열 때 끝난다. 달리는 도중 Ctrl-C로 멈추거나 요청이 실패해도 정차(속도 0·기어 P)를 보내고 끝난다. 정차·뒷문 닫기가 실패하면 VC 연결을 다시 잡고 다시 보낸다.
# 사용법: drive.sh [달릴 초, 기본 25]   (SERIAL=emulator-5560 환경 변수로 기기 변경)
# 앱 홈에서 '아기 탔어요'를 누른 뒤 실행한다. 휴대폰이 연결돼 있으면 세션이 끝날 때 리포트가 휴대폰으로 간다.
set -u
SEC="${1:-25}"
cd "$(dirname "$0")" && . ./vc.sh
case "$SEC" in ''|*[!0-9]*) die "달릴 초는 1 이상의 정수로 준다(받은 값: $SEC)";; esac
[ "$SEC" -ge 1 ] || die "달릴 초는 1 이상의 정수로 준다(받은 값: $SEC)"
need_pleos
vc_start      # forward·세션 신호를 다시 잡는다(prepare.sh 뒤 stop.sh를 했어도 동작)
set_baseline  # 앞 장면이 남긴 기어·속도·브레이크를 기준 상태로(장면 E 끝 브레이크, F14 끝 속도 등)
app_front     # 내비 등이 앞에 있으면 앱을 앞으로
# 정차: 속도 0과 기어 P를 따로 보낸다(하나가 실패해도 다른 하나는 보낸다). 어느 쪽이 실패했는지는 vc_try가 찍는다
park() {
  local rc=0
  vc_try PUT "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0" || rc=1
  vc_try PUT "/vehicle/gear?gear=Park" || rc=1
  return $rc
}
close_door() { vc_try PUT "/vehicle/body?door_rl=0"; }
# 되돌리기(이 스크립트가 바꾼 차 상태를 원래대로: 정차, 연 뒷문 닫기)가 실패하면 VC 연결(adb forward·세션 신호)을 다시 잡고 한 번 더 보낸다.
# 연결이 끊겨 실패했으면 이것으로 풀린다. $1 = 되돌리기 함수, $2 = 안내에 쓸 이름
restore_safe() {
  "$1" && return 0
  echo "VC 연결을 다시 잡고 $2 다시 보낸다" >&2
  ( vc_start ) && "$1"
}
# 기어 D를 보낸 뒤나 뒷문을 연 뒤 어떤 길로 끝나도(Ctrl-C·TERM·요청 실패로 멈춤) 정차·뒷문 닫기로 되돌린다
driving=0; door_open=0; sp=""
cleanup() {
  [ -n "$sp" ] && kill "$sp" 2>/dev/null
  if [ "$driving" = 1 ]; then driving=0; echo "멈춤: 정차(속도 0·기어 P)로 되돌린다." >&2; restore_safe park "정차를" || echo "실패: 정차를 다 보내지 못했다(위 실패 줄 확인, reset.sh로 다시)" >&2; fi
  if [ "$door_open" = 1 ]; then door_open=0; echo "멈춤: 연 뒷문을 닫는다." >&2; restore_safe close_door "뒷문 닫기를" || echo "실패: 뒷문을 닫지 못했다(reset.sh로 다시)" >&2; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
driving=1
vc PUT "/vehicle/gear?gear=Drive"
vc PUT "/vehicle/controls?speed=30&accelerator=0&brake=0&steering=0"
echo "기어 D·30km/h로 ${SEC}초 달린다(차 화면이 주행 화면으로 바뀐다)."
sleep "$SEC" & sp=$!; wait "$sp"; sp=""   # 신호를 받으면 sleep을 기다리지 않고 바로 트랩으로 간다(트랩이 sleep도 끈다)
restore_safe park "정차를" || exit 1   # 그래도 실패하면 정리(cleanup)가 한 번 더 보낸다
driving=0
sleep 2
door_open=1   # 여는 요청이 실패해도 열렸을 수 있으니 먼저 표시한다
vc PUT "/vehicle/body?door_rl=1"   # 기어 P에서 뒷문을 열면 세션이 끝난다
sleep 2 & sp=$!; wait "$sp"; sp=""
restore_safe close_door "뒷문 닫기를" || exit 1   # 그래도 실패하면 정리(cleanup)가 한 번 더 보낸다
door_open=0
echo "정차하고 뒷문을 열었다 닫았다."
