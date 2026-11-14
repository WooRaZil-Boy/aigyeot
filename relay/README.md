# 아이곁 중계 서버

차 앱(Pleos Connect)과 휴대폰 앱 사이에서 메시지를 전달하는 서버입니다. Cloudflare Workers와 Durable Objects(SQLite)로 만들었습니다.
차마다 Durable Object 하나(`CarRoom`)가 기기·메시지함·노래 파일·푸시 작업을 맡고, 전역 Durable Object 하나(`Directory`)가 연결·초대 코드와 IP별 횟수 제한을 맡습니다.
상황판 질문은 중계가 Claude API를 대신 불러, API 키를 앱에 넣지 않습니다.

## 로컬 실행

```sh
npm ci
npm run dev        # wrangler dev, http://127.0.0.1:8787
```

Android 에뮬레이터에서는 `http://10.0.2.2:8787`로 닿습니다. Claude 호출과 푸시는 비밀값이 있어야 동작합니다. `dev.vars.example`을 `.dev.vars`로 복사해 값을 넣습니다. 비밀값이 없어도 나머지 API는 동작하고, `GET /v1/health`가 `llm`·`push` 여부를 알려 줍니다.

## 시험

```sh
npm run typecheck
npm test
```

`vitest`가 Workers 런타임 안에서 돌고, Anthropic과 Google 호출은 `fetch`를 가짜로 바꿔 시험합니다.

## 배포

자기 Cloudflare 계정에 배포합니다.

```sh
npx wrangler login
npx wrangler secret put ANTHROPIC_API_KEY
npx wrangler secret put FCM_PROJECT_ID
npx wrangler secret put FCM_CLIENT_EMAIL
npx wrangler secret put FCM_PRIVATE_KEY
npm run deploy     # wrangler deploy
```

- `ANTHROPIC_API_KEY`가 없으면 `POST /v1/llm`이 503을 돌려줍니다.
- `FCM_*` 세 값(서비스 계정의 project id, client email, private key)이 모두 있어야 인계 알림 푸시를 보냅니다.
- 사용할 모델은 `wrangler.jsonc`의 `LLM_MODEL`입니다.
- 세 앱의 release 빌드는 이미 배포해 둔 중계 주소를 가리킵니다. 자기 Worker에 붙이려면 주소를 바꿔 앱을 다시 빌드합니다: 차 앱은 `app/src/main/kotlin/kr/aigyeot/app/link/Relay.kt`의 `RelayAddress.RELEASE`, 안드로이드 휴대폰 앱은 Gradle 속성 `-Paigyeot.relayUrl=https://…`(debug는 `aigyeot.relayUrl.debug`), iOS 휴대폰 앱은 Xcode 빌드 설정 `RELAY_BASE_URL`(Release 구성. 원본은 `phone/ios/project.yml`)입니다.

## 경로

`Authorization: Bearer <토큰>`이 필요한 경로는 차(`car`), 폰 소유자(`owner`), 보호자(`guardian`) 중 아래 역할만 부를 수 있습니다.

| 경로 | 역할 | 설명 |
| --- | --- | --- |
| `GET /v1/health` | 없음 | 비밀값 설정 여부 |
| `POST /v1/cars` | 없음 | 차 등록(IP당 시간당 5회) |
| `POST /v1/pair` | 없음 | 연결·초대 코드로 폰 붙이기 |
| `POST /v1/llm` | car | 상황판 질문을 Claude로 전달 |
| `POST /v1/pair-codes`, `DELETE /v1/pair-codes` | car | 연결 코드 만들기, 지우기 |
| `POST /v1/invites` | owner | 보호자 초대 코드 만들기 |
| `GET /v1/devices` | car, owner | 기기 목록 |
| `PATCH /v1/devices/me` | owner, guardian | 푸시 토큰(두 역할 모두), 인계 알림 설정(guardian만. owner가 보내면 403) |
| `DELETE /v1/devices/me` | owner, guardian | 스스로 연결 끊기 |
| `DELETE /v1/devices/:id` | car | 폰 연결 끊기(대상이 차이거나 없으면 404) |
| `POST /v1/messages` | car, owner | 메시지 보내기(역할마다 보낼 수 있는 type이 다름: `src/message-rules.ts`의 `SENDS`) |
| `GET /v1/inbox` | 모두 | 받은 함 읽기(`after_seq`, `limit`) |
| `POST /v1/ack` | 모두 | 받은 메시지 확인 |
| `GET /v1/ws` | 모두 | WebSocket으로 실시간 받기 |
| `PUT /v1/songs/:id` | owner | 노래 파일 올리기 |
| `GET /v1/songs/:id` | car | 노래 파일 받기 |
| `DELETE /v1/records` | car | 리포트·인계 알림 보관분 지우기 |

메시지 JSON 모양은 `shared/src/test/resources/protocol/`의 예제와 `src/protocol.ts`의 검사기가 기준입니다.
