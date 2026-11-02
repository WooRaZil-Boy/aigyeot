# 울음 판별층 재학습·평가

YAMNet(MediaPipe `yamnet.tflite`)이 0.975초 창마다 내는 521개 점수 위에 작은 로지스틱 판별층을 얹어 '아기 울음'을 판정한다.
- **입력 특징:** 울음 관련 24개 클래스 점수의 최근 3창 평균과 최대, 48개.
- **경보:** 3초 구간 2개가 연속으로 기준(0.7)을 넘을 때. 구간이 하나뿐인 짧은 입력은 경보를 낼 수 없다(기기와 같은 규칙).

## 파일

| 파일 | 내용 |
| --- | --- |
| `cry_head24_rt.json` | **앱이 쓰는 판별층.** 차 소음·앱 재생음·말소리·노래를 섞어 다시 학습했다 |
| `cry_head24.json` | 이전 판별층(ESC-50 + 차 소음으로만 학습). 비교 기준으로만 쓴다 |
| `rt_extract.py` | 데이터에 변형 12가지(차 소음 SNR, 앱 재생음, 8kHz 표본율)를 입히고 YAMNet 점수를 뽑는다. 0.5초보다 짧은 파일은 이름을 찍고 건너뛴다 |
| `manifest.csv` | 특징이 나온 클립 3223개의 목록(`src,name,fold,cry`). `make_manifest.py`가 `rt_feats.npz`에서 만든다 |
| `make_manifest.py` | `python make_manifest.py S [출력 경로]`. `manifest.csv`를 다시 만든다 |
| `rt_train.py` | 교차검증으로 재학습·평가하고 `cry_head24_rt.json`을 내보낸다. 결과는 `rt_results.json` |
| `rt_outcome.py` | 달래기 성공·실패 판정 규칙(R1 재생 중 판정, R2 듣기 틈, R3 듣기 틈 + 30초 확인)의 정확도를 잰다. 결과는 `rt_outcome.json` |
| `rt_stop.py` | 주행 장면에서 첫 경보 지연, 울음 도중 '그침' 오판, 그친 뒤 재경보를 잰다. 결과는 `rt_stop.json` |
| `rt_stop.json` | `rt_stop.py` 결과(2026-11-02, 수정한 코드로 다시 돌린 것). 판별층 2종 × 기준 0.5·0.7. 듣기 틈 조건(`wngap`·`songgap`)은 관측한 조용한 시간이 장면(최대 약 154초) 안에서 30초에 못 미쳐 '울음 도중 그침 오판'과 '그침 지연'이 구조적으로 측정 불가라 `측정 불가`로 적었다. 나머지 조건의 요약은 그 수정 전과 같다 |
| `mk_pcm.py` | 에뮬레이터 확인용 10초 PCM과 맥 기준값을 만든다(기기 계산이 맥과 같은지 대조. 대조 결과는 [`device_match_1102.txt`](../pleos-probe/device_match_1102.txt)) |
| `csd_list.py` | CSD(1.85GB zip)에서 필요한 곡만 HTTP Range로 꺼낸다. 아래 '`csd_list.py` 사용법' |

## 환경

- Python 3.11
- `mediapipe==0.10.14`(앱의 `tasks-audio` 0.10.14와 맞춘다)
- `numpy==1.26.4`, `scikit-learn==1.5.2`, `scipy`, `soundfile`

## 데이터 폴더(`S`)

스크립트는 첫 인자로 데이터 폴더 `S`를 받는다. 구성은 아래와 같다.

```
S/
  dl/yamnet.tflite        https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite
  yamnet_classes.csv      https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv
  cry/                    결과가 쌓이는 곳(없으면 만든다). cry_head24.json을 두면 그것을 비교 기준으로 쓴다
  data/ESC-50-master/                     https://github.com/karolpiczak/ESC-50
  data/donateacry-corpus-master/          https://github.com/gveres/donateacry-corpus
  data/rt/tcar/<세션>/ch*.wav             DEMAND TCAR 16k  https://zenodo.org/records/1227121
  data/rt/wav16/axon/*.wav                AxonData 공개 표본 18개(학습에 쓰지 않는 독립 시험)
  data/rt/wav16/zeroth/*.wav              Zeroth-Korean test
  data/rt/wav16/lsmini/*.wav              Mini LibriSpeech dev-clean-2
  data/rt/wav16/say/*.wav                 macOS `say` 한국어 합성(목소리 9개를 돌렸지만 Yuna_1~8만 소리가 나왔다)
  data/rt/wav16/musdb/*.wav               MUSDB18 7초 발췌
  data/rt/wav16/csd/*.wav                 CSD 한국어 동요(csd_list.py로 꺼냄)
```

