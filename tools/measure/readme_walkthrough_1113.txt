# README '실행 방법' 1~4단계 따라 하기 — 2026-11-13 08:36~09:25
참고: 이 기록 뒤(2026-11-13 저녁) release 빌드에서 시연·측정(장면 재생기·장면 파일)을 빼고 debug 빌드에만 두었다. 아래 제출 APK로 장면(C2·E·F14 등)을 돌린 절은 그 전 APK 기준이고, 지금 제출 APK에는 '시연·측정'이 없다.
목적: 저장소 README.md '실행 방법' 1~4단계를 처음 보는 사람처럼 글자 그대로 따라 실행해, 절차가 그대로 되는지 확인한다.
기준: HEAD 5d6131d + 작업 트리의 README.md(커밋 전 수정본). 아래 'README n줄'은 이 수정본의 줄 번호다.
APK: apk/곁빛_아이곁_release.apk(92,000,223 B, sha256 1032864fe7cd325e…), apk/곁빛_아이곁_휴대폰_release.apk(29,199,794 B, sha256 217a8737b81911f5…). 빌드하지 않았다.
환경: macOS 27.0.1(Apple Silicon), Android Emulator 37.1.11.0.
      차 AVD PleosConnect(Pleos Connect v2.1.0 APIs, API 34, arm64-v8a, 2560x1440, RAM 4GB, Cold boot 설정) → emulator-5560.
      폰 AVD AigyeotPhone(Android 16, API 36.1 google_apis_playstore, arm64-v8a, 1080x2400, 글자 크기 1.0) → emulator-5562.
      README 예시 시리얼은 emulator-5554지만 이 맥의 배정이 5560이라 명령의 SERIAL만 emulator-5560으로 바꿨다. 그 밖의 명령은 README 글자 그대로다.
      두 에뮬레이터 모두 앱을 지운 깨끗한 상태에서 시작했다(차 kr.aigyeot.app, 폰 kr.aigyeot.phone 삭제 뒤 설치).
맥 1분 부하: 시작 4.61, 장면 E 중 11.86, F14 중 4.98~20.34(다른 작업), 끝 7.78. 기다리지 않고 진행했다.
규칙: 확인만 했다(코드·스크립트·README 고치지 않음). 캡처는 tools/measure/screens_readme_1113/.
화면 판정: 화면 글은 접근성 덤프로 읽고, 캡처를 직접 보고 판정했다.

## 결과 한눈에
| 단계 | 내용 | 결과 |
| --- | --- | --- |
| 1 | 에뮬레이터 준비(emulator, adb devices, 부팅 완료) | 통과 |
| 2-1 | prepare.sh로 설치·권한·VC·기준 상태·무드 조명·앱 실행 | 통과(6/6, exit 0, 18.7초) |
| 2-2 | '아기 설정' 마치기 → 홈 | 통과 |
| 2-3 | 정차 화면 '아기 탔어요' | 통과 |
| 2-4 | drive.sh: 단추 없는 주행 화면 | 통과 |
| 2-4 | drive.sh: 주행 화면 문구 '아직 듣지 못하고 있어요' | README와 다름('아기 소리를 듣고 있어요'로 보임) |
| 2-4 | drive.sh 뒤 정차 홈으로 돌아옴 | 통과 |
| 3 | 장면 E(반복 1) | 통과 |
| 3 | 장면 F14-01~20(반복 1, 20개 전부) | 20/20 통과 |
| 3 | 장면 C2(내비 길안내 켠 뒤, 반복 1) | 통과 |
| 3 | 장면 사이 reset.sh(22번) | 22/22 exit 0 |
| 4-1 | 폰 APK 설치 | 통과(Success) |
| 4-2 | 차 '휴대폰 연결' 코드 → 폰 코드 입력 → 연결(배포 중계) | 통과 |
| 4-3 | 연결 뒤 '아기 탔어요' → drive.sh → 폰에 리포트 '울음 기록 없음' | 통과(주행 끝 뒤 11초 안에 도착) |
| 5 | stop.sh, 에뮬레이터 끄기 | 통과 |

## 1. 에뮬레이터 준비 — 통과
- 08:37:03 `emulator -avd PleosConnect -port 5560 -no-snapshot-save -timezone Asia/Seoul`
  (README 29줄대로 `adb`·`emulator`는 PATH에 있어야 한다. 이 맥의 기본 셸 PATH에는 없어 Android SDK의 platform-tools·emulator 폴더를 PATH에 넣고 실행했다.)
- 08:37:22 `adb devices` → `emulator-5560	device`, `sys.boot_completed`=1(에뮬레이터 로그 'Boot completed in 16642 ms', 게스트 uptime 0분 = 새로 부팅).
- `persist.sys.timezone`=Asia/Seoul, fingerprint `Pleos/crp_avd_arm64/…:14/RELEASE.release_connect_v2.1.0/…`, 현재 사용자 10.
- 앞서 깔려 있던 kr.aigyeot.app을 `adb -s emulator-5560 uninstall kr.aigyeot.app`로 지웠다(Success). 프로브(dev.probe.pleos)는 없었다.

## 2. 설치와 첫 실행
### 2-1. prepare.sh — 통과
- 08:37:38 `SERIAL=emulator-5560 tools/demo/prepare.sh apk/곁빛_아이곁_release.apk` 출력 전체:
    [1/6] Pleos 에뮬레이터 확인: emulator-5560
    [2/6] 프로브 없음
    [3/6] 설치 확인(user 10)
    [4/6] 권한 부여(RECORD_AUDIO, POST_NOTIFICATIONS, CAR_SPEED)
    [5/6] forward 8088, VC 세션 신호 유지 시작(pid 28290)
    [6/6] 기준값: 기어 P, 정지, 뒷문 닫힘, 공조 전원 켬, 22도
    앰비언트: 무드 조명 켜기, 밝기 70·흰색
    완료. 앱을 띄웠다.
  exit 0, 18.7초(08:37:56 끝). 갓 부팅한 직후였지만 무드 조명 단계에서 다시 시도는 없었다.
- 앱이 '아기 설정' 화면으로 떴다(01_after_prepare.png).

### 2-2. 아기 설정 — 통과
- 화면 항목: 보호자 동의 체크, 아기 별명, 태어난 해·달(2026년 11월), 평소 수유 간격(안 씀), 카시트 자리(뒷좌석 왼쪽·가운데·오른쪽, 오른쪽이 골라져 있음),
  뒷창 잠그기, 우리 노래도 틀기, 저장하기. README 48줄의 '동의, 별명, 태어난 달, 카시트 자리, 수유 간격 등'과 맞다.
- 동의 체크 → 별명 칸에 영문 별명(아무 값) → 키보드 닫기 → '저장하기'(나머지는 기본값). 08:38:40 홈('출발 전이에요', '듣기 꺼짐', '아기 탔어요', '지금 먹이기 시작했어요')이 나왔다. 03_home_after_setup.png
  · 별명은 adb 글자 입력이 한글을 넣지 못해 영문으로 넣었다(화면에는 한글 키보드가 뜬다). README 문제가 아니다.

### 2-3. '아기 탔어요' — 통과
- 08:38:48 누름 → '듣기 켜짐 · 출발하면 듣기 시작해요', 아래 단추가 '물어보기'로 바뀜. 오디오 녹음 기록: `src:MIC not silenced pack:kr.aigyeot.app`. 04_after_board.png

### 2-4. drive.sh — 단추 없는 주행 화면·정차 복귀는 통과, 주행 화면 문구는 README와 다름
- 08:38:59 `SERIAL=emulator-5560 tools/demo/drive.sh` 출력:
    기어 D·30km/h로 25초 달린다(차 화면이 주행 화면으로 바뀐다).
    정차하고 뒷문을 열었다 닫았다(세션 끝).
  exit 0, 31초.
- 14초째 화면(05_driving_14s.png): 계기판 D·30km/h, 앱은 단추 없는 주행 화면(귀 그림 + 글 한 줄, 누를 수 있는 것 없음).
  글은 '아기 소리를 듣고 있어요'였다. README 56줄은 '아직 듣지 못하고 있어요'로 보이는 것이 정상이라고 적는다 → README와 다름.
  · 앱 기록: 08:39:01 세션 LISTENING, 08:39:28 IDLE.
  · 이번에는 에뮬레이터 마이크 입력이 0만이 아니었다: 녹음이 'not silenced'였고, 장면 전 '시연·측정' 화면의 울음 확률 그래프에 마이크 입력으로 그린 선이 움직였다(10_E_selected_rep1.png).
    앱은 입력이 3초 넘게 0이거나 시스템이 녹음을 무음 처리할 때만 '아직 듣지 못하고 있어요'를 쓴다. 11-12 기록(demo_check_1112.txt 9절 6)은 '0만 들어옴'이었다.
    입력이 0이 아니게 된 까닭(맥 마이크 권한·입력 장치 상태 등)과, 들어온 것이 실제 맥 소리인지는 미판정.
