#!/usr/bin/env bash
# 시연 뒤 정리. 세션 신호 유지를 멈추고 forward를 지운다.
cd "$(dirname "$0")" && . ./vc.sh
rc=0
# 시연에서 켠 무드 조명을 끈다. Pleos 에뮬레이터일 때만(일반 안드로이드 기기에 차량 설정 am start를 보내지 않는다).
# 서브셸: die가 나도 이 스크립트의 정리는 이어 하고, 실패하면 마지막에 실패 코드를 돌려준다
if ( need_pleos ) 2>/dev/null; then
  ( ambient_off ) || { echo "경고: 무드 조명을 끄지 못했다" >&2; rc=1; }
else
  echo "Pleos 에뮬레이터가 아니거나 연결이 없어 무드 조명은 건너뛴다" >&2
fi
vc_stop
a forward --remove "tcp:$VC_PORT" 2>/dev/null
echo "세션 신호 유지와 forward($VC_PORT)를 지웠다."
exit $rc
