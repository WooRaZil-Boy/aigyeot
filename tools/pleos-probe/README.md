# Pleos 에뮬레이터 실측 프로브

아이곁에 쓸 API가 공식 Pleos 에뮬레이터에서 실제로 동작하는지 재는 시험용 앱이다(패키지 `dev.probe.pleos`). 앱 본체가 아니다. 아래 순서대로 하면 `tools/sim/`과 이 폴더의 실측 로그를 다시 만들 수 있다.

**주의: 다른 앱을 시험하기 전에 이 프로브를 먼저 지운다**(`adb uninstall dev.probe.pleos`). 설치되어 있으면 부팅할 때마다 마이크를 열어 보는 시험(`BootProbe.kt`)이 돌아서 다른 앱의 녹음과 로그에 영향을 줄 수 있다.

## 0. 에뮬레이터

- 이미지: `system-images/android-34/pleos_car_emulator/arm64-v8a`(Pleos 공식 에뮬레이터). 이 이미지로 AVD(예: `PleosProbe`)를 만든다.
- 실행:

```bash
$ANDROID_HOME/emulator/emulator -avd PleosProbe -no-snapshot -no-boot-anim -no-audio -gpu host
```

- 마이크 실측의 rms 기준값(마이크가 열리면 약 4.8, 시스템이 무음 처리하면 0.0)은 `-no-audio`로 띄운 상태에서 관찰한 값이다. 다른 오디오 옵션이면 달라질 수 있다.

## 1. 빌드

- 환경: compileSdk 36, targetSdk 34, minSdk 28, Gradle 9.6.0(래퍼 포함), AGP 9.4.1, JDK 17 이상(Android Studio 번들 JBR 가능). Pleos Vehicle 2.3.2, NaviHelper 3.0.5, SpeechToText·TextToSpeech 3.3.1, MediaPipe tasks-audio 0.10.14.
- 안드로이드 SDK 경로는 `local.properties`(`sdk.dir=…`, 저장소에는 없음)나 `ANDROID_HOME`으로 알려 준다.
- `./gradlew`는 Java 런타임이 있어야 돈다. JDK 17 이상이면 무엇이든 된다. 없으면 Android Studio 번들 JBR을 쓴다(아래는 macOS 예).

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd tools/pleos-probe
./gradlew :app:assembleDebug
# 산출물: app/build/outputs/apk/debug/app-debug.apk
```

## 2. 설치와 권한

운전자 사용자는 user 10이다. 앱이 런타임 권한을 묻지 않으므로, 새로 설치하면 마이크(RECORD_AUDIO)와 차량 속도(CAR_SPEED)가 꺼져 있다. 직접 준다.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant --user 10 dev.probe.pleos android.permission.RECORD_AUDIO
adb shell pm grant --user 10 dev.probe.pleos android.car.permission.CAR_SPEED
```

모든 화면은 user 10으로 띄운다: `adb shell am start --user 10 -n dev.probe.pleos/.<화면> --es …`. 같은 화면을 다시 시험할 때는 먼저 `adb shell am force-stop --user 10 dev.probe.pleos`와 `adb logcat -c`를 한다. 단 부팅 시험(8장) 직전에는 force-stop을 하지 않는다. 강제 종료된 앱은 stopped 상태로 돌아가 BOOT_COMPLETED를 받지 못한다.

## 3. 차량 입력(Virtual Console)

기어와 속도는 에뮬레이터의 Virtual Console(호스트 8088 포트)로 바꾼다. 세션 신호(heartbeat)를 계속 보내야 입력이 먹는다. 신호가 없으면 `{"error":"emulator_inactive"}`가 돌아온다.

```bash
adb forward tcp:8088 tcp:8088
# 세션 유지: 시험하는 동안 계속 돌려 둔다(끝나면 kill)
while true; do curl -s -X POST "http://127.0.0.1:8088/browser/session?client=probe" >/dev/null; sleep 1; done &
sleep 2   # 신호가 잡힐 때까지

curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Drive"     # 또는 Park
curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=60&accelerator=0&brake=0&steering=0"   # 정수만

curl http://127.0.0.1:8088/vehicle/state    # gear.actual.value, controls.speed_kph로 확인
```

- 장면 조건: 주행은 Drive·60km/h, 주차는 Park·0.
- 프로브가 읽는 속도(`PERF_VEHICLE_SPEED`)의 단위는 m/s다. 60km/h는 `16.666668`로 찍힌다.
- 에뮬레이터를 재부팅하면 Virtual Console 브리지가 다시 잡힐 때까지(`bridge_ready:true`) 기다린다.