`wav16/` 아래 파일은 모두 16kHz 모노 WAV다. 원본을 이 형식으로 바꾸는 변환과 `say` 합성은 일회성 명령으로 만들었다. 그 과정은 스크립트로 남아 있지 않다.

### `csd_list.py` 사용법

```bash
python csd_list.py list                              # zip 안의 .wav 목록과 크기(MB)
python csd_list.py get OUT_DIR <zip 안 경로> [...]    # 골라 OUT_DIR에 저장(디렉터리 부분은 떼고 파일 이름만 씀)
```

첫 인자가 `list`가 아니면 모두 `get`으로 동작하고, 둘째 인자를 저장 폴더로 쓴다. 저장 폴더가 없으면 만든다. 예: `python csd_list.py get S/data/rt/csd CSD/korean/wav/kr001a.wav`. 원본은 44.1kHz 스테레오이므로 `S/data/rt/csd/`에 둔 채, 16kHz 모노로 바꾼 사본을 `S/data/rt/wav16/csd/`에 둔다.

```bash
mkdir -p S/data/rt/wav16/csd
for f in S/data/rt/csd/*.wav; do ffmpeg -i "$f" -ac 1 -ar 16000 "S/data/rt/wav16/csd/$(basename "$f")"; done
```

다른 리샘플러를 써도 되지만 공개한 숫자는 위 ffmpeg 명령으로 만든 사본에서 나온 것이다. `rt_extract.load`는 파일이 16kHz가 아닐 때만 리샘플하므로, 다른 리샘플러로 만든 사본은 샘플이 달라 확률이 조금 바뀌고 숫자가 똑같이 재현되지 않는다. `wav16/`의 규칙(16kHz 모노)은 지킨다.

### 데이터 구성과 파일 이름

항목 3287개(파일 3193개, CSD 13곡은 10초 조각 107개로 셈) 중 0.5초 미만 64개(`say` 빈 파일)를 건너뛰어 특징이 나온 클립은 3223개다(`manifest.csv`의 행 수와 같다). 접기(`fold`)는 ESC-50은 원래 접기, 나머지는 업로더·화자·곡 같은 묶음 단위로 나눈 값이다.

| 출처(`src`) | 개수 | 구성·이름 규칙 |
| --- | --- | --- |
| `esc` | 2000(울음 40, 나머지 1960) | ESC-50 전체. `<원래 접기>-<클립>-<조각>-<분류 번호>.wav` 그대로 |
| `dac` | 457 | Donate-a-Cry 정리본 전체(울음). `<업로더 UUID>-<시각>-<버전>-<성별>-<나이>-<이유>.wav` 그대로. 접기는 UUID 36자의 해시 |
| `axon` | 18 | AxonData 공개 표본 `cry_00001.wav`~. 접기 0(학습에 쓰지 않는 독립 시험) |
| `speech_ko` | 229 | Zeroth-Korean test 457개 중 짝수 번호만. `b<묶음>_<번호>.wav`(예 `b00_000.wav`, `b00_002.wav`, …, `b09_456.wav`). 번호는 000~456의 짝수, 묶음은 번호를 50으로 나눈 몫이다 |
| `speech_en` | 260 | Mini LibriSpeech dev-clean-2에서 화자 26명. `<화자>-<챕터>-<발화>.wav`(예 `1272-135031-0000.wav`). 접기는 화자 단위 |
| `speech_say` | 8 | macOS `say` 합성 `Yuna_1.wav`~`Yuna_8.wav`. 9개 목소리를 돌렸지만 나머지 8개 목소리 파일은 0.01초짜리 빈 파일이라 `rt_extract.py`가 건너뛴다 |
| `music` | 144 | MUSDB18 7초 발췌의 믹스 144곡. 파일 이름은 `<아티스트> - <곡>`의 공백을 `_`로 바꾼 것(예 `AM_Contra_-_Heart_Peripheral.wav`, `Juliet's_Rescue_-_Heartbeats.wav`) |
| `singing` | 107 | CSD 한국어 동요 13곡(`kr001a`, `kr003a`, …, `kr025a`)을 10초씩 자른 조각. 이름은 `<곡>.wav#<조각 번호>`, 접기는 곡 단위 |