- 끝난 뒤(06_after_drive.png): 기어 P, 정차 홈 '듣기 켜짐 · 출발 전이에요', '오늘 하루: 차에서 울음 0번', 탑승 27초 → 정차 홈 복귀 통과.
- 같은 문구는 4단계 두 번째 drive.sh(09:23:19)에서도 '아기 소리를 듣고 있어요'였다(40_drive2_14s.png).

## 3. 시연 장면
- 경로: 홈 → 오른쪽 위 '아기 설정'(톱니) → '시연·측정'. '시연·측정' 줄은 아기 설정 화면 맨 아래('우리 노래' 밑)에 있어 한 번 아래로 밀어야 보였다(08_settings_scrolled.png).
- 반복: 장면 E를 고르면 '반복 장면 기본'으로 보이고 '−'를 한 번 누르면 '반복 4'(E 파일의 기본 반복은 5). '−'를 네 번 눌러 '반복 1'로 맞췄다. F14·C2는 파일 기본이 1이다.
- 결과 표시: 화면에 '통과' 글은 없다. 진행 줄 'E 1/1번 · 390초'에 '어긋남 t=…'이 없고, 메모가 '결과 파일 · …/scenario-results/<장면>-….csv'로 바뀌면 끝이다.
  판정은 앱 로그(SCENARIO)의 결과 표 completed 칸으로 했다(true = 모든 기대값 충족, failed_step 비어 있음).
- 장면마다 끝난 뒤 `SERIAL=emulator-5560 tools/demo/reset.sh` → 매번 '기준값을 다시 맞추고 앱을 앞으로 가져왔다(공조 전원 true).' exit 0.

### 장면 E — 통과
- 08:41:21 실행 → 08:47:52 끝(390초). 화면 'E 1/1번 · 8초 · 연습 장면 실행 중'(11_E_start.png) → 주행 화면 '아기 소리를 듣고 있어요'(12_E_driving.png) → 끝.
- 결과 표: `1,5,true,,,,,false,0,0,0,0,0,0,0,,1.000` (completed true, control_ok_rate 1.000). 기대값(뒷창 잠금·도착 안내 S12·세션 닫기·수유 상태 전송·IDLE)을 모두 채웠다.
- 측정표: 보통 감지 지연 '—'(E 0/1번, 울음 없는 장면), 회차 기준 3.6초 이하, CPU 2.0%(끝난 직후 한 번은 12.4%), 메모리 189~191MB. 14_E_done.png
- 차 '주행 리포트'에 장면 리포트가 '울음 기록 없음 · 연습 장면'으로 쌓였다(27_car_reports.png).

### 장면 F14-01~20 — 20/20 통과
| 장면 | 실행 | 끝 | completed | 비고 |
| --- | --- | --- | --- | --- |
| F14-01 | 08:51:26 | 08:52:12 | true | |
| F14-02 | 08:52:43 | 08:53:30 | true | |
| F14-03 | 08:53:54 | 08:54:42 | true | |
| F14-04 | 08:55:03 | 08:55:51 | true | |
| F14-05 | 08:56:16 | 08:56:56 | true | 끝나면 D·40km/h로 남아 '아직 듣지 못하고 있어요'(세션 없는 주행) → reset.sh로 정차 |
| F14-06 | 08:57:20 | 08:58:01 | true | |
| F14-07 | 08:58:24 | 08:59:04 | true | 장면 안의 '프로필 없음'. 끝난 뒤 실제 아기 설정은 그대로 |
| F14-08 | 08:59:28 | 09:00:25 | true | |
| F14-09 | 09:00:54 | 09:01:41 | true | |
| F14-10 | 09:02:10 | 09:03:07 | true | |
| F14-11 | 09:03:36 | 09:05:12 | true | |
| F14-12 | 09:05:40 | 09:06:56 | true | |
| F14-13 | 09:07:24 | 09:08:10 | true | |
| F14-14 | 09:08:42 | 09:09:12 | true | |
| F14-15 | 09:09:44 | 09:10:31 | true | control_ok_rate 1.000(뒷창 잠금·되돌림) |
| F14-16 | 09:11:02 | 09:11:59 | true | |
| F14-17 | 09:12:31 | 09:13:18 | true | |
| F14-18 | 09:13:53 | 09:14:26 | true | |
| F14-19 | 09:15:01 | 09:16:33 | true | |
| F14-20 | 09:17:10 | 09:17:57 | true | |
- F14는 목록에 20줄로 따로 있고 한 번에 묶어 돌리는 단추는 없다. 하나씩 골라 실행했고, 20개에 reset.sh 포함 약 27분 걸렸다. 캡처 15_F14-*_done.png

### 장면 C2 — 통과(내비 길안내 준비 뒤)
- 준비(README 74줄 → tools/demo/README.md '장면 C·C2의 내비'): 09:18 앱 아래 줄의 내비 → '어디로 갈까요?' → `busan` 입력 → 제안 'busanstation' → '부산역 KTX 309km' 오른쪽 길안내 화살표
  → 경로 3개(추천 4시간 47분·378km) → '안내 시작' 카운트다운이 끝나게 둠 → 09:19:04 안내 화면(부산역 KTX 378km). 홈 단추 줄의 아이곁 아이콘으로 앱에 돌아옴. 20~24_navi_*.png
- 09:19:48 실행(반복 장면 기본 = 1) → 09:20:25 끝. 결과 표 `1,5,true,,,,,false,0,0,0,0,0,0,0,,`.
- 화면: 09:20:11 주행 화면 '아기 소리를 듣고 있어요' + '응 / 아니'(출발 제안 질문, 25_C2_mid.png) → 장면이 '응'을 넣음 → 내비가 앞으로 떠
  '안성휴게소(부산방향) 50분 · 47km' 경유지가 경로에 들어감(26_C2_mid2.png). 차 리포트 제어 기록 'addWaypoint tbt:rest:안성휴게소(부산방향) · 성공 1'.
- 끝 메모: '결과 파일 · … · 앱 화면이 없어 마이크를 다시 켜지 못함'(내비가 앞에 있어서. tools/demo/README.md에 적힌 동작). reset.sh가 앱을 앞으로 가져왔다.
- 내비의 부산역 길안내와 안성휴게소 경유지는 지우지 않고 4단계로 갔다.

## 4. 휴대폰 앱 — 통과
- 09:20:46 `emulator -avd AigyeotPhone -port 5562 -no-snapshot-save -timezone Asia/Seoul` → 09:21:11 boot_completed 1.
- 앞서 깔려 있던 kr.aigyeot.phone을 지우고(Success) 09:21:16 `adb -s emulator-5562 install -r apk/곁빛_아이곁_휴대폰_release.apk` → 'Performing Streamed Install / Success'. versionName 0.1, minSdk 26, targetSdk 36.
- 앱을 열면 첫 안내('차의 아이곁과 이 휴대폰을 연결해요', 오가는 정보 목록, '차와 연결하기', '먼저 기록만 쓸게요'). 30_phone_start.png
- 연결(README 89줄): 차 홈 오른쪽 위 휴대폰 단추 → '휴대폰 연결' 화면에 QR과 6자리 코드, '남은 시간 4분 55초'(배포 중계에서 코드를 받아 옴). 31_car_link_code.png
  폰 '차와 연결하기' → '차와 연결'(QR 찍기 / 코드로 연결) → 코드 6자리 입력 → 바로 확인 → 09:22:34 '연결됐어요'. 차: '휴대폰 연결됨', 연결된 휴대폰 1대 '함께 타는 보호자'. 32~35 캡처.
  (코드 값은 적지 않는다.)
- 연결 뒤 폰 '차' 탭: '연결됨 · 1대', 리포트 칸 '주행이 끝나면 여기에 리포트가 와요'(36_phone_car_tab_before.png). 그때까지 장면이 만든 리포트(차에는 '연습 장면'으로 쌓임)는 폰에 오지 않았다(README 90줄과 같음).
- 리포트(README 90줄): 차 홈 '아기 탔어요'(09:23:16) → 09:23:19 `SERIAL=emulator-5560 tools/demo/drive.sh`(exit 0, 09:23:49 끝).
  09:24:00 폰 '차' 탭 리포트 칸에 '11월 13일(금) 오전 9:23 · 울음 기록 없음'(주행 끝에서 11초 안). 42_phone_report.png
  상세: '11월 13일(금) 오전 9:23 · 0분', 감지까지 —, 울음 시간 0초, 차가 한 일 0가지, 정차 안내 0번, '기록된 울음이 없어요', 소리별 성적 안내 글, '비교할 구간이 없어요'. 글 깨짐 없음. 43_phone_report_detail.png
- iOS는 하지 않았다(범위 밖).

