# 울음 판별층 재학습·평가

YAMNet(MediaPipe `yamnet.tflite`)이 0.975초 창마다 내는 521개 점수 위에 작은 로지스틱 판별층을 얹어 '아기 울음'을 판정한다.
- **입력 특징:** YAMNet 클래스 24개(울음·아이 소리와 비교용 말소리·음악·동물·차량 등, `cry_head24_rt.json`의 `names`) 점수의 최근 3창 평균과 최대, 48개.
- **경보:** 3초 구간 2개가 연속으로 기준(0.7)을 넘을 때. 구간이 하나뿐인 짧은 입력은 경보를 낼 수 없다(기기와 같은 규칙).

## 파일

| 파일 | 내용 |
| --- | --- |
| `cry_head24_rt.json` | **앱이 쓰는 판별층.** 차 소음·앱 재생음·말소리·노래를 섞어 다시 학습했다 |
| `cry_head24.json` | 이전 판별층(ESC-50 + 차 소음으로만 학습). 비교 기준으로만 쓴다 |
| `rt_extract.py` | 데이터에 변형 12가지(원본 그대로, 차 소음 SNR 4가지, 앱 재생음 6가지, 8kHz 표본율 1가지. `rt_extract.py`의 `VARS`)를 입히고 YAMNet 점수를 뽑는다. 0.5초보다 짧은 파일은 이름을 찍고 건너뛴다 |
| `manifest.csv` | 특징이 나온 클립 3223개의 목록(`src,name,fold,cry`). `make_manifest.py`가 `rt_feats.npz`에서 만든다 |
| `make_manifest.py` | `python make_manifest.py S [출력 경로]`. `manifest.csv`를 다시 만든다 |
| `rt_train.py` | 교차검증으로 재학습·평가하고 `cry_head24_rt.json`을 내보낸다. 결과는 `rt_results.json` |
| `rt_outcome.py` | 달래기 성공·실패 판정 규칙(R1 재생 중 판정, R2 듣기 틈, R3 듣기 틈 + 30초 확인)의 정확도를 잰다. 결과는 `rt_outcome.json` |
| `rt_stop.py` | 주행 장면에서 첫 경보 지연, 울음 도중 '그침' 오판, 그침 판정 지연, 그친 뒤 재경보를 잰다. 결과는 `rt_stop.json` |
| `rt_stop.json` | `rt_stop.py` 결과(2026-11-02, 수정한 코드로 다시 돌린 것). 판별층 2종 × 기준 0.5·0.7. 듣기 틈 조건(`wngap`·`songgap`)은 관측한 조용한 시간이 장면 안에서 30초에 못 미쳐 '울음 도중 그침 오판'과 '그침 지연'이 구조적으로 측정 불가라 `측정 불가`로 적었다 |
| `mk_pcm.py` | 에뮬레이터 확인용 10초 PCM과 맥 기준값을 만든다(기기 계산이 맥과 같은지 대조. 대조 결과는 [`device_match_1102.txt`](../pleos-probe/device_match_1102.txt)) |
| `csd_list.py` | CSD(1.85GB zip)에서 필요한 곡만 HTTP Range로 꺼낸다. 아래 '`csd_list.py` 사용법' |

## 환경

- Python 3.9~3.12(`mediapipe==0.10.14`의 설치 파일이 있는 판. 3.13 이상은 설치 파일이 없어 설치되지 않는다. 평가에 쓴 판은 기록하지 않았다)
- `mediapipe==0.10.14`(평가할 때 앱의 `tasks-audio` 판과 맞췄다. 앱은 뒤에 0.10.32로 올렸고 같은 입력의 확률이 같았다: [mediapipe_1108.txt](../measure/mediapipe_1108.txt))
- `numpy==1.26.4`, `scikit-learn==1.5.2`, `scipy`, `soundfile`. NumPy 2(2.4.2)에서는 MediaPipe 0.10.14 오디오 분류가 프로세스째 죽어(종료 코드 139) NumPy 1.x로 고정한다. Apple Silicon에서 2.4.2는 죽고 1.26.4는 같은 입력을 정상 처리한 것을 확인했다

## 데이터 폴더(`S`)

