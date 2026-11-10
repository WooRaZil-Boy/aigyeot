# 아이곁

> 아기가 울면 차가 대신 달래 줍니다. 부모는 앞만 보고 운전합니다.

뒷좌석 카시트에 0~2세 아기를 태우고 혼자 운전하는 부모를 위한 Pleos Connect(Android Automotive) 앱입니다. 현대자동차그룹 차량용 앱 개발 공모전 출품작이며, 차 앱과 휴대폰 앱 두 개(안드로이드·iOS), 그 사이 중계로 이루어져 있습니다.

## 3분 안에 보기

아기가 울면 차가 차 안에서 울음을 감지합니다(에뮬레이터 장면 6회 중앙값 2.46초). 그러면 **뒷좌석 스피커에서만** 달래는 소리를 틀고(뒷좌석 출력이 없는 차에서는 '아기 설정'에서 허용했을 때만 앞에서 작게 틉니다), 운전자에게는 "뒤에서 울어요. 뒷자리에 자장가 틀었어요." 한 문장만 말합니다. 56초 뒤에는 소리를 멈추고 8초 들어 봅니다. 조용하면 소리를 끈 채 30초 더 확인한 뒤 '그쳤다'고 기록합니다. 자동 시도는 두 번까지입니다. 두 번 해도 울면 소리를 멈추고 수유실 휴게소 같은 세울 곳을 내비 경유지로 넣을지 묻습니다. 연결된 다른 보호자 휴대폰에도 알려 사람에게 넘깁니다. 주행 중에 운전자가 화면을 만질 일은 없습니다. 쉼터 질문에만 '응'이라고 말로 답합니다.

| 핵심 장면 | 무엇을 보면 되나 | 장면 파일 |
| --- | --- | --- |
| 울음 → 뒷좌석에만 재생 | 출발 5분 뒤 울음. 소리는 뒷좌석 오디오 존(`bus100_audio_zone_1`)으로만 나가고, 앞에서는 한 문장만 들립니다. 56초 재생 뒤 8초 듣고, 조용하면 30초 더 확인한 뒤 '그침'이 기록됩니다 | A |
| 더운 실내 → 2차 대응 | 1차 뒤에도 울고 실내가 27℃이면 공조를 2℃ 낮추고 "실내가 27도예요. 2도 낮췄어요."라고 말합니다 | B |
| 수유 시간대 → 수유실 휴게소 경유 | 수유 시각이 가까우면 "○킬로미터 앞 ○○휴게소에 수유실이 있어요. 들를까요?"라고 묻고, '응'이면 Pleos 내비 경로에 경유지를 넣습니다 | C, C2(출발할 때 미리 제안) |
| 두 번 실패 → 사람에게 넘김 | 자동 시도 두 번 뒤 재생을 멈추고 쉼터를 제안하거나 "○분 뒤 도착해요. 도착해서 돌봐 주세요."라고 말합니다. 다른 보호자 휴대폰에도 알림을 보냅니다 | D, D2 |

그 밖에 하는 일입니다.
- **아이별 기록:** 어떤 소리를 틀었을 때 그쳤는지를 아이별로 쌓아 다음 소리를 고릅니다(백색소음, 자장가, 뱃속 소리, 부모가 녹음한 노래). 리포트에는 "그 소리를 틀었을 때 그친 횟수예요. 소리 덕분인지는 알 수 없어요"라고 함께 적습니다.
- **말로 묻기(④ 상황판):** "뭐가 제일 잘 통해?", "언제 먹였지?"처럼 운전자가 먼저 물을 때만 기록으로 답합니다.
- **휴대폰 앱:** 수유 등 돌봄 기록, 부모 노래 녹음, 주행 리포트 보기, 인계 알림 받기.
- **조명 낮춤:** 차량 설정의 무드 조명이 켜져 있으면 달래는 동안 실내 조명을 낮추고, 그친 뒤 원래대로 돌립니다.

하지 않는 일도 있습니다. 울음의 원인을 맞히지 않고, 잠·호흡을 감지하지 않습니다. 소리 크기를 울음에 맞춰 키우지 않습니다. 울음 원음은 저장하거나 밖으로 보내지 않습니다.