## 5. 정리 — 통과
- 09:24:20 `SERIAL=emulator-5560 tools/demo/stop.sh` → '세션 신호 유지와 forward(8088)를 지웠다.' exit 0(무드 조명 끄기 포함). forward 목록 비어 있음.
- 09:24:46 `adb -s emulator-5562 emu kill`, `adb -s emulator-5560 emu kill` → 둘 다 'OK: killing emulator, bye bye'. 끝난 뒤 `adb devices` 비어 있음.
- 환경 메모: 이전 작업에서 남은 Virtual Console 세션 신호 반복(client=probe, 11-08 시작)이 같은 포트 8088로 계속 보내고 있었다. 건드리지 않았다. 세션 신호가 하나 더 들어온 것이라 이번 결과에 준 영향은 없는 것으로 보지만 미판정.

## 6. README 문장과 실제가 다른 곳
1. README 56줄: drive.sh 중 주행 화면이 '아직 듣지 못하고 있어요'로 보이는 것이 정상 → 이번에는 두 번 다 '아기 소리를 듣고 있어요'.
   에뮬레이터 마이크 입력이 0만이 아니었기 때문(앱 판정 조건과 녹음 기록으로 확인). 입력 상태가 맥 환경에 따라 달라지는 것으로 보인다(까닭은 미판정).
   README 158줄 '맥 마이크를 기본 입력으로 둔 에뮬레이터에는 소리가 들어오지 않습니다'도 이번 환경에서는 '0이 아닌 입력이 들어왔다'(실제 맥 소리인지는 미판정).
2. README 60줄: '아기 설정' → '시연·측정' — '시연·측정'은 아기 설정 화면 맨 아래라 아래로 밀어야 보인다(문장에 없음, 큰 문제 아님).
3. README 60줄: '반복 횟수를 −로 1에 맞춥니다' — 장면 E는 '반복 장면 기본'(5)에서 '−'를 네 번 눌러야 1이 된다. 기본 숫자가 화면에 보이지 않아 한 번 누른 뒤에야 4로 보인다(문장과 어긋나지는 않음).
4. README 71줄 'F14-01~20' 한 줄 — 앱에서는 20개 장면이 따로이고 한 번에 돌릴 수 없다. 전부 보려면 20번 고르고 실행해야 한다(약 27분).
5. 장면 '통과·실패' 표시 — README에 적힌 것은 없지만, 화면에는 '통과' 글이 없고 결과 파일 경로만 보인다. 실패면 진행 줄에 '어긋남 t=…'이 붙는다.
그 밖의 문장(29·33·36~37·43·46·48·49·50·53·73·74·75·79·81·84·89·90줄)은 실제와 맞았다.

## 7. 확인하지 못한 것
- 장면 A·B·C·D·D2(울음 녹음이 필요해 제출 APK로는 못 봄, README 73줄): 실행하지 않음.
- 장면 E의 '꺼내 눕히기 안내'(S12) 말소리를 귀로 듣지는 않았다. 장면 기대값으로만 확인.
- 측정표 CPU가 장면 끝난 직후 한 번 12.4%로 보였다(E·F14-01). 순간 값이라 판정하지 않았다(미판정).
- 폰 리포트 상세의 '0분'(30초 주행)은 분 단위 표시로 보이며 판정하지 않았다.

## 재확인(스크립트 고친 뒤) — 2026-11-13 09:28~09:42
바뀐 것: prepare.sh 3/6 줄에 빌드 종류와 APK 경로를 찍음, drive.sh가 시작할 때 기준 상태(set_baseline: 기어 P·정지·브레이크 한 번 밟았다 뗌·뒷문 닫힘·공조)와 앱 앞으로 가져오기(app_front)를 먼저 함,
        README(장면은 늘 반복 1, 장면이 끝날 때마다 reset.sh, '시연·측정'은 아기 설정 맨 아래, 주행 화면 문구는 단정하지 않음).
환경: 차 AVD PleosConnect(emulator-5560, -no-snapshot-save -timezone Asia/Seoul)만. 09:28:51 띄움 → 09:29:08 boot_completed 1, 시간대 Asia/Seoul, 현재 사용자 10. 폰은 띄우지 않았다.
맥 1분 부하: 시작 4.27, 끝 11.91. 확인만 했다(코드·스크립트·README 고치지 않음). 캡처는 screens_readme_1113/r*.png.
b·c는 고친 drive.sh가 앞 장면이 남긴 상태를 되돌리는지 보려고 일부러 장면 뒤 reset.sh를 하지 않았다(README 60줄은 장면마다 reset.sh를 하라고 적는다).

| 단계 | 내용 | 결과 |
| --- | --- | --- |
| a | 앱 삭제 뒤 prepare.sh, 3/6 줄 | 통과. 'release 빌드'와 APK 경로가 찍힘, exit 0 |
| a | 아기 설정 다시 마치기 | 통과 |
| b | F14-05(반복 1)를 reset.sh 없이 실행 | 통과(completed true). 끝 상태 기어 D·40km/h, 단추 없는 주행 화면 |
| b | 끝난 뒤 '아기 탔어요' | 누르지 못함(화면이 주행 화면이라 단추가 없음) |
| b | drive.sh | exit 0. 시작 때 기어 P·0km/h로 되돌린 뒤 D·30km/h. 주행 화면은 세션 없는 주행('아직 듣지 못하고 있어요') |
| b | 끝난 뒤 | 기어 P·0km/h, 앱은 장면 전 화면('시연·측정')으로 돌아옴 |
| c | E(반복 1)를 reset.sh 없이 실행 | 통과(completed true, 제어 성공률 1.000). 끝 상태 기어 P·0km/h·브레이크 100 |
| c | 홈 '아기 탔어요' → drive.sh | 통과. exit 0, 세션 LISTENING → 주행 화면 → 세션 IDLE → 정차 홈 |
| d | stop.sh, 에뮬레이터 끄기, 잠금 파일 지우기 | 통과 |

### a. prepare.sh — 통과
- 앞서 깔려 있던 kr.aigyeot.app을 지움(Success). 09:29:11 `SERIAL=emulator-5560 tools/demo/prepare.sh apk/곁빛_아이곁_release.apk` 출력:
    [1/6] Pleos 에뮬레이터 확인: emulator-5560
    [2/6] 프로브 없음
    [3/6] 설치 확인(user 10): release 빌드, <저장소 절대 경로>/apk/곁빛_아이곁_release.apk
    [4/6] 권한 부여(RECORD_AUDIO, POST_NOTIFICATIONS, CAR_SPEED)
    [5/6] forward 8088, VC 세션 신호 유지 시작(pid 62854)
    [6/6] 기준값: 기어 P, 정지, 뒷문 닫힘, 공조 전원 켬, 22도
    앰비언트: 무드 조명 켜기, 밝기 70·흰색
    완료. 앱을 띄웠다.
  exit 0, 21초. 3/6 줄의 경로는 맥의 절대 경로로 찍힌다(여기서는 앞부분을 줄였다).
- 아기 설정: 동의 체크 → 영문 별명 → 저장하기 → 09:29:49 홈('듣기 꺼짐 · 출발 전이에요', '아기 탔어요'). r01_home_after_setup.png

### b. F14-05 뒤 reset.sh 없이 drive.sh
- 홈 → '아기 설정' → 맨 아래 '시연·측정' → F14-05('반복 장면 기본' = 1) → 09:30:29 실행 → 09:31:10 끝. 결과 표 `1,105,true,,,,,false,0,0,0,0,0,0,0,,` → 통과.
- 끝난 직후(09:31:13): Virtual Console 기어 Drive·40km/h·브레이크 0. 앱은 단추 없는 주행 화면 '아직 듣지 못하고 있어요'(세션 없는 주행). r_F14-05_end.png
- '아기 탔어요': 주행 화면이라 보이지 않았다. 뒤로 단추를 눌러도 주행 화면 그대로였다(r02_after_back.png). 누르지 못한 채 drive.sh로 갔다.
- 09:31:46 `SERIAL=emulator-5560 tools/demo/drive.sh`:
    기어 D·30km/h로 25초 달린다(차 화면이 주행 화면으로 바뀐다).
    정차하고 뒷문을 열었다 닫았다(세션 끝).
  exit 0, 09:32:18 끝.
- 차량 기록 순서: 09:31:47.389 기어 Park, 속도 0 → 09:31:49.610 기어 Drive → 09:31:49.621 속도 30 → 09:32:14.644 속도 0 → 09:32:14.653 기어 Park.
  시작 때 정차로 되돌린 것은 맞다. 정차는 약 2.2초뿐이고 스크립트가 멈추지 않아 그 사이에 '아기 탔어요'를 누를 틈은 없다.
