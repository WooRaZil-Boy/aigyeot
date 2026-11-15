# README '실행 방법' 1·2·4단계(안드로이드) 따라 하기 — 2026-11-14 22:08~22:14
목적: 저장소 README.md '실행 방법'을 처음 보는 사람처럼 글자 그대로 따라 해, 제출 APK가 공식 Pleos Connect 에뮬레이터에서 설치·실행되고 앱이 보이는지 확인한다.
기준: HEAD f3cdca2 + 작업 트리의 README.md(커밋 전 수정본). 아래 'README n줄'은 이 수정본의 줄 번호다.
APK(빌드하지 않고 그대로 사용):
  apk/곁빛_아이곁_release.apk — 91,766,869 B, sha256 c8c953f03574ee544f75f894a2a867b8e319953c29ee532ddfa036ca0728f7e1
  apk/곁빛_아이곁_휴대폰_release.apk — 29,199,794 B, sha256 217a8737b81911f520297e049093d91aa75c03d336ee014cb9075b9e42da0290
환경: macOS 27(Apple Silicon). 차 AVD와 폰 AVD는 11-13 확인 때 쓴 AVD(PleosConnect·AigyeotPhone)의 설정을 본떠 새로 만든 AigyeotWalkCar·AigyeotWalkPhone(같은 시스템 이미지, 앱 데이터 없음).
11-13 기록과 다르게 한 것:
  - 새 AVD 두 개로 깨끗한 상태에서 시작했다(기존 AVD는 띄우지 않았다). 시리얼은 차 emulator-5554, 폰 emulator-5556(README 예시 5554와 같다. 11-13에는 5560이었다).
  - 두 에뮬레이터를 창 없이 띄웠다(`-no-window`, `-no-snapshot-save` 추가). 그 밖의 인자는 README 그대로(`-avd <이름> -timezone Asia/Seoul`).
  - 하지 않은 단계: 3단계(울음 감지, 맥 오디오 입력 장치 변경이 필요), 4단계의 iOS, 5단계(debug 장면), 소스 빌드.
  - 화면 확인은 캡처를 직접 보고, 글은 uiautomator 덤프로 읽었다. 아기 설정의 별명은 adb 입력이 한글을 넣지 못해 영문으로 넣었다.
  - 우회한 것은 없다. 막힌 곳이 없었다.
규칙: 확인만 했다(코드·스크립트·README 고치지 않음). 캡처는 tools/measure/screens_readme_1114/(git에 올라가지 않는다).

## 결과 한눈에
| 단계 | 내용 | 결과 |
| --- | --- | --- |
| 1 | 에뮬레이터 띄우기, adb devices | 통과(차 emulator-5554 device) |
| 1 | `-timezone Asia/Seoul`로 차 시계가 한국 시간이 됨 | README와 다름(첫 부팅에서만. 차는 America/Los_Angeles, 폰은 Asia/Seoul. prepare.sh가 맞추게 고쳤으나 첫 부팅에서는 유지되지 않아 11-15에 다시 고침, 7절) |
| 2-1 | prepare.sh로 제출 APK 설치·권한·기준 상태 | 통과(exit 0, 22초). 출력은 8단계 |
| 2-2 | '아기 설정' 마치기 → 홈 | 통과 |
| 2-3 | 정차 화면 '아기 탔어요' | 통과 |
| 2-4 | drive.sh: 단추 없는 주행 화면 | 통과 |
| 2-4 | drive.sh 뒤 정차 홈으로 돌아옴 | 통과 |
| 4-1 | 폰 APK 설치 | 통과(Success) |
| 4-2 | 차 휴대폰 단추로 코드 받기 → 폰에 코드 입력 → 연결(배포 중계) | 통과 |
| 4-3 | 연결 뒤 '아기 탔어요' → drive.sh → 폰에 리포트 '울음 기록 없음' | 통과(주행 끝 뒤 11초 안에 도착) |
| 3 | 울음 감지 | 하지 않음 |
| 4 | iOS | 하지 않음 |
| 5 | debug 장면 | 하지 않음 |
| 끝 | stop.sh, 에뮬레이터 끄기 | 통과 |

