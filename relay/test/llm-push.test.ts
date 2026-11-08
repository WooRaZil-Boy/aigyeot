// POST /v1/llm(1.4)과 FCM 푸시(5장). 바깥 호출(Anthropic·Google)은 fetch를 가짜로 바꿔 시험한다.
import { env } from "cloudflare:workers";
import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { PROMPT_VERSION, SYSTEM_PROMPT, TOOLS } from "../src/llm";
import { forgetAccessToken, retryAfterS } from "../src/push";
import { ulid } from "../src/util";
import { BASE, addGuardian, call, carWithOwner, newCar, send } from "./helpers";

// 계약 1.4의 도구 JSON·시스템 지시 코드 블록을 그대로 옮긴 예제 파일과 견준다(docs/는 공개되지 않는다).
const fixtures = import.meta.glob("./fixtures/llm_*", { query: "?raw", import: "default", eager: true });
const fixture = (name: string) => fixtures[`./fixtures/${name}`] as string;
const contractTools = JSON.parse(fixture("llm_tools.json"));
const contractPrompt = fixture("llm_p6.txt");

type Seen = { url: string; headers: Headers; body: any };

function fakeFetch(reply: (url: string) => Response | Promise<Response>) {
  const seen: Seen[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const req = new Request(input as RequestInfo, init);
    const text = await req.text();
    let body: unknown = text;
    try {
      body = JSON.parse(text);
    } catch {}
    seen.push({ url: req.url, headers: req.headers, body });
    return reply(req.url);
  });
  return seen;
}
afterEach(() => vi.restoreAllMocks());

const llmBody = (question = "뭐가 제일 잘 통해?") => ({
  request_id: ulid(),
  question,
  state: {
    cry: { current_min: 2, count_this_drive: 3 },
    feeding: null,
    carseat_min: 52,
    cabin_temp: null,
    next_rest: null,
    method_stats: [{ method: "lullaby", label: "자장가", successes: 6, attempts: 7 }],
    last_night_wakeups: null,
  },
});
const claude = (content: object[], stop_reason = "end_turn") =>
  Response.json({ model: "claude-haiku-5-5", stop_reason, content, usage: { input_tokens: 10, output_tokens: 5 } });

