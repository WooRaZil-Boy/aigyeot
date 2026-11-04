# 아이곁

> 아기가 울면 차가 대신 달래 줍니다. 부모는 앞만 보고 운전합니다.

뒷좌석 카시트에 0~2세 아기를 태우고 혼자 운전하는 부모를 위한 Pleos Connect 앱입니다. 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 하는 일

1. **듣기:** 차 마이크로 아기 울음을 기기 안에서 감지합니다. 원음은 저장하거나 밖으로 보내지 않습니다.
2. **달래기:** 뒷좌석 스피커에서만 달래는 소리를 틉니다. 운전자에게는 한 문장만 말합니다.
3. **판정:** 56초 뒤 재생을 멈추고 듣습니다. 조용하면 30초 더 확인한 뒤에 '그쳤다'고 기록합니다. 아이마다 어떤 소리를 틀었을 때 그쳤는지가 쌓입니다.
4. **넘기기:** 자동 시도는 두 번까지입니다. 그래도 울면 재생을 멈추고 세울 곳(수유실 휴게소 등)을 찾아 내비 경유지로 넣을지 묻습니다.

울음의 원인을 맞히거나, 잠·호흡을 감지하지는 않습니다.

## 개발 상태

구현 중입니다. 실행 방법, 테스트 환경, APK 위치(`apk/`)는 앱이 완성되면 이 문서에 적습니다.

## 저장소 구성

| 위치 | 내용 |
| --- | --- |
| [tools/cry-retrain](tools/cry-retrain/) | 울음 판별층 재학습·평가 스크립트와 기기용 판별층 `cry_head24_rt.json`. YAMNet 점수 위에 얹는 로지스틱 판별층입니다 |
| [tools/pleos-probe](tools/pleos-probe/) | Pleos 공식 에뮬레이터에서 소리·차량 제어·내비·부팅·마이크 동작을 잰 실측 앱 |
| [tools/sim](tools/sim/) | 아이별 소리 선택(톰슨 샘플링) 시뮬레이션과 차량 제어 실측 로그 |

## 실측과 평가

### 에뮬레이터 실측

Pleos 공식 에뮬레이터에서 잰 것입니다.

| 항목 | 결과 | 조건·로그 |
| --- | --- | --- |
| 뒷좌석 전용 재생 | 뒷좌석 존 출력 장치로만 나감 | `AudioTrack` 출력 장치 지정. 로그: [rear_audio_1102.txt](tools/pleos-probe/rear_audio_1102.txt) |
| 주행 중 차량 제어 반영 | 24/24 | 기어 D·60km/h. 뒷좌석 열선(`ROW_2_LEFT`, 레벨 2↔0), 뒷창 잠금(`ROW_2_LEFT`, 잠금↔해제), 앞 공조 온도(`ROW_1_LEFT`), 앰비언트(전체 영역) 각 6/6. 열선은 켜기·끄기, 잠금은 잠금·해제를 번갈아 6번 보냈습니다. 로그: [babycare_Drive.txt](tools/sim/babycare_Drive.txt) |
| 뒷좌석 독서등 | 주행 중 0/6, 정차(P) 6/6 | 주행 중에는 시스템이 명령을 거절합니다(오류 50001). 로그: [babycare_Drive.txt](tools/sim/babycare_Drive.txt), [babycare_Park.txt](tools/sim/babycare_Park.txt) |
| 시동 뒤 화면 없이 마이크 열기 | 다섯 경로 모두 막힘 | 그래서 출발 전 정차 중에 한 번 눌러 켭니다. 로그: [mictest_1102.txt](tools/pleos-probe/mictest_1102.txt) |

에뮬레이터의 YAMNet + 판별층이 맥 계산값과 같은 확률을 내는지 6개 시험 PCM(구간 54개)으로 대조했습니다. 소수 둘째 자리 기준 최대 절대차는 0.00이고 경보 6/6이 일치했습니다. 로그: [device_match_1102.txt](tools/pleos-probe/device_match_1102.txt)

### 오프라인 평가

맥에서 파이썬으로 돌린 평가입니다. 재현율·오경보 숫자는 **교차검증(학습에 안 쓴 접기로 잼)** 값입니다. 같은 구조의 판별층을 접기마다 새로 학습해, 그 판별층이 학습에 쓰지 않은 접기에서 점수를 냈습니다(`rt_train.py`). 앱에 들어가는 `cry_head24_rt.json`은 같은 데이터 전체로 학습한 것이라, 이 판별층에 진짜 학습 밖 시험은 AxonData(독립 울음 18개)와 아래 R1·R3·첫 경보 장면 시험입니다. 장면 시험은 내보낸 판별층을 그대로 씁니다. 스크립트와 결과 파일은 [tools/cry-retrain](tools/cry-retrain/)에 있습니다.