- 14초째(r03_F14-05_drive_14s.png): 기어 D·30km/h, 단추 없는 주행 화면 '아직 듣지 못하고 있어요'. '아기 탔어요'를 못 눌러 세션이 열리지 않은 주행이다(세션 기록 없음).
- 끝난 뒤(r04_F14-05_drive_after.png): 기어 P·0km/h. 앱 화면은 정차 홈이 아니라 장면을 돌리던 '시연·측정' 화면이었다(주행 화면이 걷히고 그 전 화면이 나옴).
- 정리: 고친 drive.sh는 장면이 남긴 기어 D·속도를 되돌리고 exit 0으로 끝난다. 다만 기어 D로 끝난 장면 뒤에는 '아기 탔어요'를 누를 수 없어,
  세션이 열리는 주행(리포트가 생기는 주행)은 되지 않는다. 장면 뒤 reset.sh(README 60줄) → '아기 탔어요' → drive.sh 순서가 여전히 필요하다.

### c. E 뒤 reset.sh 없이 '아기 탔어요' → drive.sh — 통과
- 장면 E를 고르고 '−' 네 번으로 '반복 1' → 09:33:24 실행 → 09:39:56 끝(390초). 결과 표 `1,5,true,,,,,false,0,0,0,0,0,0,0,,1.000` → 통과.
- 끝난 직후: Virtual Console 기어 Park·0km/h·브레이크 100. 앱은 '시연·측정' 화면 'E 1/1번 · 390초 · 결과 파일 …'. r06_E_end.png
- 뒤로 두 번 → 홈 '듣기 꺼짐 · 출발 전이에요', '아기 탔어요' 보임(r07_home_after_E.png) → 09:40:25 누름 → '듣기 켜짐 · 출발하면 듣기 시작해요', 단추 '물어보기'.
- 09:40:28 `SERIAL=emulator-5560 tools/demo/drive.sh` → 같은 두 줄 출력, exit 0, 09:41:01 끝.
- 차량·앱 기록: 09:40:29.492 기어 Park → 09:40:30.649 앱이 받은 BRAKE_PEDAL_STATUS false(브레이크 한 번 밟았다 뗀 뒤) → 09:40:32.026 기어 Drive
  → 09:40:32.031 세션 LISTENING → 속도 30 → 09:40:57.110 기어 Park → 09:40:59.150 세션 IDLE. 끝난 뒤 Virtual Console 브레이크 0.
- 14초째(r08_E_drive_14s.png): 기어 D·30km/h, 단추 없는 주행 화면 '아기 소리를 듣고 있어요'.
- 끝난 뒤(r09_E_drive_after.png): 정차 홈 '듣기 켜짐 · 출발 전이에요', '오늘 하루: 차에서 울음 0번', '아기 탔어요'.

### d. 정리 — 통과
- 09:41:21 `SERIAL=emulator-5560 tools/demo/stop.sh` → '세션 신호 유지와 forward(8088)를 지웠다.' exit 0, forward 목록 비어 있음.
- `adb -s emulator-5560 emu kill` → 'OK: killing emulator, bye bye'. 끝난 뒤 `adb devices` 비어 있음. 앞 절의 오래된 세션 신호 반복(client=probe)은 그대로 두었다.

### 재확인에서 확인하지 못한 것
- drive.sh의 app_front(내비가 앞에 있을 때 앱을 앞으로)는 이번에 내비를 앞에 두지 않아 확인하지 않았다(미판정).
- c의 주행 화면 문구가 '아기 소리를 듣고 있어요'인 것은 이번 맥에서 마이크 입력이 0만이 아니었기 때문이다(1절과 같은 환경). 다른 맥에서의 문구는 미판정.

## debug 순서·녹음 장면·인계 알림·iOS 확인 — 2026-11-13 12:34~13:55
목적: 앞 절에서 실행하지 않은 절차를 문서 글자 그대로 따라 한다.
      (가) 제출 APK로 장면 E 뒤 README 63줄 로그 명령 그대로 보기, (나) iOS Release로 배포 중계 연결(README 92·94줄),
      (다) tools/demo/README.md 'debug로 돌리기' 절 전체, 녹음 장면 A·B·C·D·D2, 인계 알림(두 번째 휴대폰 iOS), 장면 리포트가 휴대폰으로 가지 않는지.
기준: HEAD 5d6131d + 작업 트리(커밋 전 수정본). 줄 번호는 이 시각의 README.md·tools/demo/README.md 기준.
환경: 차 AVD PleosConnect → emulator-5560(`-port 5560 -no-snapshot-save -timezone Asia/Seoul`), 폰 AVD AigyeotPhone → emulator-5562(같은 옵션),
      iPhone 17 Pro 시뮬레이터(iOS 27.0), Xcode 27.2, Node.js v25.4.0, npm 11.7.0. 문서 예시 시리얼 emulator-5554는 5560으로 바꿨다. adb·emulator는 Android SDK 폴더를 PATH에 넣고 썼다.
맥 1분 부하: 시작 5.96, 장면 E 중 6.64, iOS·Gradle 빌드 중 13.79~26.58(다른 작업 포함), 장면 A 시작 12.15, B 7.35, C 5.65, D 7.99, D2 6.28, 끝 13.83. 기다리지 않고 진행했다.
규칙: 확인만 했다(코드·스크립트·문서 고치지 않음). 캡처는 tools/measure/screens_readme_1113/(a*·b*·c*·p*·i*.png). 연결 코드·초대 코드 값은 적지 않는다.
      울음 녹음은 저장소 밖 로컬 파일(stop_40s·cont_75s·stop_50s·cont_long.pcm)을 썼다.
iOS 화면 조작: 이 Xcode에는 Simulator 앱 창이 없고 xcrun simctl에는 누르기·글자 입력이 없어, 저장소 밖에 만든 임시 XCTest UI 시험 앱으로 iOS 앱 단추를 누르고 글자를 넣었다(우회).
      설치·실행·지우기·캡처는 xcrun simctl로 했다. 임시 시험 앱은 끝에 시뮬레이터에서 지웠다.

### 결과 한눈에
| 단계 | 내용 | 결과 |
| --- | --- | --- |
| 가-1 | 차 앱 삭제 → prepare.sh(제출 APK) → 아기 설정 | 통과(6/6, exit 0, 26초) |
| 가-2 | 장면 E(반복 1) → README 63줄 `logcat -d -s SCENARIO` 그대로 → reset.sh | 통과. 결과 표(머리줄·회차 줄·summary 줄)가 그대로 보임 |
| 나 | iOS Release 빌드(명령) → 설치 → 차 release 코드로 연결 | 통과. RelayBaseURL 배포 주소, 배포 중계로 연결 |
| 다-1 | uninstall(차·안드로이드 폰·iOS) | 통과 |
| 다-2 | 로컬 중계 `npm ci && npm run dev` | 통과(8787 열림, 9초) |
| 다-3 | Gradle debug 빌드 | JAVA_HOME 없으면 실패 → README 127줄대로 지정 뒤 통과 |
| 다-3 | prepare.sh(인자 없음), cry.sh, 폰 debug 설치 | 통과(3/6 줄 'debug 빌드') |
| 다-4 | 차 아기 설정, 안드로이드 폰(debug) 연결(owner) | 통과 |
| 다-5 | iOS Debug 설치, 초대 코드로 두 번째 보호자 연결, '인계 알림 받기' 켜짐 | 통과 |
| 다-6 | 장면 A·B·C·D·D2(반복 1) | 5/5 completed true |
| 다-6 | 장면 길이(README 표) | A·C·E 맞음. B·D·D2는 표보다 14~28초 일찍 끝남 |
| 다-6 | D·D2 인계 알림: iOS에 알림·'오늘' 맨 위 카드, 안드로이드(처음 폰)에는 없음 | 통과 |
| 다-7 | 장면 리포트가 휴대폰으로 가지 않음 | 통과(차에만 5개, 두 폰 모두 없음) |
| 다-8 | stop.sh, 로컬 중계 끄기, 에뮬레이터·시뮬레이터 끄기 | 통과 |

### 가. 제출 APK 경로
- 12:34:30 차 에뮬레이터 → 12:34:49 boot_completed 1, 시간대 Asia/Seoul, 현재 사용자 10. 앞서 깔린 kr.aigyeot.app을 `adb -s emulator-5560 uninstall kr.aigyeot.app`로 지움(Success).
- 12:34:54 `SERIAL=emulator-5560 tools/demo/prepare.sh apk/곁빛_아이곁_release.apk`(README 43줄) → [1/6]~[6/6], '[3/6] 설치 확인(user 10): release 빌드, <저장소 절대 경로>/apk/곁빛_아이곁_release.apk',
  '앰비언트: 무드 조명 켜기, 밝기 70·흰색', '완료. 앱을 띄웠다.' exit 0, 26초.