## 4. 로그 보기

```bash
adb logcat -s SCENE MICTEST PROBE SOUND NAVI ActivityManager:W ActivityTaskManager:W
```

| 태그 | 쓰는 곳 |
| --- | --- |
| `SCENE` | `ScenarioProbe` 전 모드 |
| `SOUND` | `SoundProbe` |
| `NAVI` | `NaviProbe` |
| `MICTEST` | 마이크 시험(`ScenarioProbe mode=mic`, `MicService`, `BootProbe`) |
| `PROBE` | `MainActivity`·`AudioProbe` 화면과 `BootProbe`의 감시 서비스 |
| `ActivityManager`·`ActivityTaskManager`(W) | 백그라운드 서비스·활동 시작이 막힌 경고 |

## 5. 화면과 인자

### ScenarioProbe — `--es mode <모드>` (기본값 `hot`)

시작 1.5초 뒤 차량 API 능력(`CAP …`)을 조회하고, 4초 뒤 모드를 실행한다. 별도 인자는 `mic`의 `--es tag`뿐이다.

| 모드 | 하는 일 |
| --- | --- |
| `babycare` | 뒷좌석 열선·독서등·뒷창 잠금·앞 공조 온도·앰비언트 색을 6번씩 번갈아 바꿔 반영률과 시간을 잰다(`RES name i ack=… seen=… ok|FAIL`). 하이패스 안내 음량(`etcsVolume`)도 시도하지만 에뮬레이터는 지원하지 않는다(`ERR capEtcs … ERROR_NOT_SUPPORTED_PROPERTY`). 시작할 때 `STATE gear=… speed=…`로 기어·속도를 찍고, 뒷좌석 착석(`GET occupancy`), 뒷문 이벤트, 주행 시간을 기록한다. 끝나면 `RES done` |
| `drive` | 조향각·브레이크·속도 수신 이벤트 |
| `speed` | 표준 Car API로 차량 속도 읽기(CAR_SPEED) |
| `mic` | 화면에서 마이크 Foreground Service를 시작하고 화면을 닫는다. 녹음과 무음 처리(`isClientSilenced`) 기록. `--es tag 이름` |
| `rear` | 독서등·열선·창문을 뒷좌석에만 걸 수 있는지 |
| `belt` | 좌석별 착석·안전띠 수신, 뒷창 잠금·문 잠금 |
| `blind` | 뒷창 가림막 이동(뒤로 8초, 멈춤, 앞으로 8초) |
| `seat` | 시트 앞뒤·등받이·가림막 이동, 스마트글라스 지원 범위 조회 |
| `recline` | 등받이 위치 수신 |
| `fog` | 안개등·하향등·상향등 |
| `amb` | 앰비언트 구역별 색 |
| `hot`·`cold`·`emergency` | 찜통차(냉방 최대)·언 차(성에·열선)·비상 정차(트렁크·문 잠금 해제·타이어·에어백) |

`babycare` 재현:

```bash
# 3장의 Virtual Console을 켜 둔 상태에서
adb shell am force-stop --user 10 dev.probe.pleos; adb logcat -c
adb shell am start -W --user 10 -n dev.probe.pleos/.ScenarioProbe --es mode babycare
adb logcat -s SCENE:I        # 'RES done'이 나올 때까지(약 1분)
```

Drive(`gear=Drive`, `speed=60`)와 Park(`gear=Park`, `speed=0`)에서 각각 돌려 `tools/sim/babycare_Drive.txt`, `tools/sim/babycare_Park.txt`를 만들었다(`I SCENE :` 접두어는 뗐다). 로그의 `STATE gear=GEAR_DRIVE speed=16.666668`, `STATE gear=GEAR_PARK speed=0.0`이 조건이 실제로 걸렸다는 증거다.

- 뒷문은 열지 않았다. `EVT door …` 줄은 리스너를 걸 때 오는 초기값이다. 문 열림 반응은 이 로그로 알 수 없다.
- 뒷좌석 착석은 Seat API의 착석 능력(`checkSeatOccupancyCapabilities`) 영역 중 뒷줄(ROW_2)만 읽는다. 에뮬레이터 기본값은 모두 `true`(탑승)이다.

### SoundProbe — `--es cmd <명령>`

