# 아이곁

> 아기가 울면 차가 대신 달래 줍니다. 부모는 앞만 보고 운전합니다.

뒷좌석 카시트에 0~2세 아기를 태우고 혼자 운전하는 부모를 위한 Pleos Connect(Android Automotive) 앱입니다. 아기가 울면 차가 울음을 듣고 뒷좌석 스피커로 달랩니다. 현대자동차그룹 차량용 앱 개발 공모전 출품작이며, 차 앱과 보호자 휴대폰 앱(안드로이드·iOS), 그 사이 중계 서버로 이루어져 있습니다.

| 항목 | 내용 |
| --- | --- |
| APK | 차 앱 [`apk/곁빛_아이곁_release.apk`](apk/곁빛_아이곁_release.apk), 안드로이드 휴대폰 앱 [`apk/곁빛_아이곁_휴대폰_release.apk`](apk/곁빛_아이곁_휴대폰_release.apk). 둘 다 release 키로 서명 |
| 개발·시험 에뮬레이터 | Pleos Connect Emulator v2.1.0(Android 14, API 34), 기기 프로필 `Pleos Connect v2.1.0`, 화면 2560×1440. 11-08까지의 측정 일부는 같은 시스템 이미지의 1920×1080 AVD에서 했습니다([tools/measure/README.md](tools/measure/README.md)) |
| 실행 방법 | 에뮬레이터를 띄우고 `tools/demo/prepare.sh`로 APK 설치·권한·차량 상태를 맞춥니다([실행 방법](#실행-방법)) |
| 테스트 환경과 설정 | macOS(Apple Silicon)에서 확인. Pleos SDK 4종과 MediaPipe 사용([테스트 환경과 필요한 설정](#테스트-환경과-필요한-설정)) |

## 무엇을 하나

1. **차 안에서 듣기:** 차 마이크 소리를 차 안에서 판정합니다(MediaPipe YAMNet 점수 위에 직접 학습한 로지스틱 회귀 울음 판별층). 운전자의 말은 Pleos SpeechToText의 기기 안 인식(ON_DEVICE)으로 알아듣습니다. 마이크 소리는 저장하거나 차 밖으로 보내지 않습니다.
2. **뒷좌석에서 달래기:** 달래는 소리는 뒷좌석 오디오 존으로 틉니다(뒷좌석 출력이 없는 차에서는 '아기 설정'에서 허용했을 때만 앞에서 작게). 운전자에게 처음 알리는 말은 "뒤에서 울어요. 뒷자리에 자장가 틀었어요." 같은 짧은 문장입니다. 56초 틀고 멈춰 8초 듣고, 조용하면 소리를 끈 채 30초 더 확인해 그쳤는지 판정합니다. 재생을 켠 채 판정하지 않는 까닭은 노래를 울음으로 잘못 듣는 경우가 있어서입니다(아래 [검증 결과](#검증-결과)).
3. **두 번까지만:** 자동 시도는 두 번까지입니다. 1차 뒤에도 울고 실내가 더우면 앞 공조 설정을 2℃ 낮출 수 있습니다. 두 번 해도 울면 소리를 멈추고 인계(돌봄 넘김)로 넘어갑니다. 다른 보호자의 휴대폰이 연결돼 있으면 알림을 보내고, 달리는 중이면 경로 앞 휴게소·졸음쉼터를 내비 경유지로 넣을지 묻거나 도착해서·세워서 돌보라고 안내할 수 있습니다. 언제 무엇을 하는지는 `engine/`의 규칙(`SootheReducer.kt`·`OfferReducer.kt`·`Calc.kt`)이 정합니다.
4. **차량 제어:** 상황에 따라 아기 자리 시트 열선 끄기, 형제가 있으면 뒷창 잠금, 실내가 더우면 앞 공조 낮추기, 정차 중 뒷좌석 독서등 끄기, 무드 조명이 켜져 있으면 달래는 동안 조명 낮추기를 합니다(명령 목록은 `engine/`의 `Command.kt`). 브레이크·방향지시등·큰 조향으로 운전이 바쁜 동안에는 급하지 않은 안내를 바로 말하지 않습니다.
5. **기록으로 소리 고르기:** 어떤 소리(백색소음, 자장가, 뱃속 소리, 부모가 녹음한 노래)를 틀었을 때 그쳤는지 판정이 난 1차 시도의 결과를 쌓아(아기 프로필 하나 기준), 다음에 틀 소리를 고릅니다(톰슨 샘플링을 쓰며, 규칙은 `engine/`의 `Calc.kt`·`SootheReducer.kt`). 리포트에는 "소리 덕분인지는 알 수 없어요"라고 함께 적습니다.
6. **말로 묻기:** "뭐가 제일 잘 통해?", "언제 먹였지?"처럼 운전자가 먼저 물으면 답합니다. 아기를 태우고 가는 동안에는 차가 안내를 마친 직후 5초 동안, 정차(기어 P) 중에는 홈의 '물어보기'를 누르면 5초 듣습니다. 듣는 조건은 `engine/`의 `Rules.kt`(`listenOk`·`canAsk`)와 `SpeechReducer.kt`에 있습니다. 자장가나 부모 노래를 트는 동안에는 가사를 질문으로 잘못 듣지 않도록 질문으로 받지 않습니다. 질문은 중계 서버를 거쳐 외부 언어 모델(LLM)이 주제를 고르고(질문 낱말로도 고름), 기록이나 차 상태(수유·잠·기저귀·울음·카시트 시간·실내 온도·다음 쉼터·소리 성적)를 묻는 질문에는 차가 값을 읽어 정해 둔 문장으로 답합니다. 그 밖의 질문은 LLM이 쓴 글이 검사(숫자·상태 단정·군말)를 통과할 때만 앞 두 문장을 말하고, LLM에 닿지 못하면 정해 둔 문장으로 답합니다(규칙은 `app/`의 `link/Qa.kt`). LLM에는 상태값과 질문 글자만 보냅니다(아이 별명은 '아기'로 바꿈).
7. **주행 중 화면 조작 없음:** 주행 제한(`CarUxRestrictions`)이 걸리면 아이곁 화면은 단추 없는 주행 전용 화면으로 바뀝니다. 차의 질문에는 말로 답합니다.
8. **휴대폰 앱:** 수유·잠·기저귀 기록, 다른 육아 앱 기록 가져오기(CSV), 부모 노래 녹음, 주행 리포트 보기, 인계 알림 받기.

울음의 원인을 맞히지 않고, 잠·호흡을 감지하지 않습니다.

## 실행 방법

1·2·4단계는 제출 APK(release)로 보는 순서이고(iOS 휴대폰 앱만 소스로 빌드), 3단계 시연 장면은 debug 빌드에만 들어 있습니다. 5단계는 소스 빌드와 시험입니다. 명령은 저장소 루트에서 실행하고, 확인한 환경은 macOS(Apple Silicon)입니다. `adb`·`emulator`가 PATH에 있어야 하고, 시연 스크립트는 bash·curl·python3를 씁니다(macOS의 python3는 Xcode Command Line Tools에 들어 있고, 없으면 `xcode-select --install`). 스크립트와 장면의 자세한 절차, 제출 APK로 울음 감지를 보는 마이크 순서, debug 순서는 [tools/demo/README.md](tools/demo/README.md)에 있습니다.

### 1. 에뮬레이터 준비

[공식 설치 문서](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator#setting-up-the-emulator)대로 AVD를 만듭니다. 개발·시험 환경은 시스템 이미지 API 34의 `Pleos Connect v2.1.0 APIs`(arm64-v8a), 기기 프로필 `Pleos Connect v2.1.0`(2560×1440), RAM 4GB, Cold boot입니다.

```bash
emulator -avd <AVD 이름> -timezone Asia/Seoul   # 기록을 한국 시간으로 남깁니다
adb devices                                       # 차 에뮬레이터 시리얼 확인(예: emulator-5554)
```

### 2. 설치와 첫 실행

```bash
SERIAL=emulator-5554 tools/demo/prepare.sh apk/곁빛_아이곁_release.apk
```

`prepare.sh`는 APK를 설치하고 운전자 사용자(user 10) 권한과 시연에 필요한 차량·앱 상태를 맞춥니다. 하는 일 전체는 [tools/demo/README.md](tools/demo/README.md#스크립트)에 있습니다.

1. 처음 실행하면 '아기 설정'(동의, 별명, 태어난 달, 카시트 자리, 수유 간격 등)을 마쳐야 홈이 나옵니다.
2. 정차 화면에서 '아기 탔어요'를 누릅니다. 시동 뒤 앱 화면이 없으면 Android가 마이크 서비스를 시작하게 두지 않아 출발 전에 누르게 했습니다([mictest_1102.txt](tools/pleos-probe/mictest_1102.txt)).
3. 짧게 달려 봅니다. 기어 D·30km/h로 25초 달린 뒤 정차하고 뒷문을 열었다 닫아 이 주행을 끝냅니다.

   ```bash
   SERIAL=emulator-5554 tools/demo/drive.sh
   ```

   달리는 동안 앱은 단추 없는 주행 화면으로 바뀝니다.
4. 울음 감지와 달래기는 따로 새로 달리면서 에뮬레이터 마이크에 울음 소리를 넣어 봅니다. 위 3번의 25초 주행은 판정(약 56초 재생 → 8초 듣기 → 30초 확인)까지 가지 못하므로, '아기 탔어요'를 다시 누르고 `drive.sh 180`으로 3분을 달립니다. 에뮬레이터가 맥의 오디오 입력을 받게 띄우고, 맥 입력을 가상 오디오 장치로 바꿔 그동안 울음 녹음을 틀면 경보 → 뒷좌석 재생 → 그쳤는지 판정까지 이어집니다. 순서는 [제출 APK로 울음 감지 보기](tools/demo/README.md#제출-apk로-울음-감지-보기)에 있습니다.

### 3. 시연 장면(debug 빌드)

장면은 개발·측정용 재연 기능이라 debug 빌드에만 들어 있습니다. Virtual Console로 기어·속도를 바꾸고, 마이크 자리에 울음 녹음을 넣어 같은 상황을 매번 똑같이 재현합니다. debug 빌드를 설치하는 순서는 [tools/demo/README.md](tools/demo/README.md#debug로-돌리기녹음-장면인계-알림로컬-중계-서버)에 있습니다. 차 앱 홈 → 오른쪽 위 '아기 설정' → 맨 아래 '시연·측정'에서 장면을 고르고, 반복 횟수를 '−'로 1에 맞춥니다. 그다음 아래 명령을 먼저 실행하고, '기다린다'가 찍히면 '실행하기'를 누릅니다. 장면이 끝나면 결과(회차마다 기대 조건을 채웠는지, 차량 제어 반영률)를 찍고 정차 상태로 되돌립니다.

```bash
SERIAL=emulator-5554 tools/demo/scene-result.sh
```

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

길이는 장면 파일에서 계산한 대략의 시간이고, 실제로는 이보다 짧거나 길 수 있습니다.

- **울음 녹음:** 녹음 장면(A·B·C·D·D2)은 표의 이름으로 준비한 울음 녹음을 `cry.sh`로 넣어야 돌아갑니다. 쓴 녹음은 배포 허락을 확인하지 못해 저장소에 넣지 않았습니다.
- **장면 C·C2:** 시작 전에 내비 길안내를 켜 두어야 합니다([장면 C·C2의 내비](tools/demo/README.md#장면-cc2의-내비)).

### 4. 휴대폰 앱과 중계 서버

제출 APK(release)는 배포한 중계 서버 `https://aigyeot-relay.byuncorry.workers.dev`에 붙으므로 로컬 중계 서버가 필요 없습니다. 휴대폰 앱도 release로 맞춥니다.

- **안드로이드:** 일반 안드로이드 에뮬레이터(API 26 이상, arm64-v8a 또는 x86_64)에 제출 APK를 설치합니다.

  ```bash
  adb -s <폰 에뮬레이터 시리얼> install -r apk/곁빛_아이곁_휴대폰_release.apk
  ```

- **iOS:** `phone/ios/Aigyeot.xcodeproj`를 Xcode로 열고, `Aigyeot` 스킴의 실행 구성(Edit Scheme → Run → Build Configuration)을 `Release`로 바꿔 iPhone 시뮬레이터에서 실행합니다.

1. **연결:** 차 앱 홈 오른쪽 위의 휴대폰 모양 단추('휴대폰 연결')를 누르면 QR과 짧은 코드가 나옵니다. 카메라가 없는 에뮬레이터·시뮬레이터에서는 휴대폰 앱에 코드를 넣습니다.
2. **리포트:** 연결한 뒤 '아기 탔어요'를 누르고 `drive.sh`로 짧게 달리면, 주행이 끝날 때 그 주행의 리포트가 휴대폰 앱에 옵니다(울음이 없었으면 '울음 기록 없음'). 장면이 만든 리포트는 차 앱에만 남고 휴대폰으로 보내지 않습니다.

인계 알림(장면 D·D2, 두 번째 휴대폰)과 로컬 중계 서버로 모두 debug로 돌리는 순서는 [tools/demo/README.md](tools/demo/README.md#debug로-돌리기녹음-장면인계-알림로컬-중계-서버)에 있습니다.

### 5. 소스 빌드와 시험

빌드 전에 [필요한 설정](#테스트-환경과-필요한-설정)의 `local.properties`와 `JAVA_HOME`을 맞춥니다.

```bash
./gradlew :app:assembleDebug     # 차 앱 debug → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # 차 앱 release(서명 키 설정이 없으면 서명 없이 만듦)
tools/check.sh                   # 시험 전부: engine·shared·app·phone(JVM), relay(typecheck·vitest), iOS(xcodebuild test)
```

`tools/check.sh`가 무엇을 건너뛰고 언제 실패로 멈추는지, iOS 시뮬레이터를 어떻게 고르는지(`IOS_SIM`)는 스크립트 머리 주석에 있습니다.

## 테스트 환경과 필요한 설정

| 구분 | 값 |
| --- | --- |
| 개발 맥 | macOS 27.0.1, Apple Silicon(arm64) |
| 차 앱 | compileSdk 37 / targetSdk 34 / minSdk 28, ABI arm64-v8a·x86_64 |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, SpeechToText 3.3.1, TextToSpeech 3.3.1 |
| 울음 감지 | MediaPipe tasks-audio 0.10.32(YAMNet) |
| 안드로이드 빌드 | Gradle 9.6.0, AGP 9.4.1, Kotlin 2.4.21. Gradle은 Android Studio 내장 JDK(OpenJDK 25.0.3)로 돌리고, 컴파일 대상은 Java 17 |
| 안드로이드 휴대폰 앱 | compileSdk 37 / targetSdk 36 / minSdk 26 |
| iOS 휴대폰 앱 | Xcode 27.2, 최소 iOS 17.0, XcodeGen 2.45.3 |
| 중계 서버 | Node.js v25.4.0에서 확인(wrangler 4.156.0은 22 이상, vitest 5.0.5는 22.12·24·26 이상을 적어 둠), Cloudflare Workers + Durable Objects |

나머지 라이브러리 버전은 `gradle/libs.versions.toml`, `phone/ios/project.yml`, `relay/package.json`에 있습니다.

**필요한 설정**
- **필수:** 루트 `local.properties`에 `sdk.dir=<Android SDK 경로>`(또는 환경 변수 `ANDROID_HOME`). `JAVA_HOME`이 없으면 Android Studio 내장 JDK를 지정합니다(macOS: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`). `tools/check.sh`는 `JAVA_HOME`이 없으면 이 JDK를 씁니다.
- **선택(로컬 중계 서버의 LLM 답·원격 푸시):** 실제 키가 있을 때만 `cp -n relay/dev.vars.example relay/.dev.vars` 뒤 `ANTHROPIC_API_KEY`와 `FCM_PROJECT_ID`·`FCM_CLIENT_EMAIL`·`FCM_PRIVATE_KEY`를 진짜 값으로 바꿉니다. 예시 값을 그대로 두면 가짜 키로 요청해 실제 호출이 실패합니다. 키가 없거나 중계 서버에 닿지 못하면 말로 묻기는 정해 둔 문장으로 답합니다.
- **선택(원격 푸시):** 안드로이드는 `phone/android/google-services.json`을 넣습니다. iOS는 `phone/ios/Aigyeot/GoogleService-Info.plist`에 넣은 뒤 앱 타깃에 들어가게 해야 합니다. `phone/ios`에서 `xcodegen generate`로 프로젝트를 다시 만들면(프로젝트는 `phone/ios/project.yml`에서 만듭니다) 이 파일이 `Aigyeot` 타깃 리소스에 들어갑니다. XcodeGen이 없으면 Xcode에서 이 파일을 `Aigyeot` 타깃에 추가합니다(File → Add Files, Targets에서 `Aigyeot` 선택). 파일이 없거나 iOS 앱 타깃에 들어가지 않으면 원격 푸시만 꺼집니다.
- **선택(release 서명):** `local.properties`에 `aigyeot.release.storeFile`·`storePassword`·`keyAlias`·`keyPassword` 넷이 모두 있으면 서명합니다. 키는 저장소에 두지 않습니다.

## Pleos SDK와 차량 신호를 쓰는 곳

| SDK·API | 아이곁에서 쓰는 곳 |
| --- | --- |
| Vehicle(읽기) | 뒷문 열림·닫힘으로 아기 탑승 세션 시작·끝, 시트 열선·공조 온도·창 잠금·조명 상태, 조향각·브레이크·방향지시등(리포트의 큰 조향 횟수, 운전이 바쁠 때 안내 미루기) |
| Vehicle(제어) | 아기 자리 시트 열선 끄기, 형제가 있으면 뒷창 잠금·풀기, 실내가 더우면 앞 공조 2℃ 낮춤, 정차 중 뒷좌석 독서등 끄기, 무드 조명 낮추기·되돌리기 |
| NaviHelper | 안내 중 여부, 남은 시간·거리, 경로 위 휴게소·졸음쉼터, 경유지 넣기 |
| TextToSpeech / SpeechToText | 운전자에게 하는 안내 / '응·아니', 운전자 질문, 멈춤 말('그만' 등). 둘 다 기기 안(ON_DEVICE) 모드 |
| AAOS `CarPropertyManager`·`CarPowerManager`·`CarUxRestrictions` | 기어·속도, 전원 정책·화면 꺼짐으로 시동 꺼짐·켜짐 짐작, 주행 제한 때 주행 화면으로 전환 |
| Android `AudioTrack`·`MediaPlayer` | 출력 장치를 뒷좌석 오디오 존으로 지정해 재생(백색소음은 `AudioTrack`, 그 밖의 소리는 `MediaPlayer`) |

권한 전체는 [AndroidManifest.xml](app/src/main/AndroidManifest.xml)에 있습니다. Pleos Playground의 SDK 사용 신청에서 Vehicle Control·TextToSpeech·SpeechToText는 아직 '준비 중'입니다. 아이곁은 공식 에뮬레이터에서 매니페스트 권한 선언으로 동작을 확인했고, 실차 동작과 Pleos App Market 심사 통과는 확인하지 않았습니다.

## 검증 결과

에뮬레이터 실측 중 경보 지연·내비 경유지·자원은 debug 빌드 차 앱으로, 뒷좌석 재생·주행 중 차량 제어는 별도 실측 앱([tools/pleos-probe](tools/pleos-probe/))으로 쟀습니다. 울음 감지와 그침 판정은 휴대폰·가정 녹음에 차 소음을 섞어 맥에서 평가했습니다. 조건과 전체 표는 [tools/measure/README.md](tools/measure/README.md)에 있습니다.

| 항목 | 결과 | 기록 |
| --- | --- | --- |
| 경보 지연(울음 입력 → 첫 달래기 재생 명령) | 에뮬레이터 장면 6회 중앙값 2,464ms, 최대 3,515ms | [perf_1108.txt](tools/measure/perf_1108.txt) |
| 뒷좌석 재생 | 출력 장치를 지정하면 뒷좌석 존 `bus100_audio_zone_1`으로 나감 | [rear_audio_1102.txt](tools/pleos-probe/rear_audio_1102.txt) |
| 주행 중 차량 제어 | 기어 D·60km/h에서 시트 열선·창 잠금·공조 온도·무드 조명 색 24/24 반영. 무드 조명 밝기는 따로 주행 6/6 | [babycare_Drive.txt](tools/sim/babycare_Drive.txt), [official_audit_1108.txt](tools/measure/official_audit_1108.txt) |
| 내비 경유지 | 앱이 넣은 휴게소가 실제 내비 경로에 '경유'로 들어옴 | [emulator_1110.txt](tools/measure/emulator_1110.txt) 4절 |
| 자원(3D 화면 포함) | CPU 한 코어 100% 기준 구간 평균 1.63~4.03%, 메모리(PSS) 최대 252MB | [demo_check_1112.txt](tools/measure/demo_check_1112.txt) 7절 |
| 울음 감지(교차검증, 울음 대 차 소음 비 10 dB) | 재현율 378/457(0.83). 오경보 수치와 조건은 [tools/measure/README.md](tools/measure/README.md#맥-오프라인-평가) | [tools/cry-retrain](tools/cry-retrain/) |
| 그침·계속 판정(학습에 쓰지 않은 울음 18개로 만든 맥 합성 장면 360개) | 소리를 멈추고 들을 때 359/360. 재생을 켠 채 판정하면 노래 장면에서 실제로 그친 72번 중 18번을 '아직 운다'로 잘못 봄 | [tools/cry-retrain](tools/cry-retrain/) |

## 알려진 한계

- **실제 효과:** 아기가 실제로 덜 우는지, 운전자가 덜 돌아보는지는 재지 않았습니다. 앱의 성적은 '그 소리를 틀었을 때 그친 횟수'이고, 소리 덕분인지는 알 수 없습니다.
- **감지 수치의 조건:** 차 안에서 녹음한 울음과 긴 녹음에서의 시간당 오경보는 재지 않았습니다.
- **에뮬레이터 소리:** 에뮬레이터 소리는 맥 스피커로 나와 앞·뒤로 나뉘어 들리는지는 확인할 수 없습니다. 실차의 오디오 존 구성은 확인하지 않았습니다.
- **원격 푸시:** 키를 넣지 않아 백그라운드에서 인계 알림이 실제로 도착하는지는 확인하지 않았습니다.
- **학습 데이터:** 비상업 조건 데이터가 들어 있어 울음 판별층은 공모전 시연용입니다. 상용 배포 전에는 상용 가능한 데이터로 다시 학습합니다.

전체 목록은 [tools/measure/README.md](tools/measure/README.md#알려진-한계와-확인하지-못한-것)에 있습니다.

## 저장소 구성

| 위치 | 내용 |
| --- | --- |
| `app/` | 차 앱. 울음 감지(`detect`), SDK 실행(`exec`), 부팅 때 뜨는 상주 서비스(`host`), 휴대폰 연결(`link`), 장면 재생(`scenario`, debug 빌드의 `app/src/debug/`에만), 기록(`store`), 화면(`ui`), 파일 위치(`common`) |
| `engine/` | 판단 코어. 세션·달래기·판정·인계 규칙을 담은 순수 Kotlin 상태 기계이고, JVM 시험으로 검증합니다 |
| `shared/` | 차·휴대폰·중계 서버가 함께 쓰는 메시지 형식과 JSON 예제 |
| `phone/android/`, `phone/ios/` | 안드로이드(Kotlin, Jetpack Compose)·iOS(SwiftUI) 휴대폰 앱 |
| `relay/` | 차·휴대폰 중계 서버(Cloudflare Workers + Durable Objects) |
| `tools/` | 시연 스크립트(`demo`), 실측 기록(`measure`), 울음 판별층 학습·평가(`cry-retrain`), 에뮬레이터 실측 앱(`pleos-probe`), 시뮬레이션(`sim`), 뱃속 소리 합성(`sound`), 전체 시험(`check.sh`) |
| `apk/` | 제출용 release APK |

## 출처와 라이선스

울음 판별 바탕 모델(YAMNet, MediaPipe — Apache 2.0), 서체(Pretendard — SIL OFL 1.1), 아이콘(Lucide — ISC, 일부 MIT), 주요 오픈소스 라이브러리, 자장가·뱃속 소리, 휴게소·졸음쉼터 공공데이터, 판별층 학습·평가 데이터의 출처와 라이선스는 [NOTICE.md](NOTICE.md)에 있습니다.