- 아기 설정(README 48줄): 동의 체크 → 영문 별명 → 저장하기 → 12:35:58 홈('듣기 꺼짐', '출발 전이에요', '아기 탔어요'). a03_home.png
- 장면 E: 아기 설정 → 맨 아래 '시연·측정' → E → '−' 네 번 '반복 1'(a04_E_rep1.png) → 12:36:34 실행 → 12:43:08 결과 줄(화면 'E 1/1번 · 390초', 결과 파일 경로). 실행~결과 394초(표 약 6분 30초).
- 12:43:22 `adb -s emulator-5560 logcat -d -s SCENARIO`(README 63줄, 시리얼만 바꿈) → exit 0, 15줄. 끝 네 줄:
    I SCENARIO: 결과 파일 · /data/user/10/kr.aigyeot.app/files/scenario-results/E-20261113-1236.csv
    I SCENARIO: run,seed,completed,failed_step,alarm_latency_ms,alarm_to_play_ms,routed_device,fallback,max_attempts,max_episode_play_ms,session_play_ms,gain_violations,lufs_violations,system_volume_changes,driver_ops,handoff_ms,control_ok_rate
    I SCENARIO: 1,5,true,,,,,false,0,0,0,0,0,0,0,,1.000
    I SCENARIO: summary,,1/1,,,,,false,0,0,0,0,0,0,0,,1.000
  앞 11줄은 장면 진행(VC PUT 기어·속도, '시뮬레이션 입력 t=…'). 머리줄 세 번째 칸이 completed, 회차 줄 completed = true(README 60줄과 같음),
  summary 줄 세 번째 칸 '1/1'(tools/demo/README.md 18줄 '통과/전체'와 같음). 로그는 이 부팅 뒤 처음이라 표가 하나뿐이었다.
- 12:43:26 `SERIAL=emulator-5560 tools/demo/reset.sh` → '기준값을 다시 맞추고 앱을 앞으로 가져왔다(공조 전원 true).' exit 0.

### 나. iOS Release(README 84·92·94줄)
- Xcode 화면 조작(Edit Scheme → Run → Build Configuration)은 하지 않았다. 같은 일을 명령으로 했다:
  `xcodebuild -project phone/ios/Aigyeot.xcodeproj -scheme Aigyeot -configuration Release -destination "platform=iOS Simulator,id=<시연 시뮬레이터>" -derivedDataPath <임시 폴더> build`
  → 12:36:48~12:38:22 '** BUILD SUCCEEDED **'(1분 33초, 장면 E와 함께 돌림).
- 빌드된 앱 Info.plist: RelayBaseURL = https://aigyeot-relay.byuncorry.workers.dev(README 84줄과 같음), NSAppTransportSecurity 항목 없음(평문 허용 없음).
- 앞서 깔린 kr.aigyeot.phone을 `xcrun simctl uninstall`로 지우고 12:38:33 `simctl install`·`launch`.
  첫 화면은 첫 안내가 아니라 '연결이 끊겼어요 / 돌봄 기록은 이 휴대폰에 남아요 / 다시 연결하기 / 기록만 계속 쓸게요'였다(i01_ios_release_start.png).
  · 앱은 연결 정보를 iOS 키체인에 두고, 키체인 항목은 앱을 지워도 시뮬레이터에 남는다. 이 시뮬레이터의 앞선 연결 정보가 남아 있던 것으로 보인다(추측).
- '다시 연결하기' → 첫 안내('오가는 정보', '차와 연결하기') → '차와 연결하기' → '차와 연결'(QR 찍기 / 코드로 연결 칸 / 확인).
  차: 홈 오른쪽 위 '휴대폰 연결' → QR과 6자리 코드, '남은 시간 4분 55초'(a08_car_link_code.png).
  iOS 코드 칸에 6자리 입력(12:44:26) → '확인'을 누르기 전에 바로 연결되어 '오늘' 탭 '연결됐어요'(i04_ios_release_after_code.png).
  차: '휴대폰 연결됨 / 연결됐어요', 연결된 휴대폰 1대 '함께 타는 보호자 · 마지막 연락 11월 13일(금) 오후 12:44'(a09_car_linked_ios.png).
- 배포 중계인지: 이때 맥에서 로컬 중계(8787)는 열려 있지 않았고, iOS 앱 프로세스의 TCP 연결은 배포 주소(workers.dev)를 DNS로 풀어 나온 주소의 443 포트 하나(ESTABLISHED)였다 → 통과.
- 이 iOS Release 연결로 리포트(README 95줄 drive.sh)는 보지 않았다(앞 절에서 안드로이드로 확인).

### 다. debug로 돌리기(tools/demo/README.md 32~58줄)
1) 지우기(37·38줄) — 12:45:32
- `adb -s emulator-5560 uninstall kr.aigyeot.app` → Success, `adb -s emulator-5562 uninstall kr.aigyeot.phone` → Success(폰 에뮬레이터 12:45:02 띄움, 12:45:32 부팅 완료).
- iOS: `xcrun simctl uninstall <시연 시뮬레이터> kr.aigyeot.phone` → 0.

2) 터미널 1(45줄) — 통과
- 12:45:45 `(cd relay && npm ci && npm run dev)`를 백그라운드로 → 'added 76 packages, and audited 77 packages in 6s', 'found 0 vulnerabilities'.
  경고: 'npm warn EBADENGINE Unsupported engine … vitest@5.0.5 … required: ^22.12.0 || ^24.0.0 || >=26.0.0 … current: v25.4.0'(README 122줄에 적힌 그대로).
- 'wrangler 4.156.0', 'Ready on http://localhost:8787' → 12:45:54 127.0.0.1:8787·[::1]:8787 LISTEN. 이 맥에는 relay/.dev.vars가 있어 wrangler가 그 값을 읽었다(이번 장면들은 말로 묻기를 쓰지 않는다).

3) 터미널 2(50~53줄)
- 12:46:05 `./gradlew :app:assembleDebug :phone:assembleDebug` → 'The operation couldn't be completed. Unable to locate a Java Runtime.' exit 1(이 셸에 JAVA_HOME 없음, java 없음).
  README 127줄·tools/demo/README.md 41줄대로 `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` 뒤 다시 → 'BUILD SUCCESSFUL in 8s'(79 tasks, 8 executed. 앞선 빌드 산출물이 있었다).
  app-debug.apk 105,403,479 B, phone-debug.apk 35,829,537 B.
- 12:46:27 `SERIAL=emulator-5560 tools/demo/prepare.sh` → '[3/6] 설치 확인(user 10): debug 빌드, <저장소 절대 경로>/app/build/outputs/apk/debug/app-debug.apk', 6/6, exit 0, 19초.
- 12:46:54 `SERIAL=emulator-5560 tools/demo/cry.sh <녹음 폴더>/*.pcm` → '넣음: cont_75s.pcm (2384640바이트)', 'cont_long.pcm (2384640바이트)', 'stop_40s.pcm (1280000바이트)', 'stop_50s.pcm (1600000바이트)', exit 0.
- `adb -s emulator-5562 install -r phone/android/build/outputs/apk/debug/phone-debug.apk` → 'Performing Streamed Install / Success'.

4) 차 아기 설정·안드로이드 폰 연결 — 통과
- 차: 앱 삭제 뒤라 '아기 설정'부터 → 동의·영문 별명·저장하기 → 12:47:37 홈(b01_car_home_debug.png).
- 차 '휴대폰 연결' → 6자리 코드. 중계 기록: 'POST /v1/pair-codes 201', 'GET /v1/ws 101'(차가 로컬 중계 10.0.2.2:8787에 붙음, 56줄).
- 폰: 첫 안내 '차의 아이곁과 이 휴대폰을 연결해요' → '차와 연결하기' → 코드 칸에 6자리 → 12:49:01 '연결됐어요 / 수유 시작과 우리 노래를 차로 보낼 수 있어요' → '시작하기' → '오늘' 탭. 중계 'POST /v1/pair 201'. p01~p03.
- 폰 앱 알림 권한(POST_NOTIFICATIONS)은 이 흐름에서 묻지 않아 granted=false였다(처음 폰은 인계 알림 대상이 아니라 그대로 두었다).

5) iOS Debug, 두 번째 보호자 — 통과
- 12:49:41~12:50:32 `xcodebuild … -configuration Debug … build` → BUILD SUCCEEDED. Info.plist RelayBaseURL = http://localhost:8787, NSAllowsLocalNetworking = true(56줄과 같음).
  스킴 파일의 실행(LaunchAction) 기본 구성은 Debug(56줄 '기본값'과 같음).
- `simctl install`·`launch` → 이번에도 '연결이 끊겼어요'(Release 때 연결 정보가 키체인에 남아 있던 것으로 보임, 추측). '다시 연결하기' → '차와 연결하기'로 같은 연결 화면에 갔다.
- 안드로이드 폰 설정 탭 → '보호자'(1명) → '다른 보호자 초대 / 리포트와 인계 알림을 같이 받아요 / 코드는 24시간 동안 한 번만 쓸 수 있어요' → '초대 코드 만들기'
  → 12:51:30 초대 코드(영문·숫자 5-5자리)와 '11월 14일 오후 12:51까지', 초대 QR, '링크로 보내기'(p06_phone_invite_code.png). 중계 'POST /v1/invites 201'.
