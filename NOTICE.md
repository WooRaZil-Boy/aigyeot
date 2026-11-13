# 출처 고지와 학습 데이터 라이선스

- **자장가:** 브람스 「자장가」 Op.49 No.4(퍼블릭 도메인). MIDI: Rabanus Flavus(Peter Gerloff)의 편곡, CC0, Wikimedia Commons [Guten Abend, gut Nacht (Wiegenlied).mid](https://commons.wikimedia.org/wiki/File:Guten_Abend,_gut_Nacht_%28Wiegenlied%29.mid). 음색: FluidR3_GM(Frank Wen, MIT). −30 LUFS로 맞춰 렌더링함.
- **뱃속 소리:** 직접 합성한 소리(`tools/sound/womb.py`). 외부 음원을 쓰지 않았습니다. −30 LUFS로 맞춰 렌더링함.
- **휴게소·졸음쉼터 정보:** 공공데이터포털 「전국휴게소정보표준데이터」(15025446)·「전국졸음쉼터표준데이터」(15028203), 한국도로공사, 포털 수정일 2026-09-21·2026-03-05, 이용허락범위 제한 없음.
- **울음 판별 바탕 모델:** YAMNet(TensorFlow Model Garden, Apache 2.0)과 MediaPipe tasks-audio(Apache 2.0). Apache 2.0 전문은 [app/src/main/assets/licenses/Apache-2.0.txt](app/src/main/assets/licenses/Apache-2.0.txt)에 있습니다.
- **AudioSet 온톨로지:** YAMNet 모델 파일에 들어 있는 클래스 이름은 AudioSet 온톨로지(CC BY-SA 4.0)에서 왔습니다.
- **판별층:** YAMNet 점수 위에 얹은 판별층의 학습·평가 데이터와 라이선스는 아래 표에 있습니다.
- **말로 묻기의 LLM 답:** 중계가 외부 LLM API를 부릅니다(모델은 `relay/wrangler.jsonc`의 `LLM_MODEL`). 상태값과 운전자 질문 글자(아이 별명은 '아기'로 바꿈)만 보내고, 키는 중계에만 둡니다.
- **서체:** Pretendard Variable 1.3.9(SIL OFL 1.1)를 세 앱에 넣었습니다. 전문은 [app/src/main/assets/licenses/Pretendard-OFL.txt](app/src/main/assets/licenses/Pretendard-OFL.txt)에 있습니다.
- **아이콘:** Lucide(ISC, Feather에서 온 일부 아이콘은 MIT). 두 전문 모두 [app/src/main/assets/licenses/Lucide-ISC.txt](app/src/main/assets/licenses/Lucide-ISC.txt)에 있습니다.
- **3D 차:** 차 앱과 안드로이드 휴대폰 앱은 Filament 1.77.3(Apache 2.0)으로, iOS 휴대폰 앱은 RealityKit으로 그립니다. 차 모델은 직접 만들었습니다(엠블럼 없음, 외부 모델·텍스처 파일을 쓰지 않음).
- **오픈소스 고지 화면:** 안드로이드·iOS 휴대폰 앱에는 앱 안에 오픈소스 라이선스 화면이 있습니다. 차 앱에는 고지 화면이 없고, 라이선스 전문(Apache 2.0, Lucide ISC·MIT, Pretendard OFL)만 앱 assets에 있습니다.
- **함께 들어간 라이브러리:** 차 앱 APK에는 MediaPipe가 함께 넣는 protobuf-javalite(BSD-3-Clause)가 들어 있습니다. 전문은 [phone/android/src/main/assets/licenses/BSD-3-Clause-protobuf.txt](phone/android/src/main/assets/licenses/BSD-3-Clause-protobuf.txt)에 있습니다. 두 안드로이드 앱에는 OkHttp가 함께 넣는 [Public Suffix List](https://github.com/publicsuffix/list)(MPL-2.0)가 들어 있습니다. 그 밖의 라이브러리 고지는 휴대폰 앱의 오픈소스 라이선스 화면(안드로이드 `phone/android/src/main/assets/licenses/oss.json`, iOS `phone/ios/Aigyeot/Licenses/`)에 있습니다.

## 학습 데이터와 라이선스

판별층 학습과 평가에 쓴 데이터입니다(AxonData는 평가에만 씀). 비상업 조건 데이터가 들어 있어 이 판별층은 공모전 시연용입니다. 상용 배포 전에는 상용 가능한 데이터로 다시 학습합니다. 라이선스는 2026-11-02~13에 각 배포처의 표기를 확인했습니다.

| 데이터 | 쓰임 | 라이선스 |
| --- | --- | --- |
| [ESC-50](https://github.com/karolpiczak/ESC-50) | 울음·다른 소리 | CC BY-NC |
| [Donate-a-Cry corpus](https://github.com/gveres/donateacry-corpus) | 울음 | GitHub 라이선스 항목 없음. 저장소 README에 ODbL 1.0(데이터베이스)·DbCL 1.0(내용)이라고 적혀 있음 |
| [AxonData Infant Cry Detection Dataset](https://huggingface.co/datasets/AxonData/infant-cry-detection-dataset)(공개 표본) | 처음 듣는 울음(평가에만 씀) | 메타데이터 표기는 CC BY 4.0이지만, 설명 본문은 공개 표본을 평가·시험용이라 하고 상업 사용은 유료 전체본으로 안내해 서로 어긋남(그래서 녹음을 배포하지 않음) |
| [DEMAND](https://zenodo.org/records/1227121)(TCAR) | 차 실내 소음 | Zenodo 메타데이터는 CC BY 4.0, 설명 본문은 CC BY-SA 3.0(표기가 서로 다름) |
| [CSD: Children's Song Dataset](https://zenodo.org/records/4785016) | 노래 | CC BY-NC-SA |
| [MUSDB18 7초 발췌](https://zenodo.org/records/3270814) | 음악 | Zenodo 기록의 license는 CC BY 4.0, 공개(open)(원곡별 권리는 확인하지 못함) |
| [Zeroth-Korean](https://huggingface.co/datasets/Bingsu/zeroth-korean)(test) | 한국어 말소리 | CC BY 4.0 |
| [Mini LibriSpeech](https://www.openslr.org/31/)(dev-clean-2) | 영어 말소리 | CC BY 4.0 |
| macOS `say` 합성 | 한국어 말소리 8개 | 직접 합성(공개 배포 안 함) |