**어디부터 보면 되나**
1. 시연 영상([시연 영상](#시연-영상), 준비 중)
2. 에뮬레이터에서 직접 보기: [실행 방법](#실행-방법). 장면 C2·E는 울음 녹음 없이 돌릴 수 있습니다(C2는 경유지를 넣으므로 먼저 Pleos 내비에서 길안내를 켭니다).
3. 숫자: [실측·평가·시뮬레이션](#실측평가시뮬레이션). 에뮬레이터 실측, 맥 오프라인 평가, 시뮬레이션을 나눠 적었습니다.
4. 코드: 판단 규칙은 `engine/`(플랫폼 코드 없는 순수 Kotlin 상태 기계)에, Pleos SDK 연결은 `app/src/main/kotlin/kr/aigyeot/app/exec/android/`에, 울음 감지는 `app/src/main/kotlin/kr/aigyeot/app/detect/`에 있습니다.

## 저장소 구성

| 위치 | 내용 |
| --- | --- |
| `app/` | 차 앱(Pleos Connect). 울음 감지(`detect`), SDK 실행(`exec`), 감시 서비스(`host`), 휴대폰 연결(`link`), 장면 재생(`scenario`), 기록(`store`), 정차 화면(`ui`) |
| `engine/` | 판단 코어. 세션·달래기·판정·인계 규칙을 담은 순수 Kotlin 상태 기계이고, JVM 시험으로 검증합니다 |
| `shared/` | 차·휴대폰·중계가 함께 쓰는 메시지 형식과 JSON 예제(`shared/src/test/resources/protocol/`) |
| `phone/android/` | 안드로이드 휴대폰 앱(Kotlin, Jetpack Compose) |
| `phone/ios/` | iOS 휴대폰 앱(SwiftUI). 프로젝트 파일은 `project.yml`(XcodeGen)에서 만듭니다 |
| `relay/` | 차·휴대폰 중계(Cloudflare Workers + Durable Objects). 로컬은 `wrangler dev` |
| [tools/demo](tools/demo/) | 에뮬레이터 시연 준비 스크립트(설치·권한·Virtual Console·기준값) |
| [tools/measure](tools/measure/) | 에뮬레이터 실측 기록(글). 측정 중 찍은 화면 캡처는 저장소에 넣지 않습니다 |
| [tools/cry-retrain](tools/cry-retrain/) | 울음 판별층 재학습·평가 스크립트와 기기용 판별층 `cry_head24_rt.json`. YAMNet 점수 위에 얹는 로지스틱 판별층입니다 |
| [tools/pleos-probe](tools/pleos-probe/) | Pleos 공식 에뮬레이터에서 소리·차량 제어·내비·부팅·마이크 동작을 잰 실측 앱 |
| [tools/sim](tools/sim/) | 아이별 소리 고르기(톰슨 샘플링) 시뮬레이션과 차량 제어 실측 로그 |
| [tools/sound](tools/sound/) | 뱃속 소리 합성 스크립트 |
| `tools/check.sh` | 모든 시험을 한 번에 돌립니다 |
| `apk/` | 제출용 release APK(제출 전에 넣습니다) |

## 실행 방법

명령은 저장소 루트에서 실행합니다. 확인한 환경은 macOS(Apple Silicon)입니다. 시연 스크립트는 bash·curl·python3를 씁니다.

### 1. Pleos Connect 에뮬레이터 준비

[공식 설치 문서](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator#setting-up-the-emulator)대로 AVD를 만듭니다.
- 기기 프로필 `Pleos Connect v2.1.0`(2560×1440), 시스템 이미지 API 34 `Pleos Connect v2.1.0 APIs`
- RAM 4GB, 부팅 방식 Cold boot

시연 기록은 한국 시간으로 남기므로 시간대를 지정해 띄웁니다.

```bash
emulator -avd <AVD 이름> -timezone Asia/Seoul
```

차량 상태(기어·속도·문·공조)는 [Virtual Console](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator#how-to-run-pleos-connect-virtual-console)로 바꿉니다. `adb forward tcp:8088 tcp:8088` 뒤 브라우저에서 `http://127.0.0.1:8088/`을 엽니다. 세션 신호(`POST /browser/session`)가 3초 안에 계속 들어와야 차량 입력이 반영됩니다. 아래 `prepare.sh`가 이 신호를 1초마다 보냅니다.

### 2. 차 앱 설치(운전자 사용자 user 10)

Pleos 에뮬레이터의 운전자는 user 10입니다. 앱은 user 10에서 실행합니다.

```bash
adb install -r <APK>          # apk/ 안의 release APK, 또는 소스 빌드 산출물
adb shell pm grant --user 10 kr.aigyeot.app android.permission.RECORD_AUDIO
adb shell pm grant --user 10 kr.aigyeot.app android.permission.POST_NOTIFICATIONS
adb shell pm grant --user 10 kr.aigyeot.app android.car.permission.CAR_SPEED
adb shell am start --user 10 -n kr.aigyeot.app/.MainActivity
```

권한은 앱이 띄우는 권한 창에서 허용해도 됩니다. 처음 실행하면 '아기 설정'(동의, 별명, 태어난 달, 카시트 자리, 수유 간격 등)을 마쳐야 홈 화면이 나옵니다.

마이크는 시동 뒤 앱 화면이 없으면 Android가 열어 주지 않습니다. 그래서 출발 전에 정차 화면에서 '아기 탔어요'를 한 번 누릅니다. 기어를 D로 바꾸면 Pleos 주행 제한 화면이 앱 정차 화면을 가립니다.

### 3. 시연 스크립트와 장면

[tools/demo](tools/demo/)의 스크립트는 기본 기기를 `emulator-5560`으로 잡습니다. 다른 기기면 `SERIAL=emulator-5554`처럼 지정합니다.

```bash
SERIAL=emulator-5554 tools/demo/prepare.sh [APK]   # 시연 전 한 번. APK를 생략하면 debug 빌드 산출물
SERIAL=emulator-5554 tools/demo/cry.sh <녹음>...    # debug 빌드만. 장면용 울음 녹음 넣기
SERIAL=emulator-5554 tools/demo/reset.sh            # 같은 장면을 다시 하기 전
SERIAL=emulator-5554 tools/demo/stop.sh             # 끝나면
```

- `prepare.sh`가 하는 일: Pleos 에뮬레이터 확인, 실측 프로브 삭제, APK 설치, user 10 설치 확인, 권한 부여(`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `CAR_SPEED`), Virtual Console forward와 세션 신호, 기준값(기어 P, 정지, 뒷문 닫힘, 공조 켬, 22℃), 차량 설정 '무드 조명' 켜기(밝기 70·흰색), 앱 실행.
- `reset.sh`는 기준값과 조명을 되돌리고 앱을 앞으로 가져옵니다. 앞 회차의 경유지 요청이 내비를 앞으로 띄우면 다음 회차의 '아기 탔어요'에서 마이크를 켤 수 없기 때문입니다.
- `stop.sh`는 세션 신호와 forward를 멈추고 무드 조명을 끕니다.

장면은 차 앱 홈 → '시연·측정' → '장면 고르기·실행'에서 고릅니다. 장면은 Virtual Console로 기어·속도를 바꾸고, 마이크 자리에 울음 녹음을 섞어 넣습니다. 그 녹음은 실제 감지 경로(YAMNet + 판별층)를 그대로 거칩니다. 장면은 실제 시간으로 흐르고(A는 약 20분), 반복 횟수는 장면 파일 값(대부분 5회)이 기본입니다. 한 번만 보려면 '−'로 1에 맞춥니다.

| 장면 | 내용 | 울음 녹음 |
| --- | --- | --- |
| A | 등원 20분: 출발 5분 뒤 울음, 1차에 그침 | `stop_40s.pcm` |
| B | 장보기 40분: 실내 27℃, 쾌적 조정 뒤 그침 | `cont_75s.pcm` |
| C | 명절: 수유 시간대 울음, 수유실 휴게소 경유 | `stop_50s.pcm` |
| C2 | 출발: 수유 시간 전에 수유실 휴게소 제안, '응' 뒤 경유지 | 필요 없음 |
| D | 계속 울음: 두 번 시도 뒤 인계 | `cont_long.pcm` |
| D2 | 도심: 인계인데 목적지까지 6분 | `cont_long.pcm` |
| E | 도착: 꺼내 눕히기 안내, 뒷문 열면 리포트 | 필요 없음 |
| F14-01~20 | 뒷문·기어로 세션을 열고 닫는 규칙 20가지 | 필요 없음 |

장면 C·C2는 '응' 뒤 Pleos 내비 경로에 경유지를 넣습니다. 내비에 길안내가 없으면 경유지가 반영되지 않으므로, 장면을 시작하기 전에 내비에서 먼 목적지(예: 부산역 KTX)로 길안내를 켜 둡니다([feature_ab_1106a.txt](tools/measure/feature_ab_1106a.txt) 1절). 경유지 요청은 내비를 앞으로 띄워 다음 회차에서 마이크를 켤 수 없으므로, 장면 C는 반복을 1로 맞춰 돌리고(C2는 장면 기본이 1) 다시 하기 전에 `reset.sh`로 앱을 앞으로 가져옵니다. 앞 회차의 경유지가 경로에 남아 있으면 내비가 같은 요청에 아무것도 바꾸지 않으므로, 다시 할 때는 내비에서 그 경유지를 지운 뒤 합니다([scenario_C_1105b.txt](tools/measure/scenario_C_1105b.txt) 3절).

울음 녹음(16kHz 모노 16비트 PCM)은 배포 허락을 확인하지 못해 저장소와 APK에 넣지 않았습니다. 그래서 녹음 장면(A·B·C·D·D2)은 지금 debug 빌드에서 `cry.sh`로 녹음을 넣어야 돌아갑니다. 장면 B·C·D·D2는 기어 D로 끝나 정차 화면이 가려지므로, `reset.sh` 뒤에 결과를 봅니다.

### 4. 소스 빌드와 시험

루트 `local.properties`에 `sdk.dir=<Android SDK 경로>`가 있어야 합니다. `./gradlew`는 `JAVA_HOME`(없으면 PATH의 `java`)으로 돕니다. JDK를 따로 깔지 않았으면 Android Studio 내장 JDK를 먼저 지정합니다(macOS: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`).

```bash
./gradlew :app:assembleDebug        # 차 앱 debug → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # 차 앱 release
./gradlew :phone:assembleDebug      # 안드로이드 폰 앱 → phone/android/build/outputs/apk/debug/phone-debug.apk
tools/check.sh                      # 시험 전부: engine·shared·app·phone(JVM), relay(typecheck·vitest), iOS(xcodebuild test)
IOS_SIM=<시뮬레이터 id> tools/check.sh   # iOS 시험을 돌릴 시뮬레이터 지정
```

- `tools/check.sh`는 `JAVA_HOME`이 없으면 Android Studio 내장 JDK를 씁니다. iOS 시험은 `xcodebuild`가 있을 때만 돌리고, 시뮬레이터를 지정하지 않으면 켜진 iPhone, 그다음 가장 새 런타임의 iPhone을 고릅니다.
- release 서명: `local.properties`에 `aigyeot.release.storeFile`·`storePassword`·`keyAlias`·`keyPassword` 넷이 모두 있으면 서명하고, 없으면 서명 없는 release를 만듭니다. 키는 저장소에 두지 않습니다. 비밀번호가 공백으로 시작하면 `Properties` 형식이 `=` 뒤 공백을 지우므로 첫 공백을 `\ `로 적습니다.

### 5. 휴대폰 앱과 중계

차·휴대폰을 한 맥에서 함께 돌릴 때는 로컬 중계를 씁니다. 에뮬레이터는 맥을 `10.0.2.2`로 봅니다.

| 실행하는 것 | 중계 주소(debug) |
| --- | --- |
| 차 앱(Pleos 에뮬레이터) | `http://10.0.2.2:8787` |
| 안드로이드 폰 앱(일반 안드로이드 에뮬레이터) | `http://10.0.2.2:8787` |
| iOS 폰 앱(iPhone 시뮬레이터) | `http://localhost:8787` |

**중계**

```bash
cd relay
npm ci
cp -n dev.vars.example .dev.vars  # 키가 있을 때만(-n: 이미 있으면 덮지 않음). 복사한 뒤 EXAMPLE 값을 진짜 값으로 바꿉니다. 건너뛰면 ④ LLM 답과 원격 푸시만 꺼집니다
npm run dev                       # wrangler dev --port 8787
curl http://127.0.0.1:8787/v1/health   # {"ok":true,…,"llm":…,"push":…}
```

`.dev.vars`에는 `ANTHROPIC_API_KEY`(④ 상황판 LLM)와 `FCM_PROJECT_ID`·`FCM_CLIENT_EMAIL`·`FCM_PRIVATE_KEY`(원격 푸시)를 넣습니다. 예시 값을 그대로 두면 가짜 키로 요청해, `/v1/health`는 `true`를 보이지만 실제 호출은 실패합니다. 이 파일은 저장소에 넣지 않습니다. 키가 없거나 중계에 닿지 못하면 ④는 정해 둔 문장으로 답합니다.

**안드로이드 폰 앱:** `./gradlew :phone:assembleDebug` 뒤 일반 안드로이드 에뮬레이터에 `adb -s <폰 에뮬레이터> install -r phone/android/build/outputs/apk/debug/phone-debug.apk`로 설치합니다. 원격 푸시를 쓰려면 Firebase 설정 파일 `phone/android/google-services.json`을 직접 만들어 둡니다. 없으면 Firebase 플러그인을 걸지 않고 빌드합니다.

**iOS 폰 앱:** `phone/ios/Aigyeot.xcodeproj`를 Xcode로 열어 `Aigyeot` 스킴을 iPhone 시뮬레이터에서 실행합니다. Firebase는 Swift Package Manager가 받습니다. `project.yml`을 바꿨다면 `cd phone/ios && xcodegen generate`로 프로젝트 파일을 다시 만듭니다. `GoogleService-Info.plist`가 앱에 없으면 원격 푸시만 켜지지 않습니다.

**연결:** 차 앱 홈 → '휴대폰 연결'에 QR과 짧은 코드가 나옵니다. 카메라가 없는 에뮬레이터·시뮬레이터에서는 휴대폰 앱에 코드를 넣습니다.

release 빌드의 중계 주소는 아직 정하지 않았습니다(배포 뒤 채웁니다). 그래서 지금 release 차 앱은 휴대폰 연결과 ④ LLM 답 없이 동작합니다.

## 테스트 환경

빌드 파일과 개발 맥에서 읽은 값입니다.

| 구분 | 항목 | 값 | 확인한 곳 |
| --- | --- | --- | --- |
| 에뮬레이터 | Pleos Connect Emulator | v2.1.0, Android 14(API 34) arm64 이미지 `Pleos/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0`, 기기 프로필 `Pleos Connect v2.1.0`(2560×1440), RAM 4GB, Cold boot | [emulator_1110.txt](tools/measure/emulator_1110.txt), 공식 설치 문서 |
| 차 앱 | Pleos SDK(`ai.pleos.playground`) | Vehicle 2.3.2, NaviHelper 3.0.5, SpeechToText 3.3.1, TextToSpeech 3.3.1 | `gradle/libs.versions.toml` |
| 차 앱 | MediaPipe tasks-audio(YAMNet) | 0.10.32 | `gradle/libs.versions.toml` |
| 차 앱 | compileSdk / targetSdk / minSdk | 37 / 34 / 28 | `app/build.gradle.kts` |
| 차 앱 | 넣는 ABI | arm64-v8a, x86_64 | `app/build.gradle.kts` |
| 안드로이드 폰 앱 | compileSdk / targetSdk / minSdk | 37 / 36 / 26 | `phone/android/build.gradle.kts` |
| 안드로이드 폰 앱 | Firebase BoM | 35.1.0 | `gradle/libs.versions.toml` |
| 안드로이드 공통 | Gradle / AGP | 9.6.0 / 9.4.1 | `gradle/wrapper/gradle-wrapper.properties`, `gradle/libs.versions.toml` |
| 안드로이드 공통 | Kotlin / KSP | 2.4.21 / 2.3.12 | `gradle/libs.versions.toml` |
| 안드로이드 공통 | Compose BOM / Room / OkHttp | 2026.09.00 / 2.8.5 / 5.5.1 | `gradle/libs.versions.toml` |
| 안드로이드 공통 | JDK | Gradle 실행은 Android Studio 내장 JDK(OpenJDK 25.0.3), 컴파일 대상은 Java 17 | `tools/check.sh`, 각 `build.gradle.kts` |
| iOS 폰 앱 | Xcode | 27.2(설치된 시뮬레이터 런타임 iOS 27.0·27.1) | 개발 맥 |
| iOS 폰 앱 | 최소 iOS / Swift 언어 모드 | 17.0 / 5.0 | `phone/ios/project.yml` |
| iOS 폰 앱 | XcodeGen / Firebase iOS SDK | 2.45.3 / 12.19.2 | 개발 맥, `phone/ios/project.yml` |
| 중계 | wrangler / TypeScript / Vitest | 4.156.0 / 7.0.2 / 5.0.5 | `relay/package.json` |
| 중계 | Node.js | v25.4.0(wrangler 4.156.0은 22 이상 요구) | 개발 맥 |
| 중계 | Workers compatibility_date | 2026-10-20 | `relay/wrangler.jsonc` |
| 개발 맥 | macOS | 27.0.1, Apple Silicon(arm64) | 개발 맥 |

## 쓰는 Pleos SDK와 권한

Pleos Playground의 [SDK 사용 신청](https://document.pleos.ai/docs/pleos-only/create-my-project#sdk-%EC%82%AC%EC%9A%A9-%EC%8B%A0%EC%B2%AD) 화면에서 Vehicle Control, TextToSpeech, SpeechToText는 아직 '준비 중'(신청 버튼 없음)입니다. 신청 없이 쓸 수 있는 것은 Vehicle State와 NaviHelper뿐입니다. 공모전 심사는 공식 에뮬레이터에서 매니페스트 권한 선언만으로 동작합니다. 실차 배포는 신청이 열린 뒤에 할 수 있습니다.

| SDK | 아이곁에서 쓰는 곳 | 매니페스트 권한 | Playground 사용 신청 상태 |
| --- | --- | --- | --- |
| Vehicle 2.3.2(읽기) | 뒷문 열림·닫힘(세션 시작·끝), 시트 열선·공조 온도·창 잠금·조명 상태 읽기, 조향각·브레이크·방향지시등(리포트의 조향 보정, 울음 직전 주행 맥락, 차선 변경 중 말 미루기) | `pleos.car.permission.CAR_DOORS`, `CAR_SEATS`, `CAR_CLIMATE`, `CAR_WINDOWS`, `CAR_LIGHTS`, `CAR_DRIVE` | Vehicle State: 사용 가능 |
| Vehicle 2.3.2(제어) | 아기 자리 시트 열선 끄기, 형제가 있으면 뒷창 잠금·풀기, 실내가 더우면 앞 공조 2℃ 낮춤, 정차 중 뒷좌석 독서등 끄기, 무드 조명 낮추기·되돌리기 | `CONTROL_CAR_SEATS`, `CONTROL_CAR_WINDOWS`, `CONTROL_CAR_CLIMATE`, `CONTROL_CAR_LIGHTS`(독서등). 무드 조명 밝기·색 쓰기는 읽기와 같은 `CAR_LIGHTS` | Vehicle Control: 준비 중 |
| NaviHelper 3.0.5 | 안내 중 여부, 남은 시간·거리, 경로 위 휴게소·졸음쉼터(TBT), 경유지 넣기 | `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH` | 사용 가능 |
| TextToSpeech 3.3.1 | 운전자에게 하는 한 문장 안내 | `TTS_SERVICE` | 준비 중 |
| SpeechToText 3.3.1 | 쉼터 질문의 '응/아니', ④ 상황판 질문, 멈춤 말('그만' 등) | `STT_SERVICE` | 준비 중 |
| AAOS 표준 `CarPropertyManager`(Pleos SDK 아님) | 기어(`GEAR_SELECTION`), 속도(`PERF_VEHICLE_SPEED`), 시동·전원 정책 | `android.car.permission.CAR_POWERTRAIN`, `CAR_SPEED`(런타임 권한), `READ_CAR_POWER_POLICY` | 해당 없음 |

그 밖의 Android 권한: `RECORD_AUDIO`·`FOREGROUND_SERVICE_MICROPHONE`(울음 감지 마이크 서비스), `FOREGROUND_SERVICE_SPECIAL_USE`·`RECEIVE_BOOT_COMPLETED`(부팅 때 뜨는 감시 서비스), `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`. 뒷좌석 전용 재생은 SDK가 아니라 Android `AudioTrack`의 출력 장치 지정으로 합니다.

## 실측·평가·시뮬레이션

세 종류를 섞지 않고 따로 적었습니다. 숫자는 표의 기록 파일에서 그대로 옮겼습니다.

### 에뮬레이터 실측

Pleos 공식 에뮬레이터에서 잰 값입니다. 장면은 마이크 자리에 울음 녹음을 섞은 것이라 실제 마이크로 들은 값이 아닙니다. 성능은 debug 빌드로 쟀습니다.

| 항목 | 결과 | 조건·기록 |
| --- | --- | --- |
| 경보 지연(울음 입력 → 첫 달래기 재생 명령) | 유효 6회 중앙값 2,464ms, 최대 3,515ms. 실제 소리가 나기까지는 아래 '경보 → 재생 시작'만큼 더 걸립니다 | 장면 A·B·C(기록의 B1~B3·C2는 회차 이름입니다. C2는 장면 C의 두 번째 측정이고 장면 C2가 아닙니다). [perf_1108.txt](tools/measure/perf_1108.txt) |
| 경보 → 재생 시작(재생 명령 → 소리 시작) | 31~169ms | 같은 회차. [perf_1108.txt](tools/measure/perf_1108.txt) |
| CPU | 한 코어 100% 기준 평균 2.56%(전체 4코어 대비 약 0.64%). 한 코어 기준 순간 최대 7.9%는 장면 시작 직후 한 번 | 장면 B 76표본. [perf_1108.txt](tools/measure/perf_1108.txt). 조명 낮춤 회차는 평균 2.79%·최대 4.7%([featD_1110.txt](tools/measure/featD_1110.txt)) |
| 메모리(PSS) | 최대 144,225KB(약 141MB) | 장면 B·C. [perf_1108.txt](tools/measure/perf_1108.txt) |
| 스레드 | 48~56개, 장면을 거듭해도 늘지 않음 | [perf_1108.txt](tools/measure/perf_1108.txt) |
| 뒷좌석 전용 재생 | 지정하면 `bus100_audio_zone_1`(뒷좌석 존), 지정하지 않으면 `bus0_media_out` | `AudioTrack` 출력 장치 지정. [rear_audio_1102.txt](tools/pleos-probe/rear_audio_1102.txt) |
| 주행 중 차량 제어 반영 | 24/24 | 기어 D·60km/h. 뒷좌석 열선(`ROW_2_LEFT`, 레벨 2↔0), 뒷창 잠금(`ROW_2_LEFT`, 잠금↔해제), 앞 공조 온도(`ROW_1_LEFT`), 앰비언트(전체 영역) 각 6/6. [babycare_Drive.txt](tools/sim/babycare_Drive.txt) |
| 뒷좌석 독서등 | 주행 중 0/6, 정차(P) 6/6 | 주행 중에는 시스템이 명령을 거절합니다(오류 50001). [babycare_Drive.txt](tools/sim/babycare_Drive.txt), [babycare_Park.txt](tools/sim/babycare_Park.txt) |
| 시동 뒤 화면 없이 마이크 열기 | 다섯 경로 모두 막힘 | 그래서 출발 전 정차 중에 한 번 눌러 켭니다. [mictest_1102.txt](tools/pleos-probe/mictest_1102.txt) |
| 기기 계산 = 맥 계산 | 6개 PCM, 구간 54개, 울음 확률 최대 절대차 0.00(소수 둘째 자리), 경보 6/6 일치 | [device_match_1102.txt](tools/pleos-probe/device_match_1102.txt) |
| MediaPipe 0.10.14 → 0.10.32 | PCM 3개, 구간 113개, 최대 차 0.00, 경보 일치. x86_64 라이브러리가 APK에 처음 들어감 | [mediapipe_1108.txt](tools/measure/mediapipe_1108.txt) |
| 앱이 넣은 경유지 | 실제 내비 안내 중 장면 C가 안성휴게소를 넣자, 내비 TBT에 '경유'(`TURN_WAY`) 항목으로 들어옴 | [emulator_1110.txt](tools/measure/emulator_1110.txt) 4절 |
| 조명 낮춤 | 무드 조명이 켜져 있으면 첫 재생 뒤 한 번 낮춤(밝기 70→20, 색 흰색→`#3050A0`)하고 그침 확인 뒤 되돌림. 꺼져 있으면 건드리지 않음. 운전자가 그 사이 밝기를 바꾸면 밝기는 되돌리지 않음 | [featD_1110.txt](tools/measure/featD_1110.txt) 1~3절 |
| 기어 읽기(AAOS 표준) | P→D→R→N→D→P 바꿀 때마다 `GEAR_SELECTION` 변경 수신 | [official_audit_1108.txt](tools/measure/official_audit_1108.txt) 1절 |

### 맥 오프라인 평가

맥에서 파이썬으로 돌린 평가입니다(Python 3.11, `mediapipe` 0.10.14). 재현율·오경보 숫자는 **교차검증(학습에 안 쓴 접기로 잼)** 값입니다. 같은 구조의 판별층을 접기마다 새로 학습해, 그 판별층이 학습에 쓰지 않은 접기에서 점수를 냈습니다(`rt_train.py`). 앱에 들어가는 `cry_head24_rt.json`은 같은 데이터 전체로 학습한 것입니다. 그래서 이 판별층에 진짜 학습 밖 시험은 AxonData(독립 울음 18개)와 아래 장면 시험(첫 경보·R1·R3)입니다. 장면 시험은 내보낸 판별층을 그대로 씁니다. 스크립트와 결과 파일은 [tools/cry-retrain](tools/cry-retrain/)에 있습니다.

| 항목 | 결과 | 조건 |
| --- | --- | --- |
| 울음 경보 재현율(교차검증) | 378/457(0.83) | 휴대폰으로 녹음한 아기 울음(Donate-a-Cry) 457개, 차 소음 10 dB, 기준 0.7. 이전 판별층은 0.64(같은 기준 0.7) / 0.70(이전 판별층 자신의 기준 0.5) |
| 오경보(교차검증) | 말소리 0/497, 음악 0/144, 노래(CSD) 7/107, ESC 다른 소리 15/1960 | 같은 조건. 말소리 497개 중 36개는 약 2.9초보다 짧아 판정 구간이 하나뿐이라, '연속 2번' 규칙상 경보를 낼 수 없습니다. 경보가 가능한 말소리는 461개입니다 |
| (참고, 시험 결과 아님) 학습 데이터 안 값 | 울음 379/457, 말소리 0/497, 음악 0/144, 노래 6/107, ESC 13/1960 | 내보낸 판별층을 같은 데이터에 다시 적용한 값 |
| 첫 경보 지연(장면 시험) | 중앙값 2.4초, 360장면 중 357장면에서 경보 | `rt_stop.py`. 재생은 울음 시작 6초 뒤에 켜지므로 첫 경보 357개 중 353개가 재생 시작 전에 났습니다. 재생 중 소음에 대한 강건성을 잰 값이 아닙니다 |
| 달래기 성공·실패 판정(R3) | 359/360 | 56초에 재생을 멈추고 8초 듣고, 재생을 끈 채 30초 더 확인 |
| 재생을 끄지 않고 t0+30~60초로 판정할 때(R1, 비교용), 노래 장면 | 실제로 그친 72번 중 54번 맞힘, 계속 운 72번은 72번 모두 맞힘 | 18번은 노래를 울음으로 착각해 '아직 운다'로 잘못 봤습니다 |

조건과 한계는 다음과 같습니다.
- 360번 = 학습에 쓰지 않은 가정 울음 녹음 18개(AxonData) × 울음 대 차 소음 비 2가지(10, 0 dB) × 정답 2가지(그침·계속) × 재생 조건 5가지(없음, 백색소음, 브라운 잡음, 노래 두 크기)입니다. 조건마다 소음을 새로 뽑았습니다.
- 장면의 경계가 분명합니다. '그침' 장면은 첫 재생 5~25초 뒤에 울음이 끝나고, '계속' 장면은 첫 재생(t0) 76초 뒤까지 이어집니다. 확인 중에 다시 우는 경우와 판정 시점 근처에서 그치는 경우는 시험하지 않았습니다.
- 장면 시험(R1·R3·`rt_stop`)의 차 소음은 학습에 쓴 것과 같은 DEMAND TCAR 300초 녹음에서 뽑았습니다. 같은 60초 구간 다섯 개 중 하나를 고르므로 소음은 학습 밖이 아닙니다.
- R1 시험에 쓴 노래는 판별층이 '울음 아님'으로 배운 노래와 같은 곡입니다.
- 평가는 `mediapipe` 0.10.14로 돌렸고 앱은 tasks-audio 0.10.32를 씁니다. 두 버전의 기기 확률은 위 에뮬레이터 표대로 같았습니다.

### 시뮬레이션

[tools/sim/bandit_sim.py](tools/sim/bandit_sim.py)의 결과입니다. 아이마다 방법별 '1분 안에 그칠 확률'이 다르다고 **가정**하고, 톰슨 샘플링을 늘 같은 방법·무작위·돌아가며 쓰기와 비교했습니다. 판정 오류는 위 R3 값(1/180, 0/180)을 넣었습니다. **가정한 확률에서 나온 값이고, 실제 아기에서의 효과가 아닙니다.**

| 가정(방법 3개, 아이 20,000명 × 울음 20번) | 마지막 5번에서 그 아이에게 가장 맞는 방법을 고른 비율 |
| --- | --- |
| 아이마다 차이 큼(방법별 확률 0.1~0.9 균등) | 톰슨 61.1%, 늘 같은 방법 33.8%, 돌아가며 33.5% |
| 차이 중간(0.3~0.7) | 톰슨 47.6%, 늘 같은 방법 34.4% |
| 차이 없음(모두 0.5) | 모두 100%(고를 차이가 없음) |

출처: [bandit_1106_K3.txt](tools/sim/bandit_1106_K3.txt). 방법 4개(부모 노래 포함)와 울음 40번 결과는 같은 폴더의 `_K4`·`_T40` 파일에 있습니다.

## APK

- **위치:** `apk/` 안의 release APK. 제출 전에 넣습니다.
- **ABI:** arm64-v8a·x86_64만 넣었습니다. Pleos 에뮬레이터(맥 arm64 이미지, Windows·Linux x86_64 이미지)와 실차(arm64)에 맞춘 것이고, 32비트(armeabi-v7a·x86)는 뺐습니다.
- **크기:** 서명 전 release 빌드 기준 70,815,340바이트(약 67.5MB, 11-09). 서명한 APK를 넣을 때 다시 적습니다.
- **release에서 다른 점:** 중계 주소가 아직 비어 있어 휴대폰 연결과 ④ LLM 답이 꺼집니다. 녹음 장면(A·B·C·D·D2)은 녹음을 넣을 방법이 debug에만 있습니다.

## 시연 영상

준비 중입니다. 영상 링크: (준비 중)

녹화 조건은 다음과 같습니다.
- 공식 기기 프로필 AVD를 `-timezone Asia/Seoul`로 띄웁니다.
- `tools/demo/prepare.sh`로 설치·권한·Virtual Console 세션·기준값·무드 조명 켜기를 맞추고, 회차 사이에는 `reset.sh`를 돌립니다.
- Virtual Console 웹 화면(`http://127.0.0.1:8088/`)을 함께 띄웁니다. 기어·문 상태가 보이고, 조명 낮춤은 에뮬레이터 화면에 그려지지 않아 이 화면에서만 보입니다.
- 녹화에 쓰는 에뮬레이터에는 마이크로 실제 소리가 들어오지 않습니다(아래 '에뮬레이터 마이크'). 울음은 장면 녹음으로 넣습니다.

## 알려진 한계와 확인하지 못한 것

- **실제 효과:** 아기가 실제로 덜 우는지, 운전자가 덜 돌아보는지는 재지 않았습니다. 아기와 사람이 있어야 잴 수 있습니다. 앱의 성적은 '그 소리를 틀었을 때 그친 횟수'이고, 소리 덕분인지는 알 수 없습니다.
- **설계값:** 56초·8초·30초·자동 시도 2회는 제품 설계값입니다. 임상 기준이 아닙니다.
- **감지 수치의 조건:** 휴대폰·가정 녹음에 차 소음을 섞어 맥에서 잰 값입니다. 차 안에서 녹음한 울음, 긴 녹음에서의 시간당 오경보, 형제 소리(웃음은 ESC 40개 중 4개가 경보)는 재지 않았습니다.
- **에뮬레이터 마이크:** 개발 맥에서 띄운 에뮬레이터에는 맥 마이크 소리가 들어오지 않았습니다. `-allow-host-audio`로 띄워도 입력 rms가 0.0이었고, 맥의 마이크 권한은 따로 주지 않았습니다. 공식 가이드의 Extended Controls 마이크 입력 설정을 켠 상태는 재지 않았습니다. 그래서 뒷좌석 재생 소리가 마이크로 되돌아오는지, 쉼터 질문에 목소리로 '응' 하는 경로는 미판정입니다. 장면은 '응'을 직접 넣습니다.
- **순정 음성 비서·통화와 함께 쓸 때:** 마이크 무음 처리와 오디오 포커스 겹침은 에뮬레이터에서 만들지 못해 미판정입니다.
- **실차 오디오 존:** 에뮬레이터의 뒷좌석 존(`bus100_audio_zone_1`) 기준입니다. 존 구성은 차마다 다를 수 있고 실차는 확인하지 않았습니다.
- **실내 온도:** 에뮬레이터 실내 온도는 19.1℃로 고정입니다. 공조를 낮추는 장면 B는 실내 27℃를 장면이 넣습니다.
- **x86_64(Windows·Linux) 에뮬레이터:** APK에 x86_64 라이브러리가 들어 있는 것까지만 확인했습니다. 실행은 미판정입니다(맥 Apple Silicon에서 x86_64 이미지를 띄우지 못함).
- **④ LLM과 원격 푸시:** API 키 없이 쟀습니다. 그래서 LLM 실제 답의 지연과 숫자 검사 통과율, FCM·APNs 실제 도착은 미판정입니다. 정해 둔 문장으로 답하는 경로만 쟀습니다.
- **에뮬레이터 안정성:** 맥 부하가 높을 때 에뮬레이터 CarService가 다시 뜨는 일이 있었습니다. 그때 앱 응답 없음(ANR)이 두 번 기록됐고(차 연결 콜백이 차량 서비스 호출에 막힘), 원인을 고쳤습니다. 고친 뒤 같은 부하에서 CarService가 7번 다시 뜰 때 앱은 그대로 살아 있었고 ANR은 0이었습니다. 이때 무드 조명 스위치가 꺼짐으로 돌아갑니다. 장면 도중이면 차량 설정 → 라이트 → '무드 조명'에서 '켜기'를 누르고 앱으로 돌아옵니다. `tools/demo/prepare.sh`는 APK를 다시 설치해(`adb install -r`) 앱을 끝내므로 장면 사이에만 씁니다. 기록: [emulator_1110.txt](tools/measure/emulator_1110.txt), [featD_1110.txt](tools/measure/featD_1110.txt).
- **SDK 신청:** Vehicle Control·TTS·STT 사용 신청이 아직 '준비 중'이라 실차 배포는 신청이 열린 뒤에 할 수 있습니다. 에뮬레이터에서 동작한다고 앱 마켓 심사(상시 청취, 뒷좌석 자동 재생)를 통과한다는 뜻은 아닙니다.
- **학습 데이터:** 비상업 조건 데이터가 들어 있어 판별층은 공모전 시연용입니다. 상용 배포 전에 상용 가능한 데이터로 다시 학습합니다.

## 출처 고지

- **자장가:** 브람스 「자장가」 Op.49 No.4(퍼블릭 도메인). MIDI: Rabanus Flavus(Peter Gerloff), CC0, Wikimedia Commons. 음색: FluidR3_GM(Frank Wen, MIT). −30 LUFS로 맞춰 렌더링함.
- **뱃속 소리:** 직접 합성한 소리(`tools/sound/womb.py`). 외부 음원을 쓰지 않았습니다. −30 LUFS로 맞춰 렌더링함.
- **휴게소·졸음쉼터 정보:** 공공데이터포털 전국휴게소정보표준데이터(한국도로공사, 2026-09-21)·전국졸음쉼터표준데이터(한국도로공사, 2026-03-05). 졸음쉼터 데이터 번호는 15028203입니다.
- **울음 판별 바탕 모델:** YAMNet(TensorFlow Model Garden, Apache 2.0)과 MediaPipe tasks-audio(Apache 2.0). Apache 2.0 전문은 [app/src/main/assets/licenses/Apache-2.0.txt](app/src/main/assets/licenses/Apache-2.0.txt)에 있습니다.
- **AudioSet 온톨로지:** YAMNet 모델 파일에 들어 있는 클래스 이름은 AudioSet 온톨로지(CC BY-SA 4.0)에서 왔습니다.
- **판별층:** YAMNet 점수 위에 얹은 판별층의 학습 데이터와 라이선스는 아래 표에 있습니다.
- **④ 상황판 LLM:** 중계가 외부 LLM API를 부릅니다(모델은 `relay/wrangler.jsonc`의 `LLM_MODEL`). 상태값과 운전자 질문 글자(아이 별명은 '아기'로 바꿈)만 보내고, 키는 중계에만 둡니다.
- **서체:** 시스템 서체를 씁니다. 별도 서체 파일은 넣지 않았습니다.
- **오픈소스 고지 화면:** 안드로이드·iOS 휴대폰 앱에는 앱 안에 오픈소스 라이선스 화면이 있습니다. 차 앱에는 아직 없고, Apache 2.0 전문만 앱 assets에 있습니다.

## 학습 데이터와 라이선스

판별층 학습에 쓴 데이터입니다. 비상업 조건 데이터가 들어 있어 이 판별층은 공모전 시연용입니다. 상용 배포 전에는 상용 가능한 데이터로 다시 학습합니다. 라이선스는 2026-11-02에 각 배포처의 표기를 확인했습니다.

| 데이터 | 쓰임 | 라이선스 |
| --- | --- | --- |
| [ESC-50](https://github.com/karolpiczak/ESC-50) | 울음·다른 소리 | CC BY-NC |
| [Donate-a-Cry corpus](https://github.com/gveres/donateacry-corpus) | 울음 | GitHub 라이선스 항목 없음. 저장소 README에 ODbL 1.0(데이터베이스)·DbCL 1.0(내용)이라고 적혀 있음 |
| [AxonData Infant Cry Detection Dataset](https://huggingface.co/datasets/AxonData/infant-cry-detection-dataset)(공개 표본) | 처음 듣는 울음 시험 | 메타데이터 표기는 CC BY 4.0. 설명 본문의 이용 조건과 맞는지는 확인하지 못함(그래서 녹음을 배포하지 않음) |
| [DEMAND](https://zenodo.org/records/1227121)(TCAR) | 차 실내 소음 | Zenodo 메타데이터는 CC BY 4.0, 설명 본문은 CC BY-SA 3.0(표기가 서로 다름) |
| [CSD: Children's Song Dataset](https://zenodo.org/records/4785016) | 노래(오경보 시험) | CC BY-NC-SA |
| [MUSDB18 7초 발췌](https://zenodo.org/records/3270814) | 음악(오경보 시험) | Zenodo 기록 표기는 CC BY 4.0(원곡별 권리는 확인하지 못함) |
| [Zeroth-Korean](https://huggingface.co/datasets/Bingsu/zeroth-korean)(test) | 한국어 말소리 | CC BY 4.0 |
| [Mini LibriSpeech](https://www.openslr.org/31/)(dev-clean-2) | 영어 말소리 | CC BY 4.0 |
| macOS `say` 합성 | 한국어 말소리 8개 | 직접 합성(공개 배포 안 함) |
