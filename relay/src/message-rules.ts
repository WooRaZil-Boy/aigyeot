// 메시지 정책: 역할별로 보낼 수 있는 type, 봉투 검사, 중계가 직접 검사·고치는 body 필드.
import { Envelope, bodySchemas } from "./protocol";
import { HttpError, ULID_RE, decode, instant, iso } from "./util";

/** 역할별로 `POST /v1/messages`로 보낼 수 있는 type. 표에 없으면 403. */
export const SENDS: Record<"car" | "owner", readonly string[]> = {
  car: ["song_selected", "song_status", "feeding_state", "report_summary", "handoff_alert"],
  owner: ["feeding_started", "song_added", "song_removed", "song_selected", "state_request", "care_summary"],
};
const CAR_SENT = new Set(SENDS.car);

/** 저장한 메시지의 `from.role`. 보낸 기기가 지워져도 type으로 정해진다(차가 보낸 song_selected는 받는 쪽이 없다). */
export const senderRole = (type: string, sender: string | null) =>
  sender === null ? "relay" : CAR_SENT.has(type) && type !== "song_selected" ? "car" : "owner";

/** 봉투 검사(순서: 형식 → 버전 → type). */
export function decodeEnvelope(v: unknown): Envelope {
  const env = decode(Envelope, v);
  if (env.v !== 1) throw new HttpError(400, "unsupported_version");
  if (!(env.type in bodySchemas)) throw new HttpError(400, "unknown_type");
  if (!ULID_RE.test(env.id)) throw new HttpError(400, "bad_request", "id가 ULID가 아님");
  return env;
}

/** 중계가 쓰는 body 필드만 검사한다. 나머지는 받는 쪽이 검사한다. */
export function checkOwnFields(env: Envelope) {
  const b = env.body;
  const bad = (what: string) => {
    throw new HttpError(400, "bad_request", what);
  };
  switch (env.type) {
    case "feeding_started":
      if (instant(b.at) === null) bad("at");
      break;
    case "song_added":
    case "song_status":
      if (typeof b.song_id !== "string" || !ULID_RE.test(b.song_id)) bad("song_id");
      break;
    case "care_summary": {
      // 예외로 body 전체를 검사한다. 먼저 필수 칸·형(정수·bool·문자열)을 본 뒤 값을 본다.
      const c = decode(bodySchemas.care_summary, b) as Record<string, unknown>;
      // zone은 IANA 시간대 ID여야 한다(64자 이하, Intl이 받아야 한다)
      const zone = c.zone as string;
      try {
        if (zone.length > 64) throw new Error("long");
        new Intl.DateTimeFormat("en-US", { timeZone: zone });
      } catch {
        bad("zone");
      }
      for (const k of ["as_of", "last_sleep_start_at", "last_sleep_end_at", "sleeping_since", "last_diaper_at"]) {
        if (c[k] !== null && instant(c[k]) === null) bad(k);
      }
      const ints = ["today_sleep_min", "night_wakeups", "today_diaper_count", "today_feed_count"];
      for (const k of ints) if (c[k] !== null && ((c[k] as number) < 0 || (c[k] as number) > 2147483647)) bad(k);
      const sd = [
        "last_sleep_start_at",
        "last_sleep_end_at",
        "sleeping_since",
        "today_sleep_min",
        "night_wakeups",
        "last_diaper_at",
        "today_diaper_count",
      ];
      if (c.sleep_diaper_enabled === false && sd.some((k) => c[k] !== null)) bad("sleep_diaper_enabled");
      env.body = c; // decode가 모르는 칸을 버린 객체만 저장·전달한다
      break;
    }
    case "handoff_alert":
      if (instant(b.occurred_at) === null) bad("occurred_at");
      if (typeof b.text !== "string" || [...b.text].length > 80) bad("text");
      break;
  }
}

export const sameBytes = (a: ArrayBuffer, b: ArrayBuffer) => {
  const x = new Uint8Array(a);
  const y = new Uint8Array(b);
  return x.length === y.length && x.every((v, i) => v === y[i]);
};

/** 중계가 고치는 body 필드: 미래 수유 시각은 처음 받은 시각으로 고정한다. */
export function fixBody(type: string, body: Record<string, unknown>, receivedAt: number) {
  return type === "feeding_started" && instant(body.at)! > receivedAt ? { ...body, at: iso(receivedAt) } : body;
}
