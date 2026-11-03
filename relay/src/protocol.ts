// 연결 계약 1.4·3장의 JSON 모양. shared/의 Kotlin 타입과 같은 것을 런타임 검사기로 적는다.
// 검사기는 계약 표 순서로 새 객체를 만들고 모르는 필드는 버린다(계약 '공통 표기': TypeScript는 검사 뒤 버림).
// "또는 null"은 nullable(필드가 꼭 있어야 함), "선택"은 opt(빠질 수 있음)다.

export class DecodeError extends Error {}

export type D<T> = (v: unknown, at: string) => T;
type Opt<T> = D<T | undefined> & { optional: true };

const fail = (at: string, what: string): never => {
  throw new DecodeError(`${at}: ${what}`);
};
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

export const str: D<string> = (v, at) => (typeof v === "string" ? v : fail(at, "문자열이 아님"));
export const num: D<number> = (v, at) => (typeof v === "number" && Number.isFinite(v) ? v : fail(at, "숫자가 아님"));
export const int: D<number> = (v, at) => (Number.isSafeInteger(v) ? (v as number) : fail(at, "정수가 아님"));
export const bool: D<boolean> = (v, at) => (typeof v === "boolean" ? v : fail(at, "bool이 아님"));
export const anyObj: D<Record<string, unknown>> = (v, at) => (isObj(v) ? v : fail(at, "객체가 아님"));
export const enm =
  <const T extends string>(...values: T[]): D<T> =>
  (v, at) =>
    values.includes(v as T) ? (v as T) : fail(at, `모르는 값 ${JSON.stringify(v)}`);
export const nullable =
  <T>(d: D<T>): D<T | null> =>
  (v, at) =>
    v === null ? null : d(v, at);
export const arr =
  <T>(d: D<T>): D<T[]> =>
  (v, at) =>
    Array.isArray(v) ? v.map((x, i) => d(x, `${at}[${i}]`)) : fail(at, "배열이 아님");
export const opt = <T>(d: D<T>): Opt<T> =>
  Object.assign((v: unknown, at: string) => (v === undefined ? undefined : d(v, at)), { optional: true as const });

type Shape = Record<string, D<unknown>>;
type Out<S extends Shape> = {
  [K in keyof S as S[K] extends { optional: true } ? never : K]: ReturnType<S[K]>;
} & {
  [K in keyof S as S[K] extends { optional: true } ? K : never]?: Exclude<ReturnType<S[K]>, undefined>;
};
export const obj =
  <S extends Shape>(shape: S): D<Out<S>> =>
  (v, at) => {
    if (!isObj(v)) fail(at, "객체가 아님");
    const out: Record<string, unknown> = {};
    for (const [k, d] of Object.entries(shape)) {
      const x = d((v as Record<string, unknown>)[k], `${at}.${k}`);
      if (x !== undefined) out[k] = x;
    }
    return out as Out<S>;
  };
export type Infer<X> = X extends D<infer T> ? T : never;

// ---- 공통 열거(0장) ----
export const Role = enm("car", "owner", "guardian", "relay");
export const Platform = enm("pleos", "android", "ios");
const PhoneRole = enm("owner", "guardian");
const PhonePlatform = enm("android", "ios");
const SoundMethod = enm("white_noise", "lullaby", "parent_song");
const EpisodeResult = enm("success", "failure", "undetermined");
const HandoffKind = enm("rest_offer", "arrival_notice", "no_rest", "parked");

// ---- 봉투(3.1) ----
export const Envelope = obj({
  v: int,
  id: str,
  seq: opt(int),
  type: str,
  car: opt(str),
  to: opt(str),
  from: opt(obj({ role: Role, device: nullable(str) })),
  ts: str,
  body: anyObj,
});
export type Envelope = Infer<typeof Envelope>;

// ---- body(3.3) ----
const Empty = obj({});
const SongRef = obj({ song_id: str });
const SoundRef = obj({ method: SoundMethod, label: str });
const MethodStat = obj({ method: SoundMethod, label: str, successes: int, attempts: int });

export const HandoffAlertBody = obj({
  text: str,
  kind: HandoffKind,
  rest_name: nullable(str),
  minutes_to_arrival: nullable(int),
  occurred_at: str,
  session_id: str,
  episode_id: str,
});
export const AckBody = obj({ ids: arr(str) });

