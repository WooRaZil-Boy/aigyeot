#!/usr/bin/env bash
# 장면이 끝날 때마다. VC 연결을 다시 잡고, 기준 상태(기어 P·정지·뒷문 닫힘·공조 전원·22도)와 무드 조명 밝기 70·흰색을 되돌리고 앱을 앞으로 가져온다.
# 앞 회차의 경유지 요청이 내비를 앞으로 띄우면 다음 '아기 탔어요'에서 마이크를 못 켠다.
set -u
cd "$(dirname "$0")" && . ./vc.sh
need_pleos
vc_start   # 앞 루프를 끄고 forward·세션을 다시 잡는다
set_baseline
ambient_level 70 ffffff   # 앞 회차의 낮춘 밝기·색을 원래(70·흰색)로. 스위치는 그대로
app_front
echo "기준값을 다시 맞추고 앱을 앞으로 가져왔다(공조 전원 $(hvac_power))."
