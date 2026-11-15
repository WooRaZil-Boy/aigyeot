# README '실행 방법' 1·2·4단계(안드로이드) 따라 하기 — 2026-11-15 21:35~21:46
목적: 최종 코드로 다시 만든 제출 APK가 README '실행 방법' 그대로 공식 Pleos Connect 에뮬레이터에서 설치·실행되고, 휴대폰 앱과 연결돼 리포트가 오는지 확인한다(11-14 기록 readme_walkthrough_1114.txt는 그 앞 APK로 한 것이다).
기준: HEAD 741107a(제출 APK를 다시 넣은 커밋). 두 APK 안의 빌드 정보(META-INF/version-control-info.textproto)의 revision은 0938894(741107a의 부모, 그 사이 바뀐 파일은 apk/ 두 개뿐).
APK(빌드하지 않고 그대로 사용):
  apk/곁빛_아이곁_release.apk — 91,766,869 B, sha256 b34e9de53a783fa3da5119f028d1d082c52107444e1cfe55ba2bab9f65838416
  apk/곁빛_아이곁_휴대폰_release.apk — 29,199,794 B, sha256 df1048abe240013bc8b9b18efdb516e6f9aab54be1a2315f4ae2d4441444fc49
  에뮬레이터에 설치된 base.apk를 꺼내 sha256을 대조했다: 두 앱 모두 위 값과 같다.
APK 정적 확인(같은 날 21:28):
  - apksigner verify 두 APK 통과, 서명 인증서 SHA-256 b7d40a77…(11-14 APK와 같은 인증서).
  - aapt2 badging: versionCode 1·versionName 0.1, 디버그 불가, 차 APK ABI arm64-v8a·x86_64.
  - 차 APK에 debug 소스셋의 장면 파일 27개 없음. debug 소스셋 클래스 35개 중 dex에 있는 것은 kr.aigyeot.app.ui.DemoEntry 하나이고, 이는 release 소스셋의 같은 이름 빈 구현(시연 꺼짐)이다.
환경: macOS 27(Apple Silicon). 차 AVD와 폰 AVD는 기존 PleosConnect·AigyeotPhone의 config.ini를 그대로 복사해 새로 만든 AVD 두 개(같은 시스템 이미지, 앱 데이터 없음, 첫 부팅).
11-14 기록과 같게·다르게 한 것:
  - 시리얼은 차 emulator-5554, 폰 emulator-5556. 두 에뮬레이터를 창 없이 띄웠다(`-port`, `-no-window`, `-no-snapshot-save` 추가). 그 밖의 인자는 README 그대로(`-avd <이름> -timezone Asia/Seoul`).
  - 하지 않은 단계: 3단계(울음 감지, 맥 오디오 입력 장치 변경이 필요), 4단계의 iOS, 5단계(debug 장면), 소스 빌드.
  - 화면 확인은 캡처를 직접 보고, 글은 uiautomator 덤프로 읽었다. 아기 설정의 별명은 adb 입력이 한글을 넣지 못해 영문으로 넣었다.
  - 우회한 것은 없다.
규칙: 확인만 했다(코드·스크립트 고치지 않음). 캡처는 tools/measure/screens_readme_1115/(git에 올라가지 않는다).

## 결과 한눈에
| 단계 | 내용 | 결과 |
| --- | --- | --- |
| 1 | 에뮬레이터 띄우기, adb devices | 통과(차 emulator-5554 device) |
| 1 | `-timezone Asia/Seoul`로 차 시계가 한국 시간이 됨 | 통과(prepare.sh 뒤 persist.sys.timezone=Asia/Seoul, date KST. 11-14의 첫 부팅 어긋남은 다시 나지 않음) |
| 2-1 | prepare.sh로 제출 APK 설치·권한·기준 상태 | 통과(exit 0, 부팅 기다림 포함 1분 45초) |
| 2-2 | '아기 설정' 마치기 → 홈 | 통과 |
| 2-3 | 정차 화면 '아기 탔어요' | 통과 |
| 2-4 | drive.sh: 단추 없는 주행 화면 | 통과 |
| 2-4 | drive.sh 뒤 정차 홈으로 돌아옴 | 통과 |
| 4-1 | 폰 APK 설치 | 통과(Success) |
| 4-2 | 차 휴대폰 단추로 코드 받기 → 폰에 코드 입력 → 연결(배포 중계) | 통과 |
| 4-3 | 연결 뒤 '아기 탔어요' → drive.sh → 폰에 리포트 '울음 기록 없음' | 통과(도착 시각은 재지 않음, 아래 4절) |
| 3 | 울음 감지 | 하지 않음 |
| 4 | iOS | 하지 않음 |
| 5 | debug 장면 | 하지 않음 |
| 끝 | stop.sh, 에뮬레이터 끄기, 시험 AVD 지우기 | 통과 |

## 1. 에뮬레이터 준비 — 통과
- 차·폰 에뮬레이터를 띄운 뒤(21:35:39) 바로 2단계 prepare.sh를 쳤다. prepare.sh가 '에뮬레이터 부팅이 끝나기를 기다린다(최대 5분)' 한 줄을 찍고 기다렸다.
- 폰: 부팅 완료 뒤 persist.sys.timezone=Asia/Seoul, `date` KST.