const ReportSummaryBody = obj({
  session_id: str,
  started_at: str,
  ended_at: str,
  simulated: bool,
  output_route: enm("rear_zone", "default_zone_fallback", "no_playback"),
  truncated: opt(bool),
  timeline: arr(
    obj({
      episode_id: str,
      trigger: enm("cry_alarm", "switch_sound"),
      started_at: str,
      alarm_latency_s: nullable(num),
      sound: nullable(SoundRef),
      play_s: int,
      plays: arr(
        obj({
          purpose: enm("auto_first", "auto_second", "request"),
          method: SoundMethod,
          song_id: nullable(str),
          started_at: str,
          ended_at: str,
          play_s: int,
        }),
      ),
      result: nullable(EpisodeResult),
      excluded_reason: nullable(
        enm(
          "driver_stop",
          "call",
          "gear_to_park",
          "started_in_park",
          "switch_sound",
          "session_cap",
          "feeding_window",
          "mic_error",
          "focus_loss",
          "detector_timeout",
          "input_silenced",
          "suspend",
          "session_end",
        ),
      ),
      recried: bool,
      second: nullable(
        obj({
          action: enm("comfort_cool", "feeding_window", "other_sound", "focus_denied"),
          sound: nullable(SoundRef),
          result: nullable(EpisodeResult),
        }),
      ),
      stop: nullable(
        obj({
          handoff: bool,
          reason: enm("attempt_limit", "session_cap", "feeding_window", "no_sound"),
          mode: HandoffKind,
          rest_name: nullable(str),
          waypoint_added: bool,
          guardian_notified: bool,
        }),
      ),
      total_cry_s: int,
    }),
  ),
  pre_cry_context: arr(
    obj({
      episode_id: str,
      window_s: int,
      speed_kmh_avg: num,
      speed_kmh_max: num,
      brake_presses: int,
      steering_reversals: int,
      turn_signal_used: bool,
      gear: enm("P", "R", "N", "D"),
      cabin_temp_c: nullable(num),
      window_open: nullable(bool),
      playing: bool,
    }),
  ),
  steering: obj({
    available: bool,
    baseline_per_min: nullable(num),
    during_cry_per_min: nullable(num),
    after_response_per_min: nullable(num),
    during_cry_z: nullable(num),
    unavailable_reason: nullable(enm("no_comparable_window", "no_cry")),
  }),
  car_actions: arr(
    obj({
      action: enm(
        "rear_playback",
        "default_zone_playback",
        "seat_heater_off",
        "rear_window_lock",
        "cabin_cool",
        "reading_light_off",
        "waypoint_added",
        "arrival_notice",
        "guardian_alert",
      ),
      count: int,
      total_s: nullable(int),
    }),
  ),
  controls: arr(obj({ api: str, target: str, attempts: int, succeeded: int, failed: int, retries: int })),
  method_stats: opt(arr(MethodStat)),
});

/** 메시지 type → body 검사기(3.2 표의 type 전부). Kotlin `bodySerializers`와 같다. */
export const bodySchemas: Record<string, D<unknown>> = {
  paired: obj({ device_id: str, role: PhoneRole, platform: PhonePlatform, paired_at: str, handoff_alerts: bool }),
  unpaired: obj({ device_id: str, role: PhoneRole, by: enm("car", "self", "relay") }),
  feeding_started: obj({ at: str }),
  song_added: obj({ song_id: str, name: str, duration_s: num }),
  song_removed: SongRef,
  song_selected: SongRef,
  invite_created: Empty,
  state_request: Empty,
  song_status: obj({
    song_id: str,
    name: nullable(str),
    status: enm("accepted", "rejected", "removed"),
    reason: nullable(
      enm("peak_limit", "silent", "bad_format", "too_long", "too_large", "limit_reached", "download_failed"),
    ),
    duration_s: nullable(num),
    candidate: bool,
  }),
  feeding_state: obj({ last_feeding_start: nullable(str), window_start_expected: nullable(str) }),
  report_summary: ReportSummaryBody,
  handoff_alert: HandoffAlertBody,
  ack: AckBody,
};

// ---- 중계 HTTP API(1.4) ----
export const ErrorResponse = obj({ error: obj({ code: str, message: str }) });
export const HealthResponse = obj({ ok: bool, version: str, llm: bool, push: bool });
export const RegisterCarRequest = obj({ platform: Platform, app_version: str });
export const RegisterCarResponse = obj({ car_id: str, device_id: str, car_token: str });
export const PairCodeResponse = obj({ code: str, expires_at: str, link: str });
export const PairRequest = obj({ code: str, platform: Platform, push_token: nullable(str), app_version: str });
export const PairResponse = obj({ car_id: str, device_id: str, device_token: str, role: Role });
export const InviteResponse = obj({ code: str, display: str, expires_at: str, link: str });
export const DevicesResponse = obj({
  snapshot_seq: int,
  devices: arr(
    obj({
      device_id: str,
      role: Role,
      platform: Platform,
      paired_at: str,
      last_seen_at: nullable(str),
      online: bool,
      handoff_alerts: opt(bool), // 차 행에는 없다
    }),
  ),
});
/** `push_token`: 빠짐 = 그대로, 문자열 = 바꾸기, null = 지우기. */
export const PatchDeviceRequest = obj({ push_token: opt(nullable(str)), handoff_alerts: opt(bool) });
export const PatchDeviceResponse = obj({
  device_id: str,
  role: Role,
  platform: Platform,
  handoff_alerts: bool,
  push_token_set: bool,
});
export const SendMessageResponse = obj({ id: str, seq: int, recipients: int, body: anyObj });
export const InboxResponse = obj({ messages: arr(Envelope), more: bool });
export const UploadSongResponse = obj({ song_id: str, size_bytes: int });
export const LlmRequest = obj({
  request_id: str,
  question: str,
  state: obj({
    cry: obj({ current_min: nullable(int), count_this_drive: int }),
    feeding: nullable(obj({ elapsed_min: int, interval_min: int })),
    carseat_min: int,
    cabin_temp: nullable(num),
    next_rest: nullable(obj({ id: str, name: str, distance_km: num, nursing_room: bool })),
    method_stats: arr(MethodStat),
    last_night_wakeups: nullable(int),
  }),
});
export type LlmRequest = Infer<typeof LlmRequest>;
export const LlmResponse = obj({
  text: str,
  tool_calls: arr(obj({ name: str, input: anyObj })),
  stop_reason: str,
  model: str,
  prompt_version: str,
  latency_ms: int,
});
export type LlmResponse = Infer<typeof LlmResponse>;