describe("POST /v1/llm", () => {
  it("state의 돌봄 선택 칸(L61)을 받아 Claude에 그대로 넘긴다", async () => {
    const car = await newCar();
    const seen = fakeFetch(() => claude([{ type: "text", text: "ok" }]));
    const care = { sleep: { sleeping_since_min: 40, woke_min_ago: null, last_sleep_min: 35, today_sleep_min: 430 }, diaper: { min_ago: 235, today_count: 2 }, feeds_today: 3, care_as_of_min_ago: 10 };
    const b = llmBody();
    const r = await call("POST", "/v1/llm", { token: car.car_token, body: { ...b, state: { ...b.state, ...care } } });
    expect(r.status).toBe(200);
    expect(JSON.parse(seen[0].body.messages[0].content.split("\n")[1])).toMatchObject(care);
    const bad = await call("POST", "/v1/llm", { token: car.car_token, body: { ...b, state: { ...b.state, feeds_today: "3" } } });
    expect(bad.status).toBe(400);
  });

  it("계약대로 Claude API를 부르고 글을 돌려준다", async () => {
    const car = await newCar();
    const seen = fakeFetch(() => claude([{ type: "thinking", thinking: "" }, { type: "text", text: "자장가가 " }, { type: "text", text: "잘 통했어요." }]));
    const r = await call("POST", "/v1/llm", { token: car.car_token, body: llmBody() });
    expect(r.status).toBe(200);
    expect(r.json).toMatchObject({ text: "자장가가 잘 통했어요.", tool_calls: [], stop_reason: "end_turn", model: "claude-haiku-5-5", prompt_version: "p6" });
    expect(typeof r.json.latency_ms).toBe("number");
    const [s] = seen;
    expect(s.url).toBe("https://api.anthropic.com/v1/messages");
    expect(s.headers.get("anthropic-version")).toBe("2026-10-21");
    expect(s.headers.get("x-api-key")).toBe("EXAMPLE-anthropic-api-key");
    expect(s.body).toMatchObject({ model: "claude-haiku-5-5", max_tokens: 1024, output_config: { effort: "low" }, tool_choice: { type: "auto" } });
    for (const k of ["temperature", "top_p", "top_k", "thinking"]) expect(s.body).not.toHaveProperty(k);
    expect(s.body.tools.map((t: any) => [t.name, t.strict])).toEqual([["switch_sound", true], ["add_waypoint", true], ["stop_soothing", true], ["answer", true]]);
    expect(s.body.messages).toHaveLength(1);
    expect(s.body.system).toBe(contractPrompt);
    expect(s.body.tools).toEqual(contractTools);
    expect(s.body.messages[0].content).toMatch(/^상태값\(JSON\):\n\{"cry"/);
    expect(s.body.messages[0].content).toMatch(/\n\n운전자 질문: 뭐가 제일 잘 통해\?$/);
  });

  it("c194 p6 지시: 버전 p6, 사실 주제는 글 없이 도구로, 글에는 숫자를 쓰지 않는다(계약 글자 그대로)", () => {
    expect(PROMPT_VERSION).toBe("p6");
    expect(SYSTEM_PROMPT).toBe(contractPrompt);
    expect(SYSTEM_PROMPT).toContain("이때 글은 쓰지 않는다");
    expect(SYSTEM_PROMPT).toContain("글에는 숫자를 쓰지 않는다");
    expect(SYSTEM_PROMPT).not.toContain("숫자는 상태값에 있는 숫자만");
  });

  it("p6 answer tool_use는 tool_calls로 그대로 넘긴다", async () => {
    const car = await newCar();
    fakeFetch(() => claude([{ type: "tool_use", id: "t1", name: "answer", input: { topics: ["feeding", "sleep"] } }], "tool_use"));
    const r = await call("POST", "/v1/llm", { token: car.car_token, body: llmBody("마지막 수유 언제야") });
    expect(r.json).toMatchObject({ text: "", tool_calls: [{ name: "answer", input: { topics: ["feeding", "sleep"] } }], stop_reason: "tool_use" });
  });

  it("c118 switch_sound 도구 enum은 음원 넷(white_noise, womb, lullaby, parent_song)", () => {
    const t = TOOLS.find((x) => x.name === "switch_sound")!;
    expect((t.input_schema.properties as { method: { enum: string[] } }).method.enum).toEqual(["white_noise", "womb", "lullaby", "parent_song"]);
  });

  it("도구만 온 답은 text가 빈 문자열", async () => {
    const car = await newCar();
    fakeFetch(() => claude([{ type: "tool_use", id: "t1", name: "switch_sound", input: { method: "lullaby" } }], "tool_use"));
    const r = await call("POST", "/v1/llm", { token: car.car_token, body: llmBody("자장가 틀어 줘") });
    expect(r.json).toMatchObject({ text: "", tool_calls: [{ name: "switch_sound", input: { method: "lullaby" } }], stop_reason: "tool_use" });
  });

  it("거절 422, 잘림·빈 답·상류 오류 502, 8초 넘으면 504", async () => {
    const car = await newCar();
    const ask = async (reply: () => Response | Promise<Response>) => {
      vi.restoreAllMocks();
      fakeFetch(reply);
      return (await call("POST", "/v1/llm", { token: car.car_token, body: llmBody() })).json.error.code;
    };
    expect(await ask(() => claude([], "refusal"))).toBe("llm_refused");
    expect(await ask(() => claude([{ type: "text", text: "자장가가" }], "max_tokens"))).toBe("llm_upstream");
    expect(await ask(() => claude([{ type: "thinking", thinking: "" }]))).toBe("llm_upstream");
    vi.restoreAllMocks();
    const car2 = await newCar(); // 차당 분당 6회 제한을 피한다
    const ask2 = async (reply: () => Response | Promise<Response>) => {
      vi.restoreAllMocks();
      fakeFetch(reply);
      return (await call("POST", "/v1/llm", { token: car2.car_token, body: llmBody() })).json.error.code;
    };
    expect(await ask2(() => Response.json({ type: "error" }, { status: 529 }))).toBe("llm_upstream");
    expect(await ask2(() => Promise.reject(new DOMException("timeout", "TimeoutError")))).toBe("llm_timeout");
  });

  it("역할·입력·키·횟수 검사", async () => {
    const { car, owner } = await carWithOwner();
    fakeFetch(() => claude([{ type: "text", text: "네" }]));
    expect((await call("POST", "/v1/llm", { token: owner.device_token, body: llmBody() })).status).toBe(403);
    expect((await call("POST", "/v1/llm", { body: llmBody() })).status).toBe(401);
    expect((await call("POST", "/v1/llm", { token: car.car_token, body: llmBody("가".repeat(201)) })).status).toBe(400);
    const big = llmBody();
    big.state.method_stats = Array.from({ length: 60 }, () => ({ method: "lullaby", label: "자장가".repeat(10), successes: 1, attempts: 1 }));
    expect((await call("POST", "/v1/llm", { token: car.car_token, body: big })).status).toBe(400);
    const bare = { ...env, ANTHROPIC_API_KEY: undefined } as Env;
    const req = new Request(`${BASE}/v1/llm`, { method: "POST", headers: { Authorization: `Bearer ${car.car_token}` }, body: JSON.stringify(llmBody()) });
    expect(((await (await worker.fetch(req, bare)).json()) as any).error.code).toBe("llm_unconfigured");
    // 위 다섯 번(403·401은 세지 않음 → 400 둘, 503 하나)과 아래로 분당 6회를 넘긴다
    const codes: number[] = [];
    for (let i = 0; i < 5; i++) codes.push((await call("POST", "/v1/llm", { token: car.car_token, body: llmBody() })).status);
    expect(codes).toContain(429);
  });
});

describe("FCM 푸시", () => {
  const roomOf = (carId: string) => env.CAR_ROOM.get(env.CAR_ROOM.idFromName(carId));
  const jobs = (carId: string) =>
    runInDurableObject(roomOf(carId), (_: unknown, s: DurableObjectState) => s.storage.sql.exec("SELECT * FROM push_jobs").toArray());
  const alert = (minutesAgo: number) => ({
    text: "차가 선 채로 아기가 계속 울어요.",
    kind: "parked",
    rest_name: null,
    minutes_to_arrival: null,
    occurred_at: new Date(Date.now() - minutesAgo * 60_000).toISOString(),
    session_id: ulid(),
    episode_id: ulid(),
  });
  const fcm = (reply: () => Response) =>
    fakeFetch((url) => (url.startsWith("https://oauth2.googleapis.com/") ? Response.json({ access_token: "EXAMPLE-access", expires_in: 3600 }) : reply()));

  it("인계 알림을 guardian에게 보낸다(제목·본문·msg_id·ttl·tag·apns)", async () => {
    const { car, owner } = await carWithOwner();
    await addGuardian(owner.device_token, "fcm-token-1");
    const seen = fcm(() => Response.json({ name: "projects/example-project/messages/1" }));
    const a = alert(1);
    const sent = await send(car.car_token, "handoff_alert", a);
    await runDurableObjectAlarm(roomOf(car.car_id));
    await vi.waitFor(() => expect(seen.some((s) => s.url.includes("fcm.googleapis.com"))).toBe(true));
    const push = seen.find((s) => s.url.includes("fcm.googleapis.com"))!;
    expect(push.url).toBe("https://fcm.googleapis.com/v1/projects/example-project/messages:send");
    const m = push.body.message;
    expect(m).toMatchObject({
      token: "fcm-token-1",
      notification: { title: "아이곁", body: a.text },
      data: { msg_id: sent.json.id, type: "handoff_alert" },
      android: { priority: "high", notification: { channel_id: "handoff_alert", tag: sent.json.id } },
    });
    const ttl = Number(m.android.ttl.replace(/s$/, ""));
    expect(ttl).toBeGreaterThan(1700);
    expect(ttl).toBeLessThanOrEqual(1740);
    expect(m.apns.headers["apns-expiration"]).toBe(String(Math.floor((Date.parse(a.occurred_at) + 30 * 60_000) / 1000)));
    expect(await jobs(car.car_id)).toEqual([]);
  });

  it("30분 지난 알림은 푸시하지 않고 메시지함에만 둔다", async () => {
    const { car, owner } = await carWithOwner();
    await addGuardian(owner.device_token, "fcm-token-2");
    await send(car.car_token, "handoff_alert", alert(31));
    expect(await jobs(car.car_id)).toEqual([]);
  });

  it("UNREGISTERED면 같은 토큰일 때만 지우고, 503이면 30초 뒤 다시", async () => {
    const { car, owner } = await carWithOwner();
    const g = await addGuardian(owner.device_token, "fcm-token-3");
    fcm(() => Response.json({ error: { details: [{ errorCode: "UNREGISTERED" }] } }, { status: 404 }));
    await send(car.car_token, "handoff_alert", alert(1));
    await runDurableObjectAlarm(roomOf(car.car_id));
    await vi.waitFor(async () => {
      const r = await call("PATCH", "/v1/devices/me", { token: g.device_token, body: {} });
      expect(r.json.push_token_set).toBe(false);
    });

    vi.restoreAllMocks();
    await call("PATCH", "/v1/devices/me", { token: g.device_token, body: { push_token: "fcm-token-4" } });
    fcm(() => new Response("busy", { status: 503 }));
    await send(car.car_token, "handoff_alert", alert(1));
    await runDurableObjectAlarm(roomOf(car.car_id));
    await vi.waitFor(async () => expect((await jobs(car.car_id))[0]).toMatchObject({ attempts: 1 }));
    const [j] = (await jobs(car.car_id)) as { next_at: number }[];
    expect(j.next_at - Date.now()).toBeGreaterThan(20_000);
  });

  it("앞 발송 응답을 기다리는 사이 끊긴 guardian에게는 보내지 않는다", async () => {
    const { car, owner } = await carWithOwner();
    const ga = await addGuardian(owner.device_token, "fcm-a");
    const gb = await addGuardian(owner.device_token, "fcm-b");
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const tokens: string[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const req = new Request(input as RequestInfo, init);
      if (req.url.startsWith("https://oauth2.googleapis.com/")) return Response.json({ access_token: "EXAMPLE-access" });
      tokens.push(((await req.json()) as any).message.token);
      if (tokens.length === 1) await gate; // 첫 응답을 붙잡는다
      return Response.json({ name: "x" });
    });
    await send(car.car_token, "handoff_alert", alert(1));
    const alarm = runDurableObjectAlarm(roomOf(car.car_id));
    await vi.waitFor(() => expect(tokens).toHaveLength(1));
    const other = tokens[0] === "fcm-a" ? gb : ga;
    expect((await call("DELETE", "/v1/devices/me", { token: other.device_token })).status).toBe(204);
    release();
    await alarm;
    await vi.waitFor(async () => expect(await jobs(car.car_id)).toEqual([]));
    expect(tokens).toHaveLength(1);
  });

  it("접근 토큰을 기다리는 사이 끊긴 guardian에게는 보내지 않는다(401 재발송 포함)", async () => {
    const { car, owner } = await carWithOwner();
    const g = await addGuardian(owner.device_token, "fcm-c");
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let fcmCalls = 0;
    let held = false;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const req = new Request(input as RequestInfo, init);
      if (req.url.startsWith("https://oauth2.googleapis.com/")) {
        if (fcmCalls >= 1) {
          held = true; // 401 뒤 새 접근 토큰을 받는 동안 붙잡는다
          await gate;
        }
        return Response.json({ access_token: "EXAMPLE-access" });
      }
      fcmCalls++;
      return fcmCalls === 1 ? Response.json({ error: { status: "UNAUTHENTICATED" } }, { status: 401 }) : Response.json({ name: "x" });
    });
    await send(car.car_token, "handoff_alert", alert(1));
    const alarm = runDurableObjectAlarm(roomOf(car.car_id));
    await vi.waitFor(() => expect(held).toBe(true));
    expect((await call("DELETE", "/v1/devices/me", { token: g.device_token })).status).toBe(204);
    release();
    await alarm;
    await vi.waitFor(async () => expect(await jobs(car.car_id)).toEqual([]));
    expect(fcmCalls).toBe(1);
  });

  it("OAuth가 계속 실패해도 재시도는 3번까지", async () => {
    const { car, owner } = await carWithOwner();
    await addGuardian(owner.device_token, "fcm-d");
    forgetAccessToken();
    const seen = fakeFetch((url) =>
      url.startsWith("https://oauth2.googleapis.com/") ? new Response("busy", { status: 503 }) : Response.json({ name: "x" }),
    );
    await send(car.car_token, "handoff_alert", alert(1));
    const due = () =>
      runInDurableObject(roomOf(car.car_id), (_: unknown, s: DurableObjectState) =>
        s.storage.sql.exec("UPDATE push_jobs SET next_at = 0"),
      );
    for (let i = 1; i <= 3; i++) {
      await runDurableObjectAlarm(roomOf(car.car_id));
      await vi.waitFor(async () => expect((await jobs(car.car_id))[0]).toMatchObject({ attempts: i }));
      await due();
    }
    await runDurableObjectAlarm(roomOf(car.car_id));
    await vi.waitFor(async () => expect(await jobs(car.car_id)).toEqual([]));
    expect(seen.some((s) => s.url.includes("fcm.googleapis.com"))).toBe(false);
  });

  it("오류 코드가 없는 404는 토큰을 지우지 않는다", async () => {
    const { car, owner } = await carWithOwner();
    const g = await addGuardian(owner.device_token, "fcm-e");
    fcm(() => Response.json({ error: { status: "NOT_FOUND" } }, { status: 404 }));
    await send(car.car_token, "handoff_alert", alert(1));
    await runDurableObjectAlarm(roomOf(car.car_id));
    await vi.waitFor(async () => expect(await jobs(car.car_id)).toEqual([])); // 다시 보내지 않고 버린다
    expect((await call("PATCH", "/v1/devices/me", { token: g.device_token, body: {} })).json.push_token_set).toBe(true);
  });

  it("Retry-After는 초와 HTTP 날짜를 모두 읽는다", () => {
    const now = Date.parse("2026-11-05T08:00:00Z");
    expect(retryAfterS("120", now)).toBe(120);
    expect(retryAfterS("Thu, 05 Nov 2026 08:01:30 GMT", now)).toBe(90);
    expect(retryAfterS("어제", now)).toBeUndefined();
    expect(retryAfterS(null, now)).toBeUndefined();
  });
});
