// 중계 공용 도구: id·토큰, 시각, HTTP 응답·오류(1.1), 횟수 제한(4.2 '횟수 창과 만료').
import { type D, DecodeError } from "./protocol";

export const MIN = 60_000;
export const HOUR = 60 * MIN;
export const DAY = 24 * HOUR;

// ---- id·토큰(0장 '공통 표기', 1.2) ----
export const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const ULID_RE = /^[0-9A-HJKMNP-TV-Z]{26}$/;
export const TOKEN_RE = /^at1\.(c_[0-9A-HJKMNP-TV-Z]{26})\.(d_[0-9A-HJKMNP-TV-Z]{26})\.[A-Za-z0-9_-]{43}$/;

/** 무작위 Crockford 글자 n개. 256이 32의 배수라 `& 31`은 치우치지 않는다. */
export const randomCrockford = (n: number) =>
  Array.from(crypto.getRandomValues(new Uint8Array(n)), (b) => CROCKFORD[b & 31]).join("");

export function ulid(now = Date.now()): string {
  let t = now;
  let s = "";
  for (let i = 0; i < 10; i++) {
    s = CROCKFORD[t % 32] + s;
    t = Math.floor(t / 32);
  }
  return s + randomCrockford(16);
}

/** 6자리 연결 코드(앞자리 0 허용). 거절 표본으로 치우침을 없앤다. */
export function sixDigits(): string {
  const cut = 2 ** 32 - (2 ** 32 % 1_000_000);
  for (;;) {
    const [x] = crypto.getRandomValues(new Uint32Array(1));
    if (x < cut) return String(x % 1_000_000).padStart(6, "0");
  }
}

export const b64url = (bytes: ArrayBuffer | Uint8Array) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");

export const newCarId = () => "c_" + randomCrockford(26);
export const newDeviceId = () => "d_" + randomCrockford(26);
export const newToken = (carId: string, deviceId: string) =>
  `at1.${carId}.${deviceId}.${b64url(crypto.getRandomValues(new Uint8Array(32)))}`;

export const sha256 = (s: string) => crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
/** 객체 키를 재귀적으로 정렬한 JSON 문자열(배열 순서는 그대로). 키 순서가 다른 같은 내용이 같은 문자열이 되게 한다. */
export const canonicalJson = (v: unknown): string =>
  JSON.stringify(v, (_k, x) =>
    x && typeof x === "object" && !Array.isArray(x)
      ? Object.fromEntries(Object.entries(x as Record<string, unknown>).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)))
      : x,
  );
export const utf8Length = (s: string) => new TextEncoder().encode(s).length;

/** `Authorization: Bearer <토큰>`에서 토큰. 형식이 틀리면 null. */
export function bearer(header: string | null): { token: string; carId: string; deviceId: string } | null {
  const token = /^Bearer (\S+)$/.exec(header ?? "")?.[1];
  const m = token ? TOKEN_RE.exec(token) : null;
  return m ? { token: token!, carId: m[1], deviceId: m[2] } : null;
}

// ---- 시각 ----
/** 중계가 쓰는 시각은 UTC(`Z`), 초 단위. */
export const iso = (ms: number) => new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z");
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/i;
/** RFC 3339 문자열 → epoch ms. 형식이 아니면 null. */
export function instant(s: unknown): number | null {
  if (typeof s !== "string" || !RFC3339.test(s)) return null;
  const t = Date.parse(s);
  return Number.isNaN(t) ? null : t;
}

// ---- HTTP ----
export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string = code,
    readonly retryAfter?: number,
  ) {
    super(message);
  }
}

/** JSON 응답. 모든 응답에 `Date` 헤더를 단다(1.1 '시각 확인'). */
export function json(status: number, body?: unknown, headers: Record<string, string> = {}): Response {
  const h = new Headers(headers);
  h.set("Date", new Date().toUTCString());
  if (body === undefined) return new Response(null, { status, headers: h });
  h.set("Content-Type", "application/json; charset=utf-8");
  return new Response(JSON.stringify(body), { status, headers: h });
}

/** 오류 본문(1.1). 본문·토큰은 로그에 남기지 않는다(6.6). */
export function errorResponse(e: unknown): Response {
  if (e instanceof HttpError) {
    const h: Record<string, string> = e.retryAfter ? { "Retry-After": String(e.retryAfter) } : {};
    return json(e.status, { error: { code: e.code, message: e.message } }, h);
  }
  console.error("internal", (e as Error)?.name);
  return json(500, { error: { code: "internal", message: "internal" } });
}

/**
 * 본문을 읽으며 바이트를 센다. `Content-Length`가 없어도 상한을 넘는 즉시 읽기를 멈추고 413(6.4).
 */
export async function readBytes(req: Request, max: number): Promise<Uint8Array> {
  if (Number(req.headers.get("Content-Length") ?? 0) > max) throw new HttpError(413, "too_large");
  if (!req.body) return new Uint8Array(0);
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > max) {
      await reader.cancel().catch(() => {});
      throw new HttpError(413, "too_large");
    }
    chunks.push(value);
  }
  const out = new Uint8Array(size);
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.byteLength;
  }
  return out;
}

/** JSON 본문 읽기. 64 KB를 넘으면 413, 깨졌으면 400. */
export async function readJson(req: Request, max = 65_536): Promise<unknown> {
  const text = new TextDecoder().decode(await readBytes(req, max));
  try {
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, "bad_request", "JSON이 깨졌다");
  }
}

export function decode<T>(d: D<T>, v: unknown): T {
  try {
    return d(v, "$");
  } catch (e) {
    if (e instanceof DecodeError) throw new HttpError(400, "bad_request", e.message);
    throw e;
  }
}

// ---- RPC(Durable Object 메서드)는 예외 종류가 넘어가지 않으므로 값으로 돌려준다 ----
export type Result<T> = { ok: T } | { err: { status: number; code: string; message: string; retryAfter?: number } };

export async function rpc<T>(f: () => T | Promise<T>): Promise<Result<T>> {
  try {
    return { ok: await f() };
  } catch (e) {
    if (e instanceof HttpError) return { err: { status: e.status, code: e.code, message: e.message, retryAfter: e.retryAfter } };
    throw e;
  }
}

export function unwrap<T>(r: Result<T>): T {
  if ("err" in r) throw new HttpError(r.err.status, r.err.code, r.err.message, r.err.retryAfter);
  return r.ok;
}

// ---- 횟수 제한(고정 창, 4.2) ----
type Limit = { count: number; expires_at: number };

/** 창 안 횟수가 `limit`에 닿았으면 429를 던진다. `bump`가 거짓이면 보기만 한다. */
export function take(sql: SqlStorage, key: string, limit: number, windowMs: number, now = Date.now(), bump = true) {
  const row = sql.exec<Limit>("SELECT count, expires_at FROM rate_limits WHERE key = ?", key).toArray()[0];
  const live = row !== undefined && now < row.expires_at;
  if (live && row.count >= limit) {
    throw new HttpError(429, "rate_limited", "rate_limited", Math.max(1, Math.ceil((row.expires_at - now) / 1000)));
  }
  if (!bump) return;
  if (live) sql.exec("UPDATE rate_limits SET count = count + 1 WHERE key = ?", key);
  else
    sql.exec(
      "INSERT OR REPLACE INTO rate_limits (key, window_start, count, expires_at) VALUES (?, ?, 1, ?)",
      key,
      now,
      now + windowMs,
    );
}

export const RATE_LIMITS_DDL = `
CREATE TABLE IF NOT EXISTS rate_limits (
  key TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS rate_limits_expires ON rate_limits(expires_at);`;