## 2. 제출 APK 설치와 첫 실행
### 2-1. prepare.sh — 통과
- `SERIAL=emulator-5554 tools/demo/prepare.sh apk/곁빛_아이곁_release.apk` 출력:
    에뮬레이터 부팅이 끝나기를 기다린다(최대 5분)
    [1/8] Pleos 에뮬레이터 확인: emulator-5554
    [2/8] 프로브 없음
    [3/8] 설치 확인(user 10): release 빌드, <APK 경로>
    [4/8] 권한 부여(RECORD_AUDIO, POST_NOTIFICATIONS, CAR_SPEED)
    [5/8] forward 8088, VC 세션 신호 유지 시작
    [6/8] 기준값: 기어 P, 정지, 뒷문 닫힘, 공조 전원 켬, 22도
    차량 설정이 사라졌다('무드 조명'을 찾는 중)
    다시: 차량 설정을 처음부터 다시 연다(2/3)
    [7/8] 무드 조명 켜기, 밝기 70·흰색
    [8/8] 앱을 띄웠다.
  exit 0(21:35:58~21:37:43). 무드 조명 단계에서 스크립트가 스스로 한 번 다시 열고 끝까지 갔다. 끝난 뒤 persist.sys.timezone=Asia/Seoul, `date` 'Sun Nov 15 21:37:44 KST 2026'.
- 앞 화면: topResumedActivity=u10 kr.aigyeot.app/.MainActivity, '아기 설정' 화면(01_after_prepare.png).

### 2-2. 아기 설정 — 통과
- 화면: 보호자 동의, 아기 별명, 태어난 해·달(2026년 11월), 평소 수유 간격(안 씀), 카시트 자리, 뒷창 잠그기, 우리 노래도 틀기, 저장하기.
- 동의 체크 → 별명 'baby' → 키보드의 확인 키로 키보드 닫기(02_setup_filled.png) → '저장하기'.
- 홈: '듣기 꺼짐 · 출발 전이에요', 마지막 수유 '기록 없음', '아기 탔어요', '지금 먹이기 시작했어요'(03_home_after_setup.png).

### 2-3. '아기 탔어요' — 통과
- 누르면 '듣기 켜짐 · 출발하면 듣기 시작해요', 아래 단추가 '물어보기'로 바뀜(04_after_board.png).

### 2-4. drive.sh — 통과
- `SERIAL=emulator-5554 tools/demo/drive.sh` 출력: '기어 D·30km/h로 25초 달린다(차 화면이 주행 화면으로 바뀐다).' / '정차하고 뒷문을 열었다 닫았다.' exit 0(21:39:34~21:40:07).
- 14초째(05_driving_14s.png): 계기판 D·30km/h, 앱은 귀 그림과 '아기 소리를 듣고 있어요'만 있는 주행 화면. 누를 수 있는 단추 없음.
- 끝난 뒤(06_after_drive.png): 기어 P, 정차 홈 '출발 전이에요', '오늘 하루: 차에서 탑승 27초 · 울음 0번', '아기 탔어요' 단추가 다시 나옴.
  눈썹은 '듣기 켜짐'이고 상태 표시줄 마이크 아이콘이 켜져 있었다(21:40:27에도 같음). 세션 밖에서 마이크가 돌고 있으면 '듣기 켜짐'으로 쓰는 규칙(app/…/ui/HomeUi.kt idleListening)대로다.

## 4. 휴대폰 앱(안드로이드) — 통과
- `adb -s emulator-5556 install -r apk/곁빛_아이곁_휴대폰_release.apk` → 'Performing Streamed Install / Success'.
- 앱 첫 화면(12_phone_start.png): '차의 아이곁과 이 휴대폰을 연결해요', 오가는 정보(수유 시작 시각·우리 노래·요약·주행 리포트·인계 알림 / 울음소리와 차 위치·차 안 마이크 소리는 보내지 않음 / 중계 서버, Google·Apple 알림 서버 / 차에서 물으면 AI 서버), '차와 연결하기', '먼저 기록만 쓸게요'.
- 차 홈 오른쪽 위 휴대폰 단추 → '휴대폰 연결' 화면에 QR, 6자리 코드, '남은 시간 4분 53초'(배포 중계에서 받음). 11_car_link_code.png. (코드 값은 적지 않는다.)
- 폰 '차와 연결하기' → '차와 연결'(QR 찍기 / 코드로 연결) → 코드 6자리 입력. 여섯째 숫자를 넣자 '확인'을 누르기 전에 바로 '연결됐어요'가 떴다(21:42:28, 14_phone_connected.png). 차: '휴대폰 연결됨', 연결된 휴대폰 '함께 타는 보호자'(15_car_connected.png).
- 폰 '시작하기' → 폰 '오늘' 화면. 차에서 뒤로 → 홈 상단 '휴대폰 1대'. '아기 탔어요'(21:43:05) → drive.sh(exit 0, 21:43:38에 끝).
- 폰 '차' 탭(19_phone_car_tab.png): '연결됨 · 1대', 리포트 칸에 '11월 15일(일) 오후 9:43 · 울음 기록 없음'. 글 깨짐 없음.
  처음 '차' 탭을 연 것은 21:45:45였다(그 전에 다른 칸을 잘못 눌러 늦어짐). 그때 리포트가 와 있었고, 도착한 시각 자체는 재지 않았다.
- iOS는 하지 않았다.

## 5. 정리 — 통과
- `SERIAL=emulator-5554 tools/demo/stop.sh` → '세션 신호 유지와 forward(8088)를 지웠다.' exit 0.
- `adb -s emulator-5556 emu kill`, `adb -s emulator-5554 emu kill` → 둘 다 OK. 시험용 AVD 두 개를 지웠다. 기존 AVD는 건드리지 않았다.

## 6. README 문장과 실제가 다른 곳
- 없음. (4단계 '코드를 휴대폰 앱에 입력합니다'는 맞다. 6자리를 넣으면 '확인' 없이 연결되는 것은 README가 적지 않은 편의 동작이다.)