| 명령 | 인자 | 하는 일 |
| --- | --- | --- |
| `yamnet` | 없음 | 앱 files 폴더의 `*.pcm`을 모두 YAMNet으로 분류한다. 필요한 파일: `yamnet.tflite`. 울음 판별층은 `cry_head24_rt.json`을 먼저 찾고 없으면 `cry_head24.json`을 쓴다(없으면 `HEAD` 줄은 생략). 판별층 학습은 `tools/cry-retrain/rt_train.py`. 맥 계산값과의 대조 결과는 [`device_match_1102.txt`](device_match_1102.txt). 경보는 3초 구간 2개 연속으로 판별층 파일의 `threshold`(없으면 0.7) 이상 |
| `stt` | `--es file 이름` | files 폴더의 `이름.pcm`(16kHz mono s16le)을 Pleos STT(한국어)에 보낸다 |
| `sttmic` | 없음 | 차 마이크 경로로 STT |
| `tts` | `--es text 문장`(기본 문장 있음), `--ei zone 번호`(1이 뒷좌석 존) | Pleos TTS로 말하기 |
| `noise` | `--es bus 출력주소`, `--ei sec 초`(기본 3) | 백색소음을 재생하고 실제 경로(`routedDevice`)를 기록 |
| `priv` | 없음 | 화자 분리·통화 음성·TTS 존 지정 API가 일반 앱에 열려 있는지 |

### NaviProbe — `--es cmd <명령>`

`--ed lat 37.1 --ed lon 127.0 --es name 목적지`(`--ed`는 double)를 쓴다. 이미 떠 있으면 새 인텐트로 이어서 받는다(`singleTask`).

| 명령 | 인자 | 하는 일 |
| --- | --- | --- |
| `route` | `lat`, `lon`, `name`, 선택 `--es opt RECOMMENDED` 등 | 경로 요청 |
| `wp` | `lat`, `lon`, `name` | 경유지 추가 |
| `state` | 없음 | 경로 상태·TBT·현재 위치 조회 |
| `options` | 없음 | 내비의 경로 옵션 목록 화면 열기 |
| `opt` | `--es opt COMFORTABLE`(기본) | 경로 옵션 바꿔 재탐색 |

### 기타 화면

| 화면 | 하는 일 |
| --- | --- |
| `MainActivity`(런처) | 경고등·앰비언트·제어 쓰기와 화면 안 감시 루프(비 모드, 침수 대비) 시험. 값이 바뀔 때만 `PROBE` 로그 |
| `AudioProbe` | 차 마이크 입력과 차 스피커 재생이 3rd-party 앱에 열려 있는지(`PROBE MIC …`, `PROBE SPEAKER …`) |

## 6. 입력 파일을 앱 files 폴더에 넣기

user 10에서는 `adb push`로 앱 폴더에 직접 넣을 수 없다. `run-as`로 표준 입력을 흘려 넣는다(바이너리도 그대로 들어가는 것을 `cmp`로 확인했다). `run-as`는 앱 데이터 폴더(`/data/user/10/dev.probe.pleos`)에서 명령을 실행하므로 절대 경로와 상대 경로 모두 쓸 수 있다. 새로 설치한 앱에는 `files` 폴더가 아직 없으므로 아래 명령처럼 먼저 만든다.

```bash
D=/data/user/10/dev.probe.pleos/files
adb shell run-as dev.probe.pleos --user 10 mkdir -p $D   # 갓 설치한 앱에는 files 폴더가 아직 없다
adb shell "run-as dev.probe.pleos --user 10 sh -c 'cat > $D/yamnet.tflite'" < yamnet.tflite
adb shell "run-as dev.probe.pleos --user 10 sh -c 'cat > $D/axon_car10.pcm'" < axon_car10.pcm   # 16kHz mono s16le PCM면 무엇이든 된다
adb shell run-as dev.probe.pleos --user 10 ls -l files      # 확인
```

그 뒤 `--es cmd yamnet`, `--es cmd stt --es file axon_car10`처럼 부른다(`--es file`에는 `.pcm`을 뺀 이름).

`device_match_1102.txt`를 다시 만들 때는 [`tools/cry-retrain/mk_pcm.py`](../cry-retrain/mk_pcm.py)가 만든 PCM과 모델을 한 번에 넣는다. `S`는 mk_pcm에 준 데이터 폴더다.