## 1. 에뮬레이터 준비 — 통과(시간대는 README와 다름)
- `emulator -avd AigyeotWalkCar -timezone Asia/Seoul -no-window -no-snapshot-save` → `adb devices`에 `emulator-5554 device`. adb·emulator는 이 맥의 기본 셸 PATH에 없어 SDK 폴더를 PATH에 넣었다(README 40줄은 PATH에 있어야 한다고 적는다).
- 부팅 완료(sys.boot_completed=1) 확인.
- 시간대: 차 화면 시계는 '05:10 오전', `getprop persist.sys.timezone`은 `America/Los_Angeles`, `date`는 `Sat Nov 14 05:13 PST`. 같은 때 폰은 `Asia/Seoul`(`22:13 KST`)이었다.
  README 45줄은 이 인자가 '기록을 한국 시간으로 남깁니다'라고 적지만, 갓 만든 차 AVD에서는 한국 시간이 되지 않았다. 이 기록에서 차가 남기는 시각(홈의 '지금 05:11' 등)은 차 시계 기준이다. 까닭은 `-no-window` 때문인지 가리지 않았다(아래 확인에서 첫 부팅에서만 어긋남).
  뒤이은 확인(같은 날 22:15): 같은 AVD를 같은 명령으로 다시 띄우니 Asia/Seoul이었다(첫 부팅에서만 America/Los_Angeles). `adb shell cmd alarm set-timezone Asia/Seoul`은 바로 먹었다(exit 0, `getprop persist.sys.timezone`이 Asia/Seoul, `date`가 KST). 그래서 `tools/demo/prepare.sh`가 이 명령으로 시간대를 맞추게 고쳤다(이 기록의 2단계는 고치기 전 스크립트로 돌린 것이다). 이 명령만으로는 첫 부팅에서 유지되지 않아 11-15에 다시 고쳤다(7절).

## 2. 제출 APK 설치와 첫 실행
### 2-1. prepare.sh — 통과
- `SERIAL=emulator-5554 tools/demo/prepare.sh apk/곁빛_아이곁_release.apk` 출력:
    [1/8] Pleos 에뮬레이터 확인: emulator-5554
    [2/8] 프로브 없음
    [3/8] 설치 확인(user 10): release 빌드, <APK 경로>
    [4/8] 권한 부여(RECORD_AUDIO, POST_NOTIFICATIONS, CAR_SPEED)
    [5/8] forward 8088, VC 세션 신호 유지 시작
    [6/8] 기준값: 기어 P, 정지, 뒷문 닫힘, 공조 전원 켬, 22도
    [7/8] 무드 조명 켜기, 밝기 70·흰색
    [8/8] 앱을 띄웠다.
  exit 0, 22초. 앱이 '아기 설정' 화면으로 떴다(01_after_prepare.png).

### 2-2. 아기 설정 — 통과
- 화면: 보호자 동의 체크, 아기 별명, 태어난 해·달(2026년 11월), 평소 수유 간격(안 씀), 카시트 자리(뒷좌석 오른쪽 선택), 뒷창 잠그기, 우리 노래도 틀기, 저장하기. README 55줄의 설명과 맞다.
- 동의 체크 → 별명 'baby' → 키보드 닫기 → '저장하기'. 저장 버튼은 동의 전에는 흐리고 체크 뒤 파랗게 켜졌다(02_setup_filled.png).
- 홈이 나왔다: '듣기 꺼짐 · 출발 전이에요', 마지막 수유 '기록 없음', '아기 탔어요', '지금 먹이기 시작했어요'(03_home_after_setup.png).

### 2-3. '아기 탔어요' — 통과
- 누르면 '듣기 켜짐 · 출발하면 듣기 시작해요', 아래 단추가 '물어보기'로 바뀜(04_after_board.png).

### 2-4. drive.sh — 통과
- `SERIAL=emulator-5554 tools/demo/drive.sh` 출력: '기어 D·30km/h로 25초 달린다(차 화면이 주행 화면으로 바뀐다).' / '정차하고 뒷문을 열었다 닫았다.' exit 0, 약 33초.
- 14초째(05_driving_14s.png): 계기판 D·30km/h, 앱은 귀 그림과 글 '아기 소리를 듣고 있어요'만 있는 주행 화면. 누를 수 있는 단추 없음. README 55줄의 '단추 없는 주행 화면'과 맞다. README는 이제 이 화면의 문구를 적지 않는다.
- 끝난 뒤(06_after_drive.png): 기어 P, 정차 홈 '출발 전이에요', '오늘 하루: 차에서 탑승 27초 · 울음 0번'.