| 항목 | 결과 | 조건 |
| --- | --- | --- |
| 울음 경보 재현율(교차검증) | 378/457(0.83) | 휴대폰으로 녹음한 아기 울음(Donate-a-Cry) 457개, 차 소음 10 dB, 기준 0.7. 이전 판별층은 0.64(같은 기준 0.7) / 0.70(이전 판별층 자신의 기준 0.5) |
| 오경보(교차검증) | 말소리 0/497, 음악 0/144, 노래(CSD) 7/107, ESC 다른 소리 15/1960 | 같은 조건. 말소리 497개 중 36개는 약 2.9초보다 짧아 판정 구간이 하나뿐이라, '연속 2번' 규칙상 경보를 낼 수 없습니다. 경보가 가능한 말소리는 461개입니다 |
| 달래기 성공·실패 판정(R3) | 359/360 | 56초에 재생을 멈추고 8초 듣고, 재생을 끈 채 30초 더 확인 |
| 재생을 끄지 않고 t0+30~60초로 판정할 때(R1, 비교용), 노래 장면 | 실제로 그친 72번 중 54번 맞힘, 계속 운 72번은 72번 모두 맞힘 | 18번은 노래를 울음으로 착각해 '아직 운다'로 잘못 봤습니다 |

조건과 한계:
- 360번 = 학습에 쓰지 않은 가정 울음 녹음 18개(AxonData) × 울음 대 차 소음 비 2가지(10, 0 dB) × 정답 2가지(그침·계속) × 재생 조건 5가지(없음, 백색소음, 브라운 잡음, 노래 두 크기). 조건마다 소음을 새로 뽑았습니다.
- 장면의 경계가 분명합니다. '그침' 장면은 첫 재생 5~25초 뒤에 울음이 끝나고, '계속' 장면은 첫 재생(t0) 76초 뒤까지 이어집니다. 확인 중에 다시 우는 경우와 판정 시점 근처에서 그치는 경우는 시험하지 않았습니다.
- 장면 시험(R1·R3·`rt_stop`)의 차 소음은 학습에 쓴 것과 같은 DEMAND TCAR 300초 녹음에서 뽑았습니다. 같은 60초 구간 다섯 개 중 하나를 고르므로 소음은 학습 밖이 아닙니다.
- R1 시험에 쓴 노래는 판별층이 '울음 아님'으로 배운 노래와 같은 곡입니다.

울음이 실제로 줄어드는지, 운전자가 덜 돌아보는지는 사람과 아기가 있어야 잴 수 있어 아직 재지 않았습니다.

## 출처 고지

- **자장가:** 브람스 「자장가」 Op.49 No.4(퍼블릭 도메인). MIDI: Rabanus Flavus(Peter Gerloff), CC0, Wikimedia Commons. 음색: FluidR3_GM(Frank Wen, MIT). −30 LUFS로 맞춰 렌더링함.
- **휴게소·졸음쉼터 정보:** 공공데이터포털 전국휴게소정보표준데이터(한국도로공사, 2026-09-21)·전국졸음쉼터표준데이터(한국도로공사, 2026-03-05). 졸음쉼터 데이터 번호는 15028203입니다.
- **울음 판별 바탕 모델:** YAMNet(TensorFlow Model Garden, Apache 2.0)과 MediaPipe tasks-audio(Apache 2.0). Apache 2.0 전문은 [app/src/main/assets/licenses/Apache-2.0.txt](app/src/main/assets/licenses/Apache-2.0.txt)에 있습니다.
- **AudioSet 온톨로지:** YAMNet 모델 파일에 들어 있는 클래스 이름은 AudioSet 온톨로지(CC BY-SA 4.0)에서 왔습니다.
- **판별층:** YAMNet 점수 위에 얹은 판별층의 학습 데이터와 라이선스는 아래 표에 있습니다.

## 학습 데이터와 라이선스

판별층 학습에 쓴 데이터입니다. 비상업 조건 데이터가 들어 있어 이 판별층은 공모전 시연용입니다. 상용 배포 전에는 상용 가능한 데이터로 다시 학습합니다. 라이선스는 2026-11-02에 각 배포처의 표기를 확인했습니다.

| 데이터 | 쓰임 | 라이선스 |
| --- | --- | --- |
| [ESC-50](https://github.com/karolpiczak/ESC-50) | 울음·다른 소리 | CC BY-NC |
| [Donate-a-Cry corpus](https://github.com/gveres/donateacry-corpus) | 울음 | GitHub 라이선스 항목 없음. 저장소 README에 ODbL 1.0(데이터베이스)·DbCL 1.0(내용)이라고 적혀 있음 |
| [AxonData Infant Cry Detection Dataset](https://huggingface.co/datasets/AxonData/infant-cry-detection-dataset)(공개 표본) | 처음 듣는 울음 시험 | CC BY 4.0 |
| [DEMAND](https://zenodo.org/records/1227121)(TCAR) | 차 실내 소음 | Zenodo 메타데이터는 CC BY 4.0, 설명 본문은 CC BY-SA 3.0(표기가 서로 다름) |
| [CSD: Children's Song Dataset](https://zenodo.org/records/4785016) | 노래(오경보 시험) | CC BY-NC-SA |
| [MUSDB18 7초 발췌](https://zenodo.org/records/3270814) | 음악(오경보 시험) | Zenodo 기록 표기는 CC BY 4.0(원곡별 권리는 확인하지 못함) |
| [Zeroth-Korean](https://huggingface.co/datasets/Bingsu/zeroth-korean)(test) | 한국어 말소리 | CC BY 4.0 |
| [Mini LibriSpeech](https://www.openslr.org/31/)(dev-clean-2) | 영어 말소리 | CC BY 4.0 |
| macOS `say` 합성 | 한국어 말소리 8개 | 직접 합성(공개 배포 안 함) |
