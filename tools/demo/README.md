# 시연 스크립트와 장면

Pleos Connect 에뮬레이터에서 아이곁 차 앱을 시연할 때 쓰는 bash 스크립트와 절차입니다. 명령은 저장소 루트에서 `SERIAL=<차 에뮬레이터 시리얼> tools/demo/<스크립트>`로 실행합니다(`SERIAL`을 빼면 `emulator-5560`). 스크립트는 실패하면 무엇이 왜 실패했는지 찍습니다. 차량 상태는 [Pleos Connect Virtual Console](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)(`http://127.0.0.1:8088/`)과 같은 요청으로 바꿉니다.

## 스크립트

| 파일 | 언제 | 하는 일 |
| --- | --- | --- |
| `prepare.sh [APK]` | 시연 전 한 번, 장면 사이에만 | 부팅이 끝나기를 기다림(최대 5분), Pleos 에뮬레이터 확인, 자동 시간대를 끄고 시간대를 Asia/Seoul로 맞춤(끝에서 한 번 더 확인), 실측 앱(`dev.probe.pleos`)이 있으면 삭제, APK 설치(인자가 없으면 `app/build/outputs/apk/debug/app-debug.apk`. 설치한 빌드 종류와 경로를 찍음), 운전자 사용자(user 10) 권한(`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `CAR_SPEED`), Virtual Console 연결과 세션 신호 유지, 기준 상태(기어 P·정지·뒷문 닫힘·공조 전원 켬·22℃), 차량 설정 '무드 조명' 켜기(밝기 70·흰색), 앱 실행. APK를 다시 설치해 앱을 끝내므로 장면 도중에는 돌리지 않습니다. '무드 조명' 단계는 차량 설정 화면을 세 번까지 다시 열며, 그래도 멈추면 다시 실행합니다. 다시 실행해도 같은 곳에서 멈추면 에뮬레이터를 껐다가 다시 띄운 뒤 실행합니다(차량 설정 화면이 스크롤되지 않던 때 이렇게 풀렸습니다) |
| `drive.sh [초]` | 장면 없이 짧게 달려 볼 때 | VC 연결과 기준 상태를 다시 잡고 앱을 앞으로 가져온 뒤, 기어 D·30km/h로 달리고(기본 25초) 기어 P로 정차해 뒷문을 열었다 닫습니다. 달리는 도중 Ctrl-C로 멈추거나 요청이 실패해도 속도 0·기어 P를 보내고 끝납니다. 아기 탑승 세션이 열려 있었으면 이때 끝납니다. 앱 홈에서 '아기 탔어요'를 누른 뒤 실행합니다 |
| `cry.sh <녹음>...` | 녹음 장면 전(debug 빌드만) | 울음 녹음을 앱의 `files/scenario/cry/`에 넣고 크기를 확인합니다. release 빌드에는 넣을 수 없어 멈춥니다 |
| `scene-result.sh` | 장면을 고른 뒤, '실행하기'를 누르기 전에 | 먼저 실행하고 '기다린다'가 찍히면 '실행하기'를 누릅니다. 이 실행 뒤의 결과만 읽어(앞 장면 결과는 읽지 않음) 회차마다 기대 조건을 채웠는지(`completed`)와 차량 제어 반영률(`control_ok_rate`)을 찍고 `reset.sh`로 정차 상태로 되돌립니다. 요청한 반복을 다 채우고 모든 회차가 기대 조건을 채우면 종료 코드 0, 아니면 1입니다. release 빌드·시작하지 못함·앱 꺼짐 등 끝나는 길 전부는 스크립트 머리 주석에 있습니다 |
| `selftest.py` | 스크립트를 고친 뒤(`tools/check.sh`가 함께 돌림) | 가짜 adb·curl로 기기 없이 `scene-result.sh`·`drive.sh`·`reset.sh`가 끝나는 길마다 종료 코드와 보낸 차량 요청을 확인하고, `stop.sh`가 정리까지 가는지 봅니다 |
| `reset.sh` | `scene-result.sh`가 장면 끝에 실행. 장면을 도중에 멈췄을 때는 직접 | VC 연결을 다시 잡고, 기준 상태와 무드 조명 밝기·색을 되돌리고 앱을 앞으로 가져옵니다 |
| `stop.sh` | 모든 확인이 끝난 뒤 | 무드 조명을 끄고 Virtual Console 세션 신호와 포트 연결을 지웁니다. 무드 조명을 끄지 못해도 나머지는 정리하고 실패 코드를 돌려줍니다. 그 뒤에는 `drive.sh`·`reset.sh`가 연결을 다시 잡습니다 |
| `vc.sh` | — | 공용 함수. 다른 스크립트가 불러 씁니다 |

## 장면 실행

장면 재생기는 debug 빌드에만 있습니다(제출 APK의 '아기 설정'에는 '시연·측정'이 없습니다). 먼저 아래 [debug로 돌리기](#debug로-돌리기녹음-장면인계-알림로컬-중계-서버) 순서로 debug 차 앱을 설치합니다.

차 앱 홈 → 오른쪽 위 '아기 설정' → 맨 아래 '시연·측정'에서 장면을 고르고, 반복 횟수를 '−'로 1에 맞춥니다. 먼저 `SERIAL=<차 에뮬레이터 시리얼> tools/demo/scene-result.sh`를 실행하고, '기다린다'가 찍히면 '실행하기'를 누릅니다. F14-01~20은 장면 20개라 하나씩 고릅니다. 화면 진행 줄의 '어긋남'은 일부 실패만 보여 주므로 결과는 `scene-result.sh`로 봅니다.

| 장면 | 보는 것 | 대략 길이 | 울음 녹음 |
| --- | --- | --- | --- |
| A | 출발 5분 뒤 울음 → 뒷좌석 재생 → 1차에 그침 | 약 20분 30초 | `stop_40s.pcm` |
| B | 실내 27℃. 1차 뒤에도 울어 공조를 2℃ 낮춘 뒤 그침 | 약 8분 | `cont_75s.pcm` |
| C | 수유 시간대 울음 → 수유실 휴게소를 내비 경유지로 | 약 3분 | `stop_50s.pcm` |
| C2 | 출발할 때 수유 시간 전에 수유실 휴게소 제안 | 약 50초 | 필요 없음 |
| D | 두 번 시도에도 울어 보호자에게 넘김 | 약 10분 30초 | `cont_long.pcm` |
| D2 | 넘길 때 목적지까지 6분 → 도착해서 돌보라고 안내 | 약 10분 30초 | `cont_long.pcm` |
| E | 도착: 꺼내 눕히기 안내, 뒷문을 열면 리포트 | 약 6분 30초 | 필요 없음 |
| F14-01~20 | 아기 탑승 세션을 열고 닫는 규칙 20가지(장면 20개를 하나씩 실행) | 장면마다 약 30초~1분 40초 | 필요 없음 |

길이는 장면 파일에서 계산한 대략의 시간이고, 실제로는 이보다 짧거나 길 수 있습니다. 녹음 장면(A·B·C·D·D2)은 표의 이름으로 준비한 울음 녹음을 `cry.sh`로 넣어야 돌아갑니다.

- **장면은 debug 빌드에만 있습니다.** 제출 APK(release)에는 '시연·측정'이 없습니다. 아래 'debug로 돌리기'대로 debug를 설치해 씁니다. 제출 APK로 울음 감지를 보려면 아래 '제출 APK로 울음 감지 보기'를 따릅니다.
- **장면 리포트:** 장면이 만든 리포트는 차 앱의 주행 리포트에만 남고 휴대폰으로 보내지 않습니다. 휴대폰 리포트는 `drive.sh` 같은 장면 밖 주행으로 봅니다.
- **장면이 끝난 뒤:** 장면은 기어·속도·브레이크·내비 화면을 남길 수 있고, 끝나도 주행 화면이 남을 수 있습니다. `scene-result.sh`가 결과를 찍은 뒤 정차 상태로 되돌리고 앱을 앞으로 가져옵니다.

### 장면 C·C2의 내비

장면 C·C2는 운전자의 '응' 답을 장면이 직접 넣고(목소리로 '응' 하는 경로는 미판정), 실제 Pleos 내비 경로에 휴게소 경유지를 넣습니다.

1. 장면 전에 내비에서 먼 목적지(예: 부산역 KTX)로 길안내를 켜 둡니다. 길안내가 없으면 경유지가 들어가지 않습니다.
2. 경유지를 넣으면 내비가 앞으로 떠서 다음 회차에 마이크를 켤 수 없습니다. 그래서 반복은 늘 1로 둡니다. 같은 까닭으로 장면 시작 때 마이크가 켜져 있었으면, 장면이 기대대로 끝나도 결과 메모 끝에 '앱 화면이 없어 마이크를 다시 켜지 못함'이 붙습니다. 통과 여부는 `scene-result.sh`가 찍는 결과로 봅니다.
3. 두 장면은 같은 경유지를 넣습니다. 둘 중 하나를 다시 하거나 이어서 할 때는 `scene-result.sh`가 정차 상태로 되돌린 뒤, 내비 화면에서 그 경유지를 직접 지웁니다. 남아 있으면 내비가 같은 요청에 아무것도 바꾸지 않습니다.

## 제출 APK로 울음 감지 보기

장면 없이, 실제로 쓰는 경로대로 에뮬레이터 마이크에 울음 소리를 넣습니다. 맥에 가상 오디오 장치(예: BlackHole 2ch)와 `ffmpeg`이 있어야 합니다. 같은 경로를 11-13에 만든 release 빌드로 잰 기록은 [blackhole_cry_1113.txt](../measure/blackhole_cry_1113.txt)에 있습니다.

1. 맥의 기본 **입력**을 가상 오디오 장치로 바꿉니다. 기본 **출력**과 사운드 효과 출력은 스피커 등 다른 장치로 둡니다. 출력까지 가상 장치로 두면 에뮬레이터 마이크 소리가 끊겨 경보가 나지 않았습니다. 장치를 바꾼 뒤에는 에뮬레이터를 다시 띄웁니다.
2. 에뮬레이터가 맥의 오디오 입력을 받게 띄웁니다([공식 안내: 마이크 접근 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator#%EB%A7%88%EC%9D%B4%ED%81%AC-%EC%A0%91%EA%B7%BC-%EC%84%A4%EC%A0%95)).

   ```bash
   emulator -avd <AVD 이름> -timezone Asia/Seoul -allow-host-audio
   ```

3. 저장소 README 2단계의 `prepare.sh`를 실행합니다(이미 설치했어도 에뮬레이터를 다시 띄웠으면 다시 실행합니다. 앱을 띄우고 권한과 차량 상태를 맞춥니다). 아기 설정을 마쳤으면 홈에서 '아기 탔어요'를 누르고 3분 동안 달립니다.

   ```bash
   SERIAL=<차 에뮬레이터 시리얼> tools/demo/drive.sh 180
   ```

4. 달리는 동안 다른 터미널에서 울음 녹음을 가상 오디오 장치로 틉니다. 장치 번호는 `ffmpeg -f lavfi -i anullsrc -t 0.1 -f audiotoolbox -list_devices true -`가 찍는 목록의 `[번호]`입니다(audiotoolbox는 출력 장치라 이렇게 물어야 목록이 나옵니다). 녹음은 아무 아기 울음이나 되고, 확인에는 Wikimedia Commons의 퍼블릭 도메인 녹음 [Babys_disappointed_crying.oga](https://commons.wikimedia.org/wiki/File:Babys_disappointed_crying.oga)(48초)를 썼습니다.

   ```bash
   ffmpeg -re -i Babys_disappointed_crying.oga -f audiotoolbox -audio_device_index <장치 번호> -
   ```

5. 몇 초 안에 차가 "뒤에서 울어요…"라고 알리고 뒷좌석으로 달래는 소리를 틉니다. 소리를 멈추고 들어 그쳤는지 판정하는 순서는 [저장소 README](../../README.md#주요-기능)의 '뒷좌석에서 달래기'와 같습니다. 녹음(48초)은 소리를 멈추기 전에 끝납니다. 같은 조건으로 다섯 번 했을 때 네 번은 '그침'이었고, 한 번은 듣는 동안 판별 결과가 3초 넘게 나오지 않아 '판정하지 못함(감지 시간 초과)'이었습니다([측정 기록](../measure/readme_walkthrough_1113.txt)). 경보·뒷좌석 재생은 다섯 번 다 나왔습니다. 주행이 끝나면 리포트에 그 울음이 남습니다.

## debug로 돌리기(녹음 장면·인계 알림·로컬 중계 서버)

제출 APK(release)와 소스에서 만든 debug는 서명이 달라 같은 기기에 서로 덮어 설치되지 않습니다(차 앱·휴대폰 앱 모두, 어느 쪽으로 바꿀 때나). 바꿀 때는 다른 AVD를 쓰거나, 앱을 지운 뒤 다시 설치합니다. 지우면 그 기기의 아기 설정·휴대폰 연결·기록도 함께 지워집니다.

```bash
adb -s <차 에뮬레이터 시리얼> uninstall kr.aigyeot.app      # 차 앱. 다시 설치는 prepare.sh <APK 경로>(권한까지 줌, 경로를 빼면 debug 산출물)
adb -s <폰 에뮬레이터 시리얼> uninstall kr.aigyeot.phone    # 안드로이드 휴대폰 앱. 다시 설치는 adb -s <폰 에뮬레이터 시리얼> install
```

debug 빌드는 맥의 로컬 중계 서버에 붙으므로, 차·휴대폰 모두 debug로 맞춥니다. 소스에서 빌드하므로 먼저 [저장소 README](../../README.md#테스트-환경과-필요한-설정)의 필수 설정(`local.properties`의 `sdk.dir`, `JAVA_HOME`)을 맞추고, 로컬 중계 서버에는 Node.js가 필요합니다(확인한 판은 저장소 README의 환경 표).

```bash
# 터미널 1(켜 둡니다)
(cd relay && npm ci && npm run dev)   # wrangler dev --port 8787
```

```bash
# 터미널 2
./gradlew :app:assembleDebug :phone:assembleDebug
SERIAL=<차 에뮬레이터 시리얼> tools/demo/prepare.sh                 # debug 차 앱 설치·권한·차량 상태
SERIAL=<차 에뮬레이터 시리얼> tools/demo/cry.sh <녹음 파일>...       # 녹음 장면을 볼 때
adb -s <폰 에뮬레이터 시리얼> install -r phone/android/build/outputs/apk/debug/phone-debug.apk
```

- **중계 주소:** 차 앱(Pleos 에뮬레이터)과 안드로이드 휴대폰 앱(일반 안드로이드 에뮬레이터)은 `http://10.0.2.2:8787`, iOS 휴대폰 앱(iPhone 시뮬레이터)은 `http://localhost:8787`에 붙습니다. iOS는 `Aigyeot` 스킴의 실행 구성을 `Debug`(기본값)로 둡니다. iOS 앱은 지우고 다시 설치해도 연결 정보가 키체인에 남아 첫 화면이 '연결이 끊겼어요'로 나올 수 있고, '다시 연결하기'를 누르면 연결 화면으로 갑니다.
- **울음 녹음:** 16kHz 모노 16비트 PCM이고, 장면마다 녹음 속 울음이 그치는지가 다릅니다. 측정에 쓴 녹음은 다음과 같습니다([scenario_A_1104.txt](../measure/scenario_A_1104.txt)·[scenario_B_1104.txt](../measure/scenario_B_1104.txt)·[scenario_C_1104.txt](../measure/scenario_C_1104.txt)·[scenario_D_1104.txt](../measure/scenario_D_1104.txt)).
  - A `stop_40s.pcm`: 울음 20초 + 조용함 20초(첫 재생 동안 울음이 그침)
  - C `stop_50s.pcm`: 울음 20초 + 조용함 30초
  - B `cont_75s.pcm`: 약 75초 동안 계속 우는 녹음
  - D·D2 `cont_long.pcm`: B와 같은 녹음. 장면이 240초 동안 되풀이해 넣어 두 번의 시도 내내 울게 함

  장면은 녹음을 장면 파일이 정한 길이(A 40초, B 75초, C 50초, D·D2 240초)만큼 넣고, 녹음이 더 짧으면 처음부터 되풀이합니다. 녹음 원본은 배포 허락을 확인하지 못해 저장소와 APK에 넣지 않았습니다.
- **인계 알림:** 장면 D·D2에서 생깁니다. 처음 연결한 휴대폰이 아니라, 그 휴대폰 앱의 설정 탭 → '보호자' → '초대 코드 만들기'로 만든 코드로 연결한 두 번째 휴대폰에 갑니다('인계 알림 받기'가 켜져 있을 때). 원격 푸시 키가 없으면 휴대폰 앱을 열어 둔 상태에서 알림과 '오늘' 탭 맨 위 카드로 확인합니다([e2e_v6_1110.txt](../measure/e2e_v6_1110.txt)).
- **선택 설정(LLM 답):** 실제 키가 있을 때만 `cp -n relay/dev.vars.example relay/.dev.vars` 뒤 값을 진짜로 바꿉니다([relay/README.md](../../relay/README.md)). 예시 값을 그대로 두면 가짜 키로 요청해 실제 호출이 실패하고, 키가 없으면 말로 묻기는 정해 둔 문장으로 답합니다.
- **선택 설정(원격 푸시):** 안드로이드는 `phone/android/google-services.json`을 넣습니다. iOS는 `phone/ios/Aigyeot/GoogleService-Info.plist`를 넣고 `phone/ios`에서 `xcodegen generate`로 프로젝트를 다시 만들거나, Xcode에서 그 파일을 `Aigyeot` 타깃에 추가합니다. 파일이 없으면 원격 푸시만 꺼집니다.

## 무드 조명과 공조

- **무드 조명:** Virtual Console의 `ambient_mode`로는 켜지지 않아, `prepare.sh`가 차량 설정 화면에서 '켜기'를 찾아 누릅니다. 시연 중 차량 서비스(VHAL·CarService)가 다시 뜨면 무드 조명이 꺼짐으로 돌아갑니다. 장면 도중이면 차량 설정 → 라이트 → '무드 조명'에서 '켜기'를 누르고 앱으로 돌아옵니다. 낮춘 밝기는 에뮬레이터 화면에 그려지지 않아 Virtual Console 웹 화면에서만 보입니다.
- **공조 전원:** Virtual Console이 켜질 때 공조가 '꺼짐'으로 보이는 일이 있어, 기준 상태에 공조 전원 켜기를 넣었습니다. 스크립트는 켠 뒤 `GET /vehicle/hvac`의 `power_on`이 `true`인지 확인합니다.