- iOS 코드 칸에 초대 코드 입력 → '확인' → 12:51:57 '연결됐어요', '오늘' 탭 '아직 받은 리포트·인계 알림이 없어요'. 중계 'POST /v1/pair 201'.
  iOS 알림 권한 창 '‘아이곁’에서 알림을 보내고자 합니다.'가 떠 '허용'을 눌렀다.
- iOS 설정 → '보호자': '인계 알림 받기'(아기가 계속 울면 알려요 / 리포트는 알림을 꺼도 받아요) 스위치 켜짐(값 1). 따로 켜지 않았다(i14_ios_guardians.png).
- 차 '휴대폰 연결' 목록: '연결된 휴대폰 2대', '함께 타는 보호자'(안드로이드), '다른 보호자'(iOS)(b03_car_two_phones.png). 차 홈 위에 '휴대폰 2대'.

6) 장면(반복 1). 장면마다 결과 표를 장면 혼자 보도록 실행 직전에 `adb -s emulator-5560 logcat -c`로 로그를 비웠다(문서에 없는 보조 단계).
   결과는 `adb -s emulator-5560 logcat -d -s SCENARIO`의 회차 줄. 장면 뒤 매번 reset.sh → exit 0, '…(공조 전원 true).'
   '−'를 네 번 눌러 '반복 1'(장면 파일 기본 반복 5). 시각은 '실행하기'를 누른 때 ~ 로그 결과 줄.
| 장면 | 실행 ~ 결과 | 걸린 시간 / 화면 | README 표 길이 | 회차 줄(run,seed,completed,…) |
| --- | --- | --- | --- | --- |
| A | 12:53:49 ~ 13:14:26 | 20분 37초 / 'A 1/1번 · 1230초' | 약 20분 30초 | 1,1,true,,2447,1303,bus100_audio_zone_1,false,1,54699,54699,0,0,0,0,, |
| B | 13:15:33 ~ 13:23:12 | 7분 39초 / 456초 | 약 8분 | 1,2,true,,2491,46,bus100_audio_zone_1,false,2,111919,111919,0,0,0,0,,1.000 |
| C | 13:27:58 ~ 13:31:01 | 3분 3초 / 180초 | 약 3분 | 1,3,true,,2554,133,bus100_audio_zone_1,false,1,55870,55870,0,0,0,0,, |
| D | 13:31:49 ~ 13:41:55 | 10분 6초 / 602초 | 약 10분 30초 | 1,4,true,,1593,52,bus100_audio_zone_1,false,2,111910,111910,0,0,0,0,118951, |
| D2 | 13:42:35 ~ 13:52:41 | 10분 6초 / 602초 | 약 10분 30초 | 1,4,true,,1609,50,bus100_audio_zone_1,false,2,111902,111902,0,0,0,0,119082, |
- 다섯 장면 모두 summary 줄 세 번째 칸 '1/1'. 측정표 보통 감지 지연: A 2.4초, B 2.5초, C 2.6초, D 1.6초, D2 1.7초. CPU 1.5~3.0%, 메모리 185~235MB.
- 길이: A·C는 표와 맞다(몇 초 더). B·D·D2는 표보다 14~28초 일찍 끝났다. 장면 파일 마지막 기대값이 'LISTENING'을 'by'초 안에 보는 것이라(B 450+20초, D·D2 600+30초),
  그 상태가 마감 전에 오면 바로 끝나는 것으로 보인다(추측). README 77줄 '실제로는 몇 초 더 걸립니다'와 다르다.
- 장면이 끝난 뒤 화면: A는 기어 P·정차, '시연·측정'에 결과. B·D·D2는 기어 D·단추 없는 주행 화면('아직 듣지 못하고 있어요')이 남아 화면에서는 결과가 보이지 않았다
  → reset.sh 뒤 '시연·측정'에 결과가 보였다(README 60줄·tools/demo/README.md 22줄과 같음). C는 내비가 앞에 남았다.

장면 C(28~30줄) — 통과
- 준비 13:25~13:26: 화면 아래 줄 내비 단추 → '어디로 갈까요?' → `busan` → 'busanstation' → '부산역 KTX KTX정차역 309km' 오른쪽 화살표 → 경로 3개(추천 5시간 1분·378km)
  → '안내 시작' 카운트다운이 끝나게 둠 → 13:26:44 안내 화면(5시간 1분·378km·부산역 KTX). c01~c05.
- 아래 줄 아이곁 단추로 돌아오니 '시연·측정'이 아니라 홈이 나와, 다시 '아기 설정' → '시연·측정' → C로 갔다.
- 13:29:00 로그 'Audio(file=cry/stop_50s.pcm…)', 'Say(intent=YES, method=null, onAsk=S07, bySec=45.0)'(26줄 '응을 장면이 직접 넣음'과 같음).
  13:29:36 캡처에 내비가 앞으로 떠 '부산역 KTX · 경유지 1개 포함' 경로 화면, 13:29:41 내비 기록 'onWayPointInfo', 13:30:03 안내 화면 '안성휴게소(부산방향) 47분 · 47km'(b31·b32).
- 결과 줄 메모: '결과 파일 · …/C-20261113-1328.csv · 앱 화면이 없어 마이크를 다시 켜지 못함'(29줄과 같음). reset.sh가 앱을 앞으로 가져왔다.
- C·C2를 다시 하지 않아 경유지 지우기(30줄)는 하지 않았다. 내비 길안내와 경유지는 남긴 채 D·D2로 갔다.

장면 D·D2의 인계 알림(58줄) — 통과
- 두 휴대폰 모두 앱을 연 채 '오늘' 탭에 두었다. 원격 푸시 키(google-services.json·GoogleService-Info.plist)는 넣지 않았다.
- D: 차 기록 13:34:53.033 세션 HANDOFF. 13:35:04 iOS 화면 위에 알림 배너 '인계 알림'(본문 일부가 상태 줄에 가림), '오늘' 탭 맨 위 카드
  '차에서 · 방금 / 아기가 계속 울어서 옥천만남휴게소에 들르자고 운전자에게 제안했어요.'(i21_D_now.png, i22_D_today_card.png).
  13:35:13 안드로이드(처음 폰) '오늘' 탭 맨 위는 '마지막 수유'(카드 없음), 알림 목록에 kr.aigyeot.phone 알림 0개(p20).
- D2: 차 기록 13:45:38.146 HANDOFF. 13:45:44 iOS 배너 '아이곁 · 지금 / 인계 알림 / 아기가 계속 울어요. 6분 뒤 도착해요.', 맨 위 카드 '차에서 · 방금 / 아기가 계속 울어요. 6분 뒤 도착해요.',
  그 아래 D 카드('차에서 · 10분 전')(i30_D2_1.png). 13:46:06 안드로이드 카드 없음, 알림 0개(p21).
- 차 HANDOFF에서 iOS 화면에 보인 때까지 D 11초 안, D2 6초 안(캡처 간격 기준, 실제 도착 시각은 미판정). 결과 표 handoff_ms: D 118951, D2 119082.

7) 장면 리포트(README 95줄, tools/demo/README.md 21줄) — 통과
- 차 '주행 리포트'에 장면 다섯 개가 '울음 1번 · 연습 장면'으로 쌓였다(12:54–1:14, 1:15–1:23, 1:28–1:31, 1:32–1:41, 1:42–1:52)(b52_car_reports.png).
- 장면 A 뒤와 모든 장면 뒤: 안드로이드 '차' 탭 리포트 칸 '주행이 끝나면 여기에 리포트가 와요'(p11·p30), iOS '오늘' 탭에는 인계 카드 둘뿐이고 리포트 줄 없음, '오늘 하루: 수유 0번, 차에서 울음 0번'(i40·i41).

8) 정리 — 통과
- 13:54:11 `SERIAL=emulator-5560 tools/demo/stop.sh` → '세션 신호 유지와 forward(8088)를 지웠다.' exit 0, forward 목록 비어 있음.
- 로컬 중계(wrangler·workerd)를 끄고 8787이 닫힌 것을 확인. 임시 iOS UI 시험 앱을 지움.
- 13:54:56 `adb -s emulator-5562 emu kill`, `adb -s emulator-5560 emu kill` → 둘 다 'OK: killing emulator, bye bye'. `xcrun simctl shutdown` → 0. `adb devices` 비어 있음.
- 차·폰·iOS에 debug 앱이 깔린 채로 끝났다(다음 제출 APK 확인 때는 지우고 설치해야 한다, tools/demo/README.md 34줄).