```bash
D=/data/user/10/dev.probe.pleos/files
adb shell run-as dev.probe.pleos --user 10 mkdir -p $D   # 갓 설치한 앱에는 files 폴더가 아직 없다
for f in S/assets/rt/*.pcm S/dl/yamnet.tflite S/cry/cry_head24_rt.json; do adb shell "run-as dev.probe.pleos --user 10 sh -c 'cat > $D/$(basename $f)'" < $f; done
adb logcat -c
adb shell am start -W --user 10 -n dev.probe.pleos/.SoundProbe --es cmd yamnet
sleep 15   # 추론이 끝날 때까지
adb logcat -d -s SOUND:I
```
 지울 때는 앱을 지우면 files 폴더도 함께 사라진다.

## 7. 뒷좌석 존 재생 시험

앱이 만든 소리를 뒷좌석 존으로만 보낼 수 있는지 본다. 결과는 [`rear_audio_1102.txt`](rear_audio_1102.txt).

```bash
adb shell am force-stop --user 10 dev.probe.pleos; adb logcat -c
# 1) 출력 장치 주소를 먼저 본다: OUTS 줄에 bus100_audio_zone_1이 있는지
adb shell am start -W --user 10 -n dev.probe.pleos/.SoundProbe --es cmd noise --es bus bus100_audio_zone_1 --ei sec 3
sleep 4   # 재생과 기록이 끝날 때까지(너무 일찍 읽으면 줄이 비어 있다)
adb logcat -d -s SOUND:I
# 2) 비교: bus 없이
adb shell am force-stop --user 10 dev.probe.pleos; adb logcat -c
adb shell am start -W --user 10 -n dev.probe.pleos/.SoundProbe --es cmd noise --ei sec 3
sleep 4
adb logcat -d -s SOUND:I
```

볼 곳은 `NOISE preferred=true …`와 `NOISE routed=…`다. bus를 지정하면 `routed=bus100_audio_zone_1`, 지정하지 않으면 `routed=bus0_media_out`이어야 존 지정이 먹은 것이다. 에뮬레이터에서는 실제 소리가 아니라 라우팅 결과(로그)만 확인한다.

`bus100_audio_zone_1`이 뒷좌석 존 1 소속인지는 차량 서비스 덤프로 확인한다(읽기 전용).

```bash
adb shell dumpsys car_service | grep -E "CarAudioZone\(|MUSIC -> Address"
```

## 8. 부팅 시험(`BootProbe.kt`)과 결과

설치되어 있으면 **부팅할 때마다** 시동 뒤 화면 없이 마이크를 여는 다섯 경로(A~E)를 시험한다. 결과는 [`mictest_1102.txt`](mictest_1102.txt)에 있고, 다섯 경로 모두 막혔다. 경로 E(백그라운드 활동 실행)는 재부팅 2회차에서만 시험했다. 대조군(화면에서 시작)은 `--es mode mic`으로 같은 방식으로 돌린다.

**주의: 한 번도 실행하지 않은 새 설치 앱은 안드로이드의 stopped 상태여서 BOOT_COMPLETED를 받지 못한다. `am force-stop`도 앱을 stopped 상태로 되돌린다.** 그래서 순서는 이렇다.

1. 설치하고 권한을 준다(2장).
2. 대조군을 한 번 돌리고 `MICTEST fg-activity done`이 찍힐 때까지 기다린다(약 10초): `adb shell am start -W --user 10 -n dev.probe.pleos/.ScenarioProbe --es mode mic`
3. force-stop을 하지 않는다.
4. `adb reboot`
5. 앱 화면을 열지 않고 `adb wait-for-device logcat -s MICTEST PROBE ActivityManager:W ActivityTaskManager:W`를 건다(`adb reboot` 직후 시작한 `adb logcat`은 기기 연결이 끊기며 끝나 버린다). `BOOT received`는 로그를 걸고 약 50초 뒤, 경로 D는 그 80초 뒤, 경로 E는 100초 뒤에 나오므로 `MICTEST bg-activity-launch` 줄이 찍힐 때까지(`BOOT received` 뒤 약 100초, 재부팅부터 약 3분) 기다린다.
6. 끝나면 프로브를 지운다(`adb uninstall dev.probe.pleos`).

공개한 실측도 이렇게 했다. 대조군(`mode=mic`)을 02:18:53에 돌리고 곧바로 첫 재부팅을 했다([`mictest_1102.txt`](mictest_1102.txt) 참고).