스크립트는(`csd_list.py` 빼고) 첫 인자로 데이터 폴더 `S`를 받는다. 구성은 아래와 같다.

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
  data/rt/wav16/say/*.wav                 macOS `say` 한국어 합성
  data/rt/wav16/musdb/*.wav               MUSDB18 7초 발췌
  data/rt/wav16/csd/*.wav                 CSD 한국어 동요(csd_list.py로 꺼냄)
```

`wav16/` 아래 파일은 16kHz 모노 WAV로 둔다. 원본을 이 형식으로 바꾸는 변환과 `say` 합성은 일회성 명령으로 만들었다. 그 과정은 스크립트로 남아 있지 않다.

### `csd_list.py` 사용법

```bash
python csd_list.py list                              # zip 안의 .wav 목록과 크기(MB)
python csd_list.py get OUT_DIR <zip 안 경로> [...]    # 골라 OUT_DIR에 저장(디렉터리 부분은 떼고 파일 이름만 씀)
```

첫 인자가 `list`가 아니면 모두 `get`으로 동작하고, 둘째 인자를 저장 폴더로 쓴다. 저장 폴더가 없으면 만든다. 예: `python csd_list.py get S/data/rt/csd CSD/korean/wav/kr001a.wav`. 원본은 `S/data/rt/csd/`에 둔 채, 16kHz 모노로 바꾼 사본을 `S/data/rt/wav16/csd/`에 둔다.

```bash
mkdir -p S/data/rt/wav16/csd
for f in S/data/rt/csd/*.wav; do ffmpeg -i "$f" -ac 1 -ar 16000 "S/data/rt/wav16/csd/$(basename "$f")"; done
```

`rt_extract.load`는 파일이 16kHz가 아닐 때만 리샘플하므로, 다른 리샘플러로 만든 사본은 샘플이 달라 확률이 조금 바뀔 수 있다. `wav16/`의 규칙(16kHz 모노)은 지킨다.

### 데이터 구성과 파일 이름

0.5초 미만 파일은 건너뛰고, 특징이 나온 클립은 3223개다(`manifest.csv`의 행 수. CSD는 10초 조각으로 셈). 접기(`fold`)는 ESC-50은 원래 접기, 나머지는 업로더·화자·곡 같은 묶음 단위로 나눈 값이다.

| 출처(`src`) | 개수 | 구성·이름 규칙 |
| --- | --- | --- |
| `esc` | 2000(울음 40, 나머지 1960) | ESC-50 전체. `<원래 접기>-<클립>-<조각>-<분류 번호>.wav` 그대로 |
| `dac` | 457 | Donate-a-Cry 정리본 전체(울음). `<업로더 UUID>-<시각>-<버전>-<성별>-<나이>-<이유>.wav` 그대로. 접기는 UUID 36자의 해시 |
| `axon` | 18 | AxonData 공개 표본 `cry_00001.wav`~. 접기 0(학습에 쓰지 않는 독립 시험) |
| `speech_ko` | 229 | Zeroth-Korean test 457개 중 짝수 번호만. `b<묶음>_<번호>.wav`(예 `b00_000.wav`, `b00_002.wav`, …, `b09_456.wav`). 번호는 000~456의 짝수, 묶음은 번호를 50으로 나눈 몫이다 |
| `speech_en` | 260 | Mini LibriSpeech dev-clean-2에서 화자 26명. `<화자>-<챕터>-<발화>.wav`(예 `1272-135031-0000.wav`). 접기는 화자 단위 |
| `speech_say` | 8 | macOS `say` 합성 `Yuna_1.wav`~`Yuna_8.wav` |
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

결과 숫자와 조건은 [tools/measure/README.md](../measure/README.md#맥-오프라인-평가)의 '맥 오프라인 평가'에 한 곳으로 모았다. 원자료는 이 폴더의 `rt_results.json`(판별층·변형·경보 기준별 재현율과 오경보, 앱 판별층은 `h24`), `rt_outcome.json`(달래기 판정 R1·R2·R3), `rt_stop.json`(첫 경보 지연·그침 판정)이다.

## 데이터 라이선스

ESC-50(CC BY-NC)과 CSD(CC BY-NC-SA)는 비상업 조건이다. 그래서 `cry_head24_rt.json`은 공모전 시연용이며, 상용 배포 전에는 상용 가능한 데이터로 다시 학습한다. 나머지 데이터의 라이선스는 저장소 [NOTICE.md](../../NOTICE.md)의 표를 본다.