### 문서 문장과 실제가 다른 곳
1. README 77줄 '실제로는 몇 초 더 걸립니다': B(456초, 표 약 8분)·D·D2(602초, 표 약 10분 30초)는 표보다 일찍 끝났다. A·C·E는 맞다.
2. iOS: 시뮬레이터에서 앱을 지우고 다시 깔아도 첫 화면이 '연결이 끊겼어요'였다(Release·Debug 두 번). 키체인의 연결 정보가 남은 것으로 보인다(추측).
   '다시 연결하기'로 문서의 연결 흐름에 그대로 들어가 진행에는 지장이 없었다. iOS의 Release↔Debug 바꾸기와 이 화면은 문서에 없다.
3. tools/demo/README.md 41줄·50줄: 이 맥의 셸에는 JAVA_HOME이 없어 Gradle이 'Unable to locate a Java Runtime'으로 멈췄다. 문서가 가리키는 README 127줄대로 지정하면 된다(문서와 어긋나지는 않음).
4. 장면 C 준비 뒤 아래 줄 아이곁 단추로 돌아오면 '시연·측정'이 아니라 홈이 나왔다(문서는 홈에서 '아기 설정' → '시연·측정'으로 가라고 적으니 어긋나지는 않음).

### 확인하지 못한 것
- README 92줄의 Xcode 화면 조작(Edit Scheme)은 하지 않았다. 명령 빌드의 결과(구성·중계 주소)만 확인했다.
- 원격 푸시(앱을 닫은 상태의 인계 알림): 키가 없어 확인하지 않았다(README 164줄과 같음).
- 장면 소리(뒷좌석 재생·안내 말)는 귀로 듣지 않았다. 결과 표의 routed_device bus100_audio_zone_1과 기대값으로만 확인.
- 처음 폰(안드로이드)의 알림 권한이 꺼져 있어 '알림이 오지 않음'은 앱 안 카드와 알림 목록으로 봤다. 권한을 켰을 때도 오지 않는지는 미판정(인계 알림 받는 폰을 고르는 곳은 중계·차 쪽이라 영향은 없을 것으로 보지만 확인 안 함).
- iOS 배너 본문 전체(D)는 상태 줄에 가려 읽지 못했다. 같은 글은 '오늘' 카드로 확인.

## 환경 판 확인(2026-11-13, README '테스트 환경과 필요한 설정' 표 대조)
- `sw_vers -productVersion` → 27.0.1, `uname -m` → arm64
- Android Studio 내장 JDK `java -version` → openjdk version "25.0.3" 2026-04-21
- `./gradlew --version` → Gradle 9.6.0
- `xcodebuild -version` → Xcode 27.2 Build version 27B52
- `xcodegen --version` → Version: 2.45.3
- `node --version` → v25.4.0, `npx wrangler --version`(relay) → 4.156.0
- `ls /usr/bin/xcodebuild` → /usr/bin/xcodebuild

## 최종 코드로 다시 확인(2026-11-13 17:24 ~ 11-14 02:30)
위 기록 뒤 바뀐 것: release에서 시연·측정을 빼 debug 빌드에만 두었다. '모든 기록 지우기'를 아기 설정 화면으로 옮겼다(장면 중에는 꺼짐).
시연 스크립트(scene-result.sh·drive.sh·vc.sh)를 다시 짰다. 아래는 마지막 코드로 확인한 결과만 적는다. 중간 판의 확인은 지웠다.
환경: 차 AVD PleosConnect(emulator-5560, -no-snapshot-save -timezone Asia/Seoul), 매번 앱을 지운 깨끗한 상태. 폰은 띄우지 않았다.
제출 APK: 지금 제출 파일은 apk/곁빛_아이곁_release.apk(91,766,869 B, sha256 c8c953f03574ee54…, 11-14 06:49 빌드)와 apk/곁빛_아이곁_휴대폰_release.apk(29,199,794 B, sha256 217a8737b81911f5…, 처음 기록과 같은 파일).
      지금 차 제출 파일은 11-13 23:38 빌드(sha256 e1f0d6ac10fbdea7…)에서 '모든 기록 지우기' 확인 창 문구 한 줄(휴대폰에 이미 온 기록은 휴대폰에서 지우라는 안내)만 더한 것이다. 11-13 23:40 이후 확인(장면 중 지우기 행, 울음 감지 다시 재기 세 번, 기록 지우기의 중계 몫, 공용 adb 보호 뒤 한 바퀴)은 23:38 빌드로 했다.
      그 앞의 차 release 확인(17:24 시연·측정 뺀 뒤, 17:38~17:47 울음 감지 두 번, 22:33 기록 지우기 옮긴 뒤)은 그때 빌드(91,750,485 B)로 했다. 그 뒤 release 코드에서 바뀐 것은 '모든 기록 지우기' 행과 그 행을 장면 중에 끄는 것뿐이다(울음 감지 코드는 그대로).

### 제출 APK(release)
| 확인 | 결과 |
| --- | --- |
| prepare.sh apk/곁빛_아이곁_release.apk | 통과. '[3/8] 설치 확인(user 10): release 빌드', DEBUGGABLE 없음 |
| 아기 설정 맨 아래 | '우리 노래'와 '모든 기록 지우기 · 프로필·연결·노래는 남겨요'만 있고 '시연·측정'은 없음 |
| '아기 탔어요' → drive.sh → 정차 홈 | 통과, exit 0 |
| 모든 기록 지우기 | 확인 창 '모든 기록을 지울까요?'. '닫기'면 리포트 그대로, '지우기'면 리포트가 '아직 기록이 없어요'로 바뀌고 홈 '오늘 하루' 카드가 사라짐. 별명·카시트 자리는 남음 |
| scene-result.sh·cry.sh | 0.4초 만에 '…: debug 빌드에만 있다…'를 찍고 exit 1 |

### 제출 APK로 울음 감지(tools/demo/README '제출 APK로 울음 감지 보기' 그대로, 같은 조건 두 번)
맥 기본 입력 BlackHole 2ch, 출력 USBC Headset. 장치 번호는 고친 명령(`ffmpeg -f lavfi -i anullsrc -t 0.1 -f audiotoolbox -list_devices true -`)으로 찾았다([2] BlackHole 2ch).
녹음: Wikimedia Commons Babys_disappointed_crying.oga(퍼블릭 도메인, 마지막 소리 48.19초). 울음 시작 = ffmpeg 시작 + 0.89초. 시각은 울음 시작부터 잰 값.
| 항목 | 1회차 | 2회차 |
| --- | --- | --- |
| 경보(ATTEMPT) | +2.4초 | +2.7초 |
| S02 "뒤에서 울어요. 뒷자리에 백색소음 틀었어요." | +2.5초 | +2.9초 |
| 뒷좌석 재생 | 출력 bus100_audio_zone_1 | 리포트 '소리 나간 곳 · 뒷좌석' |
| 녹음 끝 | +47.9초 | +48.3초 |
| 소리 멈추고 듣기(GAP) → 확인(CONFIRM) | +58.4초 → +66.7초 | +58.7초 → +67.1초 |
| 그침 판정 | 판정하지 못함(감지 시간 초과, +148.8초) | 그침(+97.3초) |
| drive.sh 180 | exit 0 | exit 0 |
| 리포트 | 울음 1번, 감지까지 3.9초, 뒷좌석 재생 56초 | 같음 |
- 소리는 녹음이 끝날 때가 아니라 정해진 재생 시간(56초)이 끝날 때 멈췄다.
- 1회차의 '판정하지 못함'은 판별 구간이 3초 넘게 나오지 않아 앱이 판정에서 뺀 것이다(거짓 판정은 없음). 마이크는 열려 있었고 녹음은 끝까지 울음이었다. 까닭은 미판정.
- 다시 재기(11-14 03:40 무렵, 같은 절차 세 번, 맥 1분 부하 4.5~4.9): 경보 +2.0·+2.3·+2.1초, 확인 30.2초, 울음 시작부터 판정까지 96.5·96.9·96.6초, 세 번 다 '그침'.
  확인 구간 동안 화면 글을 약 2.5초마다 읽었고 '아직 듣지 못하고 있어요'는 한 번도 나오지 않았다. 다섯 번 중 '판정하지 못함'은 처음 한 번뿐이다.
  감지 코드(app/src/main/kotlin/kr/aigyeot/app/detect/)는 로그를 남기지 않아, 그 한 번에 무엇이 끊겼는지는 볼 수 없다. 실패 회차의 맥 부하(11.29)가 통과 회차(39.36)보다 낮아 맥 부하 탓은 아닌 것으로 보인다(추측).

