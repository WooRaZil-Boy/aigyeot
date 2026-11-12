# 시연 준비 스크립트

Pleos 에뮬레이터(AVD `PleosConnect`, 2560×1440 공식 프로필, `-timezone Asia/Seoul`로 띄움)에서 아이곁 차 앱을 시연하기 전후에 쓰는 bash 스크립트다. 모든 adb 명령은 `-s "$SERIAL"`을 붙인다(기본 `emulator-5560`, `SERIAL=emulator-5554 ./prepare.sh`처럼 바꾼다). 실패하면 무엇이 왜 실패했는지 찍고 멈춘다. 중계(`wrangler dev`)는 다루지 않는다.

| 파일 | 쓰임 |
| --- | --- |
| `prepare.sh [APK]` | 시연 전 한 번. Pleos 확인, 프로브 삭제, APK 설치(기본 debug 산출물), user 10 설치 확인, 권한(`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `CAR_SPEED`), forward 8088, VC 세션 신호 유지 시작, 기준값(기어 P·정지·뒷문 닫힘·**공조 전원 켬**·22℃), 앱 실행 |
| `cry.sh <녹음>...` | debug 빌드만. 장면용 울음 녹음(16kHz 모노 16비트 PCM)을 `files/scenario/cry/`에 넣고 크기를 확인한다. release면 "방법이 정해지지 않음"을 찍고 멈춘다 |
| `reset.sh` | 같은 장면을 되풀이하기 전. 기준값·공조 전원을 다시 맞추고 앱을 앞으로 가져온다(앞 회차의 내비 화면·주행 상태를 지움) |
| `stop.sh` | 세션 신호 유지를 멈추고 forward를 지운다 |
| 앰비언트(`prepare.sh`·`reset.sh`·`stop.sh`) | 조명 낮춤 시연용. `prepare.sh`는 차량 설정 '무드 조명'을 '켜기'로 켜고(VC `ambient_mode`로는 안 켜진다, 설정 화면 덤프에서 '켜기'를 찾아 누름) 밝기 70·흰색으로 맞춘다. `reset.sh`는 밝기 70·흰색만 되돌리고 스위치는 그대로, `stop.sh`는 스위치를 끈다. 시연 중 차량 서비스(VHAL·CarService)가 다시 뜨면 무드 조명이 꺼짐으로 돌아간다(11-09 실측). 장면 도중이면 차량 설정 → 라이트 → '무드 조명'에서 '켜기'를 누르고 앱으로 돌아온다. `prepare.sh`는 APK를 다시 설치해(`adb install -r`) 앱을 끝내므로 장면 사이에만 돌린다 |
| `vc.sh` | 공용 함수(`vc`, `vc_start`, `vc_stop`, `set_baseline` 등). 다른 스크립트가 source 한다 |

순서: `prepare.sh` → (debug면) `cry.sh 녹음파일` → (장면 C·C2면) 내비에서 먼 목적지 길안내 켜기·앞 회차 경유지 지우기 → 차 화면에서 시연·측정 → (장면 C면) 반복을 1로 → 장면 실행 → 다시 하기 전에 `reset.sh` → 끝나면 `stop.sh`.

장면 C·C2는 '응' 뒤 내비 경로에 경유지를 넣는다. 길안내가 없으면 경유지가 반영되지 않고, 앞 회차 경유지가 남아 있으면 내비가 같은 요청에 아무것도 바꾸지 않는다. 경유지 요청은 내비를 앞으로 띄워 다음 회차에서 마이크를 켤 수 없으므로, 장면 C는 반복을 1로 맞춰 한 번씩 돌리고(기본 5, C2는 기본 1) 다시 하기 전에 `reset.sh`로 앱을 앞으로 가져오고 내비에서 그 경유지를 지운다(저장소 README '시연 스크립트와 장면').

장면 녹음 이름: A `stop_40s.pcm`, B `cont_75s.pcm`, C `stop_50s.pcm`, D `cont_long.pcm`(장면 파일의 `audio.file`). 녹음 원본은 저장소에 없다.

공조 전원: VC가 켜질 때 공조가 '꺼짐'으로 보이는 일이 있어(1105c) 기준값에 `PUT /vehicle/hvac?power=1`을 넣었다. 되돌린 뒤 `GET /vehicle/hvac`의 `power_on`이 true인지 확인한다.
