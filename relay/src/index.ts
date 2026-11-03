// 중계 Worker(계약 0장): 경로 나누기, 토큰에서 차 id 꺼내기, Claude API 호출(④ 상황판 대리).
// 판정(토큰 해시·역할)은 차 방이 한다.
import { CarRoom } from "./car-room";
import { Directory } from "./directory";
import { askClaude } from "./llm";
import { LlmRequest, PairRequest, RegisterCarRequest } from "./protocol";
import { pushConfigured } from "./push";
import {
  DAY,
  HOUR,
  HttpError,
  bearer,
  decode,
  errorResponse,
  json,
  newCarId,
  newDeviceId,
  newToken,
  readJson,
  sha256,
  unwrap,
  utf8Length,
} from "./util";

export { CarRoom, Directory };
const VERSION = "0.1.0"; // 진입 모듈은 처리기·Durable Object 클래스만 내보낼 수 있다

const room = (env: Env, carId: string) => env.CAR_ROOM.get(env.CAR_ROOM.idFromName(carId));
const directory = (env: Env) => env.DIRECTORY.get(env.DIRECTORY.idFromName("directory"));
// IP는 제한 창 동안만 안내 방에 둔다. 로그에는 남기지 않는다(4.3, 6.6).
const clientIp = (req: Request) => req.headers.get("CF-Connecting-IP") ?? "local";

/** 공백·하이픈을 지우고 대문자로. 6자리 숫자면 연결 코드, 아니면 Crockford 규칙을 적용한 초대 10자(1.4). */
function normalizeCode(raw: string): string {
  const s = raw.replace(/[\s-]/g, "").toUpperCase();
  if (/^\d{6}$/.test(s)) return s;
  const c = s.replace(/O/g, "0").replace(/[IL]/g, "1");
  if (/^[0-9A-HJKMNP-TV-Z]{10}$/.test(c)) return c;
  throw new HttpError(400, "bad_code_format");
}

async function registerCar(req: Request, env: Env) {
  const b = decode(RegisterCarRequest, await readJson(req));
  if (b.platform !== "pleos") throw new HttpError(400, "bad_request", "차 platform은 pleos");
  unwrap(await directory(env).rate(`reg:ip:${clientIp(req)}`, 5, HOUR)); // G7
  const carId = newCarId();
  const deviceId = newDeviceId();
  const token = newToken(carId, deviceId);
  await room(env, carId).init(carId, deviceId, await sha256(token));
  return json(201, { car_id: carId, device_id: deviceId, car_token: token });
}

async function pair(req: Request, env: Env) {
  const b = decode(PairRequest, await readJson(req));
  if (b.platform === "pleos") throw new HttpError(400, "bad_request", "폰 platform은 android·ios");
  const code = normalizeCode(b.code);
  const install = req.headers.get("X-Install-Id");
  const found = unwrap(
    await directory(env).consumeCode(code, clientIp(req), install && install.length <= 64 ? install : null),
  );
  const res = unwrap(await room(env, found.carId).pair(found.kind, found.createdBy, b.platform, b.push_token));
  return json(201, res);
}

async function llm(req: Request, env: Env, authorization: string, carId: string) {
  unwrap(await room(env, carId).llmGate(authorization));
  const b = decode(LlmRequest, await readJson(req));
  const qLen = [...b.question].length;
  if (qLen < 1 || qLen > 200) throw new HttpError(400, "bad_request", "question은 1~200자");
  if (utf8Length(JSON.stringify(b.state)) > 4096) throw new HttpError(400, "bad_request", "state는 4 KB 이하");
  if (!env.ANTHROPIC_API_KEY) throw new HttpError(503, "llm_unconfigured");
  unwrap(await directory(env).rate("llm:all:day", 2000, DAY));
  return json(200, await askClaude(env, b));
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      const { pathname } = new URL(req.url);
      const route = `${req.method} ${pathname}`;
      if (route === "GET /v1/health") {
        // 비밀값이 들어 있는지만 알린다. 바깥 서비스를 부르지 않는다.
        return json(200, { ok: true, version: VERSION, llm: Boolean(env.ANTHROPIC_API_KEY), push: pushConfigured(env) });
      }
      if (route === "POST /v1/cars") return await registerCar(req, env);
      if (route === "POST /v1/pair") return await pair(req, env);
      if (!pathname.startsWith("/v1/")) throw new HttpError(404, "not_found");

      const authorization = req.headers.get("Authorization");
      const b = bearer(authorization);
      if (!b) throw new HttpError(401, "unauthorized");
      if (route === "POST /v1/llm") return await llm(req, env, authorization!, b.carId);
      return await room(env, b.carId).fetch(req);
    } catch (e) {
      return errorResponse(e);
    }
  },
} satisfies ExportedHandler<Env>;