`manifest.csv`의 `cry`는 울음이면 1이다(`esc` 40 + `dac` 457 + `axon` 18 = 515).

## 순서

```bash
python rt_extract.py S                       # S/cry/rt_feats.npz
python make_manifest.py S                    # manifest.csv(rt_feats.npz의 클립 목록)
python rt_train.py S                         # S/cry/cry_head24_rt.json, rt_results.json
python rt_outcome.py S S/cry/cry_head24_rt.json   # rt_outcome.json
python rt_stop.py S cry_head24.json S/cry/cry_head24_rt.json   # S/cry/rt_stop.json(판별층 2종. 이 폴더의 cry_head24.json이 이전 판별층)
python mk_pcm.py S                           # S/assets/rt/*.pcm + 기준값
```

## 주요 결과

모두 경보 기준 0.7이다(`rt_results.json`의 `h24`, `0.7`). 재현율·오경보 두 줄은 **교차검증(학습에 안 쓴 접기로 잼)** 값이다. 접기마다 같은 구조의 판별층을 새로 학습해 안 쓴 접기에서 점수를 냈다(`rt_train.py`). 내보낸 `cry_head24_rt.json`은 같은 데이터 전체로 학습했으므로 그 판별층의 학습 밖 시험은 AxonData(독립 울음 18개)와 R1·R3·첫 경보 장면 시험이다(장면 시험은 내보낸 판별층을 그대로 쓴다).

참고(시험 결과 아님): 내보낸 판별층을 같은 데이터에 다시 적용한 학습 안 점수는 울음 379/457, 말소리 0/497, 음악 0/144, 노래 6/107, ESC 다른 소리 13/1960이다.

| 항목 | 결과 |
| --- | --- |
| 휴대폰 녹음 울음(Donate-a-Cry 457개), 차 소음 10 dB, 교차검증 | 378/457(0.83) 경보. 이전 판별층은 293/457(0.64, 같은 기준 0.7). 이전 판별층 자신의 기준 0.5(`cry_head24.json`의 `threshold`)로 재면 319/457(0.70), 오경보는 말소리 2/497, 노래 4/107(`rt_results.json` old/car10/0.5) |
| 오경보(교차검증): 말소리 / 음악 / 노래(CSD) / ESC 다른 소리 | 0/497 / 0/144 / 7/107 / 15/1960 |
| (말소리 참고) 판정 구간이 하나뿐인 짧은 클립 | 497개 중 36개(약 2.9초 미만: `speech_en` 35, `speech_say` 1). '연속 2번' 규칙상 경보를 낼 수 없어 경보가 가능한 말소리는 461개 |
| 첫 경보 지연(주행 장면, 기준 0.7) | 첫 경보 중앙값 2.4초, 전체 360장면 중 357장면에서 경보(`rt_stop.json`). 재생은 울음 시작 6초 뒤에 켜지므로 첫 경보 357개 중 353개가 재생 시작 전에 났다. 이 값은 재생 중 소음에 대한 강건성을 시험한 것이 아니다 |
| 달래기 판정 R3(재생을 멈추고 듣고, 끈 채로 30초 확인) | 360번 중 359번 일치 |
| 달래기 판정 R1(재생하면서 판정), 노래를 틀 때 | 실제로 그친 72번 중 54번만 맞힘. 18번은 노래를 울음으로 착각해 '아직 운다'로 판정 |

## 데이터 라이선스

ESC-50(CC BY-NC)과 CSD(CC BY-NC-SA)는 비상업 조건이다. 그래서 `cry_head24_rt.json`은 공모전 시연용이며, 상용 배포 전에는 상용 가능한 데이터로 다시 학습한다. 나머지 데이터의 라이선스는 저장소 README의 표를 본다.
