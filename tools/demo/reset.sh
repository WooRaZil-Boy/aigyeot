#!/usr/bin/env bash
# 같은 장면을 되풀이하기 전. 기준값과 공조 전원을 다시 맞추고 앱을 앞으로 가져온다.
# 앞 회차의 경유지 요청이 내비를 앞으로 띄우면 다음 '아기 탔어요'에서 마이크를 못 켠다.
set -u
cd "$(dirname "$0")" && . ./vc.sh
need_pleos
vc_start   # 앞 루프를 끄고 forward·세션을 다시 잡는다
set_baseline
app_front
echo "기준값을 다시 맞추고 앱을 앞으로 가져왔다(공조 전원 $(hvac_power))."
