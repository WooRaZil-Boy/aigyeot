// 계약 시험(구현 계획 7장 ②): shared/의 JSON 예제를 TypeScript 검사기로 읽고 다시 써 트리가 같은지 본다.
import { describe, expect, it } from "vitest";
import * as P from "../src/protocol";

// 빌드 때 Vite가 파일 내용을 넣는다(Workers 런타임에는 파일 시스템이 없다).
const raw = import.meta.glob("../../shared/src/test/resources/protocol/*.json", {
  eager: true,
  query: "?raw",
  import: "default",
}) as Record<string, string>;
const files = Object.fromEntries(Object.entries(raw).map(([p, text]) => [p.split("/").pop()!.replace(/\.json$/, ""), text]));

// 파일 이름 → 값이 문자열이면 그 type의 봉투, 검사기면 API 본문(Kotlin ProtocolFixturesTest와 같은 표).
const table: Record<string, string | P.D<unknown>> = {
  paired: "paired",
  unpaired: "unpaired",
  feeding_started: "feeding_started",
  feeding_started_send: "feeding_started",
  song_added: "song_added",
  song_removed: "song_removed",
  song_selected: "song_selected",
  invite_created: "invite_created",
  state_request: "state_request",
  song_status: "song_status",
  song_status_removed: "song_status",
  song_status_rejected: "song_status",
  feeding_state: "feeding_state",
  feeding_state_reply: "feeding_state",
  report_summary: "report_summary",
  report_summary_handoff: "report_summary",
  handoff_alert: "handoff_alert",
  handoff_alert_arrival: "handoff_alert",
  care_summary: "care_summary",
  care_summary_off: "care_summary",
  report_summary_womb: "report_summary",
  ack: "ack",
  api_error: P.ErrorResponse,
  api_health_response: P.HealthResponse,
  api_cars_request: P.RegisterCarRequest,
  api_cars_response: P.RegisterCarResponse,
  api_pair_codes_request: P.obj({}),
  api_pair_codes_response: P.PairCodeResponse,
  api_pair_request: P.PairRequest,
  api_pair_request_push: P.PairRequest,
  api_pair_response: P.PairResponse,
  api_invites_response: P.InviteResponse,
  api_devices_response: P.DevicesResponse,
  api_devices_me_request: P.PatchDeviceRequest,
  api_devices_me_clear_request: P.PatchDeviceRequest,
  api_devices_me_keep_request: P.PatchDeviceRequest,
  api_devices_me_response: P.PatchDeviceResponse,
  api_messages_response: P.SendMessageResponse,
  api_inbox_response: P.InboxResponse,
  api_songs_response: P.UploadSongResponse,
  api_llm_request: P.LlmRequest,
  api_llm_request_minimal: P.LlmRequest,
  api_llm_request_care: P.LlmRequest,
  api_llm_response: P.LlmResponse,
  api_llm_response_tool: P.LlmResponse,
};

const roundTrip = (d: P.D<unknown>, v: unknown) => JSON.parse(JSON.stringify(d(v, "$")));

describe("protocol 예제", () => {
  it("표와 protocol/ 파일이 같다", () => {
    expect(Object.keys(files).sort()).toEqual(Object.keys(table).sort());
  });

  for (const [name, target] of Object.entries(table)) {
    it(`${name} 왕복`, () => {
      const original = JSON.parse(files[name]);
      if (typeof target === "string") {
        expect(roundTrip(P.Envelope, original)).toStrictEqual(original);
        expect(original.type).toBe(target);
        const body = P.bodySchemas[target];
        expect(body).toBeDefined();
        expect(roundTrip(body, original.body)).toStrictEqual(original.body);
      } else {
        expect(roundTrip(target, original)).toStrictEqual(original);
      }
    });
  }

  it("report_summary body는 계약 표 순서로 쓴다", () => {
    const body = JSON.parse(files.report_summary).body;
    expect(Object.keys(roundTrip(P.bodySchemas.report_summary, body))).toEqual(Object.keys(body));
  });

  it("모르는 필드는 버리고, null이어야 할 필드가 빠지면 거절한다", () => {
    expect(P.bodySchemas.ack({ ids: ["A"], extra: 1 }, "$")).toStrictEqual({ ids: ["A"] });
    expect(() => P.bodySchemas.feeding_state({ last_feeding_start: null }, "$")).toThrow(P.DecodeError);
    // push_token: 빠짐 = 그대로, null = 지우기
    expect(P.PatchDeviceRequest({}, "$")).toStrictEqual({});
    expect(P.PatchDeviceRequest({ push_token: null }, "$")).toStrictEqual({ push_token: null });
  });
});
