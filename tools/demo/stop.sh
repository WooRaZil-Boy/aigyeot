#!/usr/bin/env bash
# 시연 뒤 정리. 세션 신호 유지를 멈추고 forward를 지운다.
cd "$(dirname "$0")" && . ./vc.sh
vc_stop
a forward --remove "tcp:$VC_PORT" 2>/dev/null
echo "세션 신호 유지와 forward($VC_PORT)를 지웠다."