## 4. 휴대폰 앱(안드로이드) — 통과
- `emulator -avd AigyeotWalkPhone -timezone Asia/Seoul -no-window -no-snapshot-save` → emulator-5556, 부팅 완료.
- `adb -s emulator-5556 install -r apk/곁빛_아이곁_휴대폰_release.apk` → 'Performing Streamed Install / Success'.
- 앱 첫 화면(12_phone_start.png): '차의 아이곁과 이 휴대폰을 연결해요', 오가는 정보 목록(수유 시작 시각·우리 노래·요약·리포트·인계 알림 / 울음소리와 차 위치·차 안 마이크 소리는 보내지 않음 / 중계 서버, Google·Apple 알림 서버 / 질문 때 AI 서버), '차와 연결하기', '먼저 기록만 쓸게요'.
- 차 홈 오른쪽 위 휴대폰 단추 → '휴대폰 연결' 화면에 QR, 6자리 코드, '남은 시간 4분 56초'(배포 중계에서 받음). 11_car_link_code.png. (코드 값은 적지 않는다.)
- 폰 '차와 연결하기' → '차와 연결'(QR 찍기 / 코드로 연결) → 코드 6자리 입력 → '확인' → 22:12 '연결됐어요'(14_phone_connected.png). 차: '휴대폰 연결됨', 연결된 휴대폰 1대 '함께 타는 보호자'(15_car_connected.png). 차 화면의 코드는 연결 뒤 새 코드로 바뀌었다.
- 폰 '시작하기' → 폰 홈. 차에서 뒤로 → 홈 상단 '휴대폰 1대'. '아기 탔어요'(22:12:48) → drive.sh(22:12:48~22:13:21, exit 0).
- 폰 '차' 탭(22:13:32, 끝난 뒤 11초): 리포트 칸에 '11월 14일(토) 오후 10:12–10:13 · 울음 기록 없음'(19_phone_car_tab.png). 글 깨짐 없음.
  차의 홈 표시와 폰의 리포트 시각은 서로 다른 시계(차 PST, 폰 KST)에서 나왔지만 폰 쪽 리포트 시각은 KST 22시대로 맞다.
- iOS는 하지 않았다.

## 5. 정리 — 통과
- `SERIAL=emulator-5554 tools/demo/stop.sh` → '세션 신호 유지와 forward(8088)를 지웠다.' exit 0.
- `adb -s emulator-5556 emu kill`, `adb -s emulator-5554 emu kill` → 둘 다 OK. 끝난 뒤 `adb devices` 비어 있음.
- 새 AVD 두 개(AigyeotWalkCar·AigyeotWalkPhone)는 지우지 않고 두었다.

## 6. README 문장과 실제가 다른 곳
1. README 45줄: `-timezone Asia/Seoul`이 '기록을 한국 시간으로 남깁니다' → 새 차 AVD는 America/Los_Angeles로 떠 차 시계가 05시대였다(폰은 KST). 첫 부팅에서만 그랬고(두 번째 부팅은 Asia/Seoul), prepare.sh가 시간대를 맞추게 고쳤으나 첫 부팅에서는 유지되지 않아 11-15에 다시 고쳤다(7절). 위 1절.
2. README 40줄: `adb`·`emulator`가 PATH에 있어야 한다 → 이 맥 기본 셸에는 없어 SDK 폴더를 PATH에 넣었다(안내와 일치하며 문제는 아니다).
3. README 49줄: prepare.sh 설명은 설치·권한·차량 상태까지만 말한다. 실제 출력은 8단계(무드 조명 켜기 포함)이고 문제는 아니다.

