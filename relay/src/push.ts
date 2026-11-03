// FCM HTTP v1 발송(계약 5.2). 서비스 계정 JWT를 WebCrypto로 서명해 접근 토큰으로 바꾼다.
import { b64url } from "./util";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

export const pushConfigured = (env: Env) => Boolean(env.FCM_PROJECT_ID && env.FCM_CLIENT_EMAIL && env.FCM_PRIVATE_KEY);

// 접근 토큰은 55분 다시 쓴다. 방이 쉬면(Hibernation) 사라져 다시 받는다.
let cached: { token: string; until: number } | undefined;
/** 시험용: 저장한 접근 토큰을 잊는다. */
export const forgetAccessToken = () => {
  cached = undefined;
};

const enc = (s: string) => new TextEncoder().encode(s);

function pemToDer(pem: string): Uint8Array {
  const b64 = pem
    .replace(/\\n/g, "\n")
    .replace(/-----[^-]+-----/g, "")
    .replace(/\s+/g, "");
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

async function accessToken(env: Env, now: number): Promise<string> {
  if (cached && now < cached.until) return cached.token;
  const iat = Math.floor(now / 1000);
  const head = b64url(enc(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claim = b64url(
    enc(JSON.stringify({ iss: env.FCM_CLIENT_EMAIL, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600 })),
  );
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(env.FCM_PRIVATE_KEY!),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc(`${head}.${claim}`));
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${head}.${claim}.${b64url(sig)}`,
    }),
  });
  if (!res.ok) throw new Error(`oauth ${res.status}`);
  const { access_token } = (await res.json()) as { access_token: string };
  cached = { token: access_token, until: now + 55 * 60_000 };
  return access_token;
}

/** `expiresAt` = occurred_at + 30분(epoch ms, G28 고정). */
export type PushJob = { msgId: string; token: string; text: string; expiresAt: number };
/**
 * status 0 = 네트워크 오류, -1 = 보내기 직전에 유효 시간이 1초 미만, -2 = 보내기 직전에 작업이 없어짐(끊김·ack·토큰 지움).
 * `token`은 실제로 보낸 기기 토큰(무효 응답 때 같은 토큰일 때만 지운다, L51). `errorCode`는 FCM `details[].errorCode`.
 */
export type PushResult = { status: number; token?: string; errorCode?: string; retryAfterS?: number };

/** FCM 요청 본문. `ttl`은 남은 시간을 내림한 초, `apns-expiration`은 고정 만료의 epoch 초(5.2). */
function message(job: PushJob, leftMs: number) {
  return JSON.stringify({
    message: {
      token: job.token,
      notification: { title: "아이곁", body: job.text },
      data: { msg_id: job.msgId, type: "handoff_alert" },
      android: {
        priority: "high",
        ttl: `${Math.floor(leftMs / 1000)}s`,
        notification: { channel_id: "handoff_alert", tag: job.msgId },
      },
      apns: {
        headers: {
          "apns-priority": "10",
          "apns-push-type": "alert",
          "apns-collapse-id": job.msgId,
          "apns-expiration": String(Math.floor(job.expiresAt / 1000)),
        },
        payload: { aps: { sound: "default", "thread-id": "handoff_alert" } },
      },
    },
  });
}

/** `Retry-After`: 초 또는 HTTP 날짜(RFC 9110). 날짜면 지금부터의 대기 초로 바꾼다. 읽지 못하면 undefined. */
export function retryAfterS(value: string | null, now = Date.now()): number | undefined {
  if (!value) return undefined;
  const v = value.trim();
  if (/^\d+$/.test(v)) return Number(v) > 0 ? Number(v) : undefined;
  const at = Date.parse(v);
  return Number.isNaN(at) ? undefined : Math.max(1, Math.ceil((at - now) / 1000));
}

/**
 * `read`는 보내기 직전에 작업을 다시 읽는다. 접근 토큰을 기다리는 동안 방은 다른 요청(끊기·ack)을 처리하므로,
 * 토큰을 얻은 뒤(401 재발송 포함) `read`와 fetch 사이에는 다른 await를 두지 않는다.
 */
export async function sendPush(env: Env, read: () => PushJob | null): Promise<PushResult> {
  const url = `https://fcm.googleapis.com/v1/projects/${env.FCM_PROJECT_ID}/messages:send`;
  for (let attempt = 0; ; attempt++) {
    let res: Response;
    let job: PushJob | null;
    try {
      const auth = `Bearer ${await accessToken(env, Date.now())}`;
      job = read();
      if (!job) return { status: -2 };
      // 실제 요청 직전에 남은 시간을 다시 구한다(5.1).
      const left = job.expiresAt - Date.now();
      if (left < 1000) return { status: -1 };
      res = await fetch(url, {
        method: "POST",
        headers: { Authorization: auth, "Content-Type": "application/json" },
        body: message(job, left),
      });
    } catch {
      return { status: 0 };
    }
    if (res.ok) return { status: res.status, token: job.token };
    const err = (await res.json().catch(() => null)) as {
      error?: { details?: { errorCode?: string }[] };
    } | null;
    const errorCode = err?.error?.details?.find((d) => d.errorCode)?.errorCode;
    // 401(OAuth 만료)이면 접근 토큰을 새로 받아 한 번 더(5.5). APNs 키 문제는 다시 보내지 않는다.
    if (res.status === 401 && errorCode !== "THIRD_PARTY_AUTH_ERROR" && attempt === 0) {
      cached = undefined;
      continue;
    }
    return { status: res.status, token: job.token, errorCode, retryAfterS: retryAfterS(res.headers.get("Retry-After")) };
  }
}
