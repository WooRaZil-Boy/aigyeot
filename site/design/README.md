# 아이곁 디자인 시안

차(Pleos Connect)·안드로이드·iOS 세 앱을 한 디자인 언어로 그린 시안입니다. 앱을 구현하기 전에 화면 구성과 색·글자·움직임 규칙을 정하려고 만들었고, 세 앱은 이 시안을 기준으로 구현했습니다.

## 여는 법

- `index.html`을 브라우저에서 엽니다(더블클릭). 서버나 인터넷 연결 없이 열립니다.
- 위쪽 막대에서 탭(한 장면·차·안드로이드·iOS·시스템), 테마(자동·라이트·다크), 모션(켜기·줄이기)을 바꿉니다.
- 주소 뒤에 `?tab=system&theme=dark`처럼 붙여 원하는 탭과 테마로 바로 열 수도 있습니다.

## 탭별 내용

| 탭 | 보여 주는 것 |
| --- | --- |
| 한 장면 | 울음 한 번이 차 화면과 두 휴대폰에서 어떻게 이어지는지 단계별로(재생·멈춤·앞뒤 단계) |
| 차 | Pleos Connect 차 앱의 홈·리포트·설정·연결 화면, 출발 전부터 정차 뒤 물어보기까지 13단계 상태 흐름과 단계별 3D 실내, 정차·주행 보기, 전체·분할 창 |
| 안드로이드 · iOS | 보호자 휴대폰 앱의 오늘·기록·주행 리포트·잠금 화면·알림, 라이트·다크. 왼쪽 큰 화면은 눌러 볼 수 있습니다 |
| 시스템 | 앱 아이콘, 색 토큰, 글자, 달래기 방법·돌봄 종류 아이콘, 실시간 3D 차, 재질, 모션 |

## 디자인 원칙

- **빛 두 가지:** 아이 쪽 신호는 주황, 차와 보호자의 응답은 파랑으로 나타내고 나머지는 무채색으로 둡니다. 같은 두 빛을 차 실내, 휴대폰 카드, 앱 아이콘에 똑같이 씁니다.
- **한눈에 읽히는 차 화면:** 운전자가 짧은 시간에 파악하도록 핵심 정보만 크게 보이고, 주행 중에는 조작할 것을 두지 않습니다([Pleos Connect UI Design Guide › 즉시성](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro#%EC%A6%89%EC%8B%9C%EC%84%B1-glanceability)).
- **세 앱이 같은 값:** 색·글자·모서리·모션 토큰의 이름과 값을 세 앱이 함께 씁니다. 앱 저장소의 [`shared/src/test/resources/design/tokens.json`](../../shared/src/test/resources/design/tokens.json) 한 곳에 두고, 차 앱·안드로이드·iOS의 시험이 각 앱의 테마 코드와 대조합니다. 글자와 바탕의 대비는 4.5:1 이상입니다.
- **3D 차:** 직접 만든 세단 모델(엠블럼·차명 없음, 외부 모델·텍스처 없음) 하나를 세 앱이 실시간으로 그립니다. 부품 이름은 Pleos의 glTF 부품 이름 규칙을 따릅니다([3D Assets](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/contents-creation/3d-contents)).

## 시안과 실제 앱

- 화면 속 시각·거리·이름·성적 숫자는 모두 예시입니다.
- 구현 전에 확정한 시안이라 실제 앱과 배치·문구가 세부에서 다를 수 있습니다. 실제 동작은 저장소 [README](../../README.md)의 화면을 기준으로 봐 주세요.

## 파일과 라이선스

- `index.html`, `css/`, `js/`, `assets/brand/`(앱 아이콘)는 아이곁이 만든 것이며 저장소 [LICENSE](../../LICENSE)를 따릅니다. `js/`는 주석을 걷고 공백을 줄인 공개본입니다. `js/gen-car.js`는 차 앱에 들어 있는 3D 모델(`app/src/main/assets/car/aigyeot_car.glb`)과 같은 파일을 브라우저에서 바로 읽도록 담은 것입니다.
- 함께 넣은 오픈소스는 다음과 같습니다.

| 이름 | 버전 | 라이선스 |
| --- | --- | --- |
| three.js(GLTFLoader·RoomEnvironment·OrbitControls 포함) | 0.170.0 | MIT, [`vendor/three/LICENSE`](vendor/three/LICENSE) |
| Motion | 14.4.0 | MIT, [`vendor/motion/LICENSE.md`](vendor/motion/LICENSE.md) |
| Pretendard Variable | 1.3.9 | SIL OFL 1.1, [`vendor/pretendard/LICENSE.txt`](vendor/pretendard/LICENSE.txt) |
| Lucide 아이콘(`js/gen-icons.js`에 포함) | — | ISC, [`vendor/lucide/LICENSE`](vendor/lucide/LICENSE) |