## 7. 뒤이은 확인(11-15): 부팅 직후 실행과 첫 부팅의 시간대
방법: 시험마다 새 차 AVD(PleosConnect 설정을 본뜸, 앱 데이터 없음)를 만들어 `emulator -avd <이름> -timezone Asia/Seoul -no-window -no-snapshot-save`로 띄우고, `adb devices`에 device로 처음 보이는 순간 `SERIAL=emulator-5554 tools/demo/prepare.sh apk/곁빛_아이곁_release.apk`를 쳤다.
### 재현(고치기 전 스크립트, 첫 부팅) — 둘 다 재현됨
- 부팅 직후 실행: 00:25:19 device로 보이자마자 실행(그때 `sys.boot_completed`는 빈 값). `[1/8]` 줄과 '시간대를 Asia/Seoul로 바꾸지 못했다' 뒤 '실패: 설치된 앱 목록을 읽지 못했다(adb 연결 확인)', 종료 코드 1(00:25:20).
- 부팅이 끝난 뒤(00:25:29) 다시 실행: [1/8]~[8/8] 끝까지 감(종료 코드 0). 실행 직전 `persist.sys.timezone`은 Asia/Seoul, `auto_time_zone`=1. 끝난 직후·10초·30초·120초 모두 `America/Los_Angeles`(`date` PST 07:25~07:27), `auto_time_zone`=1.
- 까닭: `dumpsys time_zone_detector`의 기록에서 부팅 18.5초째 위치 기반 감지(`mGeoDetectionSupported=true`)가 America/Los_Angeles를 제안해 시간대를 바꿨다(그 앞 9.6초째에는 에뮬레이터 -timezone이 보낸 Asia/Seoul이 적용돼 있었다). 자동 시간대가 켜져 있으면 `cmd alarm set-timezone`으로 맞춰도 다음 제안이 덮어쓴다.
- 부팅이 끝난 기기에서 `settings put global auto_time_zone 0` 뒤 `cmd alarm set-timezone Asia/Seoul`: 직후와 30초 뒤 Asia/Seoul 유지(이 확인은 18초 뒤라 첫 부팅 검증은 아님. 검증은 아래 B).
### 고친 것(`tools/demo/prepare.sh`)
- `[1/8]` 앞: `sys.boot_completed`=1, `pm path android` 성공, 현재 사용자 10, `pm list packages --user 10` 성공이 될 때까지 기다린다(처음 기다릴 때 한 줄을 찍고 최대 5분. 이미 부팅이 끝났으면 아무것도 찍지 않는다. 5분이 지나면 이유를 찍고 멈춘다).
- 시간대: `settings put global auto_time_zone 0`으로 자동 시간대를 끄고 `cmd alarm set-timezone Asia/Seoul` 뒤 `persist.sys.timezone`을 읽어 확인한다. 앱을 띄우기 직전에 한 번 더 읽고 다르면 다시 맞추며, 그래도 다르면 경고 한 줄(스크립트는 멈추지 않는다).
### 검증(새 AVD B의 첫 부팅, 고친 스크립트)
- V1 통과: 00:33:54 device로 처음 보임(`sys.boot_completed` 빈 값) → 바로 실행 → '에뮬레이터 부팅이 끝나기를 기다린다(최대 5분)' 한 줄 뒤 [1/8]~[8/8], 종료 코드 0(00:34:26, 32초).
- V2 통과: 끝난 직후(00:34:26)·10초·30초·120초·300초(00:39:27) 모두 `persist.sys.timezone`=Asia/Seoul, `date` KST(예: `Sun Nov 15 00:39:27 KST 2026`), `auto_time_zone`=0.
- V3 통과: 300초 뒤 캡처(`20_bootB_after_prepare.png`)에서 차 앱이 앞에 있고(`topResumedActivity`=kr.aigyeot.app/.MainActivity) '아기 설정' 화면이 깨지지 않았다. 차 시계 '12:39 오전'.
- V4 통과: 같은 AVD를 끄고 같은 명령으로 다시 띄운 두 번째 부팅에서 device로 보이자마자 실행 → 종료 코드 0(00:40:36), 기다리는 줄 한 줄(부팅 직후라 기다림), 0·10·30초 모두 Asia/Seoul. 부팅이 끝난 기기에서 다시 실행하면 기다리는 줄 없이 바로 [1/8]로 간다.
- V5 통과: `python3 tools/demo/selftest.py` → '시연 스크립트 자체 시험 통과'.
- 확인하지 못한 것: 폰 AVD(시간대가 맞게 뜸), 두 번째 부팅의 300초 뒤 시간대(30초까지만 읽음).
정리: 시험 AVD 두 개와 에뮬레이터를 지웠다. 기존 AVD는 건드리지 않았다.