### debug 빌드
| 확인 | 결과 |
| --- | --- |
| 아기 설정 맨 아래 | '우리 노래'·'시연·측정'·'모든 기록 지우기'. 시연·측정 화면에는 지우기 단추가 없음 |
| 장면이 도는 동안 아기 설정(시스템 뒤로가기로 들어감) | '모든 기록 지우기' 행이 꺼지고 아래 글이 '장면이 끝나면 지울 수 있어요'. 눌러도 확인 창이 뜨지 않음. 장면이 끝나면 다시 켜짐 |
| prepare.sh(인자 없음) → drive.sh 10 | 둘 다 exit 0, 정차 홈으로 돌아옴 |

### scene-result.sh(스크립트를 먼저 실행하고 '기다린다' 뒤 '실행하기')
| 경우 | 출력 | exit |
| --- | --- | --- |
| F14-01 반복 1(여러 번) | '결과 파일 · … · 요청 1회', '회차 1: 기대 조건 충족…', '통과 1/1', 정차로 되돌림 | 0 |
| F14-01 반복 2 | 저장 CSV에서 읽어 회차 1·2 충족, '통과 2/2'(CSV를 run-as로 직접 읽어 대조) | 0 |
| F14-01 반복 2, 2회차 5초에 '멈추기' | 회차 2 못 채움(처음 어긋난 장면 시각 5.0초), '통과 1/2', 정차로 되돌림 | 1 |
| 깨진 장면 파일(files/scenario/ZZ-broken.json) | '시작하지 못함 · 장면 파일 오류: JSON 객체가 아니다'(누른 지 약 2초), 되돌리지 않음 | 1 |
| 결과 폴더 쓰기를 막음(chmod 500) | 메모에 '결과 파일을 쓰지 못함', 로그의 표로 '통과 1/1' | 0 |
| 앱을 끈 뒤 실행 | '앱이 떠 있지 않다…'(0.2초) | 1 |
| 장면 중(기어 P 5초, 기어 D·40km/h 15초) 앱 강제 종료 | '앱이 꺼져…' 뒤 reset.sh로 기어 P·0km/h | 1 |
| '기다린다' 4초 뒤 Ctrl-C | '멈춤: 장면이 돌고 있으면…', 남은 adb logcat 없음 | 130 |
| 장면이 끝난 직후 같은 셸에서 바로 다시 실행, 8번 연달아 | 8번 모두 앞 장면 결과를 읽지 않고 '기다린다'만 찍음, 표식 줄 수 = 실행 수 | 130(Ctrl-C) |
- 강제 종료 한 번은 0.1초 뒤 시스템이 앱 프로세스를 다른 pid로 다시 띄웠다(com.google.android.datatransport의 예약 작업). 그때 앞 판 스크립트는 프로세스가 있다고 보고 계속 기다렸다. 지금 스크립트는 시작 때의 pid와 달라지면 꺼진 것으로 본다(selftest.py 'app-restarted'로 확인).
- 표식 줄이 로그에 들어가지 않은 일이 한 번 있었다(앞 실행 직후). 지금 스크립트는 새 표식으로 세 번까지 다시 남긴다(selftest.py 'mark-dropped-once').
- pid 비교로 고친 뒤 다시 확인(11-14 03:00 무렵): 기어 D·40km/h로 달릴 때 강제 종료를 두 번 했다. 한 번은 프로세스가 다시 뜨지 않았고, 한 번은 바로 다른 pid(6133 → 17141)로 다시 떴다. 두 번 다 1초 안에 '앱이 꺼져…'를 찍고 기어 P·0km/h로 되돌린 뒤 exit 1. 같은 확인 중 F14-01 반복 1은 '통과 1/1', exit 0.
- 같은 확인의 첫 부팅에서 prepare.sh가 무드 조명 단계에서 세 번 멈췄다('무드 조명' 다음의 '켜기'를 찾지 못함). 차량 설정 '라이트' 화면이 스크롤되지 않았다(맥 1분 부하 64~69, 세 번째는 4.7). 에뮬레이터를 다시 띄우자 [1/8]~[8/8] exit 0.

### 확인하지 못했던 것을 다시 잰 결과(11-14 03:20~03:50)
- 표식 줄 빠짐: 쉬는 상태와 장면 결과 직후에 표식 300줄씩을 남겼다. 600줄 모두 들어왔고 logd 오류 줄은 없었다. 앞서 한 줄이 빠진 일은 재현되지 않았다.
- 보조 .txt만 쓰기 실패: 장면이 시작된 뒤 같은 이름의 폴더를 만들어 .txt 쓰기만 막았다. 결과 CSV로 '통과 1/1', exit 0이었고, 메모에 '결과 파일을 쓰지 못함'이 붙지 않았다. logcat에는 '결과 요약 글 쓰기 실패: … EISDIR'이 찍혔다.
  장면 전에 폴더를 만들면 앱이 그 이름을 건너뛰어(-2.csv) 실패가 생기지 않는다.
- 달리는 중 Virtual Console 연결 끊김(drive.sh 40 중 adb forward --remove): 그때 판은 정차 요청을 같은 끊긴 연결로 다시 보내 실패로 끝나고 차가 D·30km/h로 남았다(reset.sh로 P·0). 지금 판은 연결을 다시 잡고 정차한다(아래).
- 모든 기록 지우기의 중계 몫(제출 APK 둘, 배포 중계): 폰 앱을 끈 채 주행해 리포트를 만들었다. 차에서 '모든 기록 지우기' 뒤 폰을 열자 그 리포트는 오지 않았다. 대조로 지우지 않은 주행은 16초 안에 왔다.
  release 앱이 중계 요청을 로그로 남기지 않아, 지운 것이 중계 보관분인지 차의 보낼 함인지는 가리지 못했다(어느 쪽이든 폰에는 오지 않음).

### 끝 알림 파일과 VC 다시 잡기(11-14 04:12~04:17)
- 앱은 끝날 때 결과 폴더에 .done 파일을 쓴다(첫 줄이 끝 알림 줄). F14-01 뒤 F14-01-…-0414.done의 첫 줄이 스크립트가 찍은 '결과 파일 · … · 요청 1회'와 같았다. 깨진 장면에서는 notstarted-….done('시작하지 못함 · 장면 파일 오류: JSON 객체가 아니다')이 생겼고 스크립트는 exit 1.
  이번에는 로그가 먼저 와서 .done만으로 끝을 아는 길은 일어나지 않았다(가짜 adb로만 확인).
- drive.sh 30 중 D·30km/h에서 adb forward --remove: 정차 요청 둘이 실패한 뒤 'VC 연결을 다시 잡고 정차를 다시 보낸다' → 정차·뒷문까지 보내 exit 0. 끝난 뒤 P·0km/h, 포트 연결 다시 잡힘, 세션 닫힘.

### 공용 adb 보호를 넣은 뒤 한 바퀴(11-14 05:20~05:24)
모든 adb 호출에 시간 제한(perl alarm), 기기에서 읽는 값은 끝 표식까지 온 출력만 쓰기, 경로 인용, 기기·포트별 pid 파일을 넣은 뒤 실제 에뮬레이터에서 돌렸다.
prepare.sh(release·debug 둘 다 exit 0) → release에서 scene-result.sh는 'debug 빌드에만 있다'로 exit 1 → cry.sh(1,271,250바이트 일치, exit 0) → F14-01 '통과 1/1', exit 0(결과 폴더에 .csv·.done·.txt만, .tmp 없음) → drive.sh 10 exit 0 → stop.sh exit 0(pid 파일 지워짐).

### 가짜 adb·curl 자체 시험(tools/demo/selftest.py, 기기 없이)
scene-result.sh 27가지, drive.sh·reset.sh 12가지 끝나는 길이 모두 통과. 오류 종류마다 넣었다: 호출 실패, 출력이 중간에 끊김(CSV), 응답 없음(adb 멈춤 → 시간 제한), 앞 실행이 남긴 로그·.done, 경로의 공백·따옴표·세미콜론, 끝 알림 로그 빠짐, logcat 끊김(.done으로 계속), 앱 꺼짐·다른 pid, VC 연결 끊김(정차·뒷문 닫기 다시 보내기), 중단(뒷문을 연 뒤 포함).

### 확인하지 못한 것
- 울음 감지 다섯 번 중 한 번 판별 구간이 3초 넘게 나오지 않은 까닭. 다시 잰 세 번에서는 재현되지 않았고, 감지 코드가 로그를 남기지 않아 볼 길이 없다.
- 표식 줄이 로그에 한 번 들어가지 않은 까닭. 600줄로 재현되지 않았다. 표식 다시 남기기는 가짜 adb로만 확인했다.
- 회차와 회차 사이에 '멈추기'를 눌러 회차가 모자라는 경우. 에뮬레이터에서는 두 회차 사이에 누를 틈이 없어, 가짜 adb로만 확인했다.
- '모든 기록 지우기' 뒤 폰에 리포트가 오지 않은 것이 중계 보관분을 지운 결과인지, 차 보낼 함에서 버린 결과인지(release 앱이 중계 요청을 로그로 남기지 않음).
