// 중계 HTTP API·WebSocket의 정상·오류 경로(계약 1·2·3·4·6장).
import { env, exports } from "cloudflare:workers";
import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { DAY, canonicalJson, ulid } from "../src/util";
import {
  BASE,
  addGuardian,
  call,
  carWithOwner,
  drain,
  envelope,
  inbox,
  m4a,
  newCar,
  pairWith,
  send,
} from "./helpers";

const roomOf = (carId: string) => env.CAR_ROOM.get(env.CAR_ROOM.idFromName(carId));
const sqlIn = <T>(carId: string, q: string, ...args: (string | number)[]) =>
  runInDurableObject(roomOf(carId), (_: unknown, s: DurableObjectState) => s.storage.sql.exec(q, ...args).toArray() as T[]);

describe("health·차 등록", () => {
  it("health는 비밀값이 있는지만 알리고 Date 헤더를 단다", async () => {
    const r = await call("GET", "/v1/health");
    expect(r.status).toBe(200);
    expect(r.json).toEqual({ ok: true, version: "0.1.0", llm: true, push: true });
    expect(r.res.headers.get("Date")).toBeTruthy();
    const bare = { ...env, ANTHROPIC_API_KEY: undefined, FCM_PRIVATE_KEY: undefined } as Env;
    const r2 = await worker.fetch(new Request(`${BASE}/v1/health`), bare);
    expect(await r2.json()).toMatchObject({ llm: false, push: false });
  });

  it("차 등록은 토큰 모양을 지키고 IP당 시간당 5회", async () => {
    const car = await newCar();
    expect(car.car_id).toMatch(/^c_[0-9A-Z]{26}$/);
    expect(car.car_token).toMatch(new RegExp(`^at1\\.${car.car_id}\\.${car.device_id}\\.[A-Za-z0-9_-]{43}$`));
    const ip = "ip-reg-limit";
    for (let i = 0; i < 5; i++) {
      expect((await call("POST", "/v1/cars", { body: { platform: "pleos", app_version: "1" }, ip })).status).toBe(201);
    }
    const r = await call("POST", "/v1/cars", { body: { platform: "pleos", app_version: "1" }, ip });
    expect(r.status).toBe(429);
    expect(r.json.error.code).toBe("rate_limited");
    expect(Number(r.res.headers.get("Retry-After"))).toBeGreaterThan(0);
    expect((await call("POST", "/v1/cars", { body: { platform: "pleos" } })).status).toBe(400);
  });

  it("토큰이 없거나 틀리면 401, 모르는 경로는 404", async () => {
    const car = await newCar();
    expect((await call("GET", "/v1/devices")).status).toBe(401);
    const forged = car.car_token.slice(0, -4) + "AAAA";
    expect((await call("GET", "/v1/devices", { token: forged })).json.error.code).toBe("unauthorized");
    expect((await call("GET", "/v1/nope", { token: car.car_token })).status).toBe(404);
  });
});

describe("연결 코드·연결", () => {
  it("연결 코드로 owner가 붙고 차·owner에 paired가 간다", async () => {
    const car = await newCar();
    const pc = await call("POST", "/v1/pair-codes", { token: car.car_token, body: {} });
    expect(pc.status).toBe(201);
    expect(pc.json.code).toMatch(/^\d{6}$/);
    expect(pc.json.link).toBe(`aigyeot://pair?code=${pc.json.code}`);
    const owner = await pairWith(` ${pc.json.code.slice(0, 3)}-${pc.json.code.slice(3)} `);
    expect(owner).toMatchObject({ car_id: car.car_id, role: "owner" });
    const [m] = await inbox(car.car_token);
    expect(m).toMatchObject({ type: "paired", car: car.car_id, from: { role: "relay", device: null } });
    expect(m.body).toMatchObject({ device_id: owner.device_id, role: "owner", platform: "android", handoff_alerts: false });
    expect((await inbox(owner.device_token))[0].type).toBe("paired"); // 새 폰 포함(G14)
    // 한 번만 쓰인다
    const again = await call("POST", "/v1/pair", { body: { code: pc.json.code, platform: "android", push_token: null, app_version: "1" } });
    expect(again.json.error.code).toBe("code_invalid");
  });

  it("새 코드는 이전 코드를 지우고, DELETE는 살아 있는 코드를 지운다", async () => {
    const car = await newCar();
    const a = (await call("POST", "/v1/pair-codes", { token: car.car_token, body: {} })).json.code;
    const b = (await call("POST", "/v1/pair-codes", { token: car.car_token, body: {} })).json.code;
    const body = (code: string) => ({ code, platform: "android", push_token: null, app_version: "1" });
    if (a !== b) expect((await call("POST", "/v1/pair", { body: body(a) })).status).toBe(404);
    expect((await call("DELETE", "/v1/pair-codes", { token: car.car_token })).status).toBe(204);
    expect((await call("POST", "/v1/pair", { body: body(b) })).status).toBe(404);
  });

  it("코드 형식·역할·횟수 오류", async () => {
    const { owner } = await carWithOwner();
    expect((await call("POST", "/v1/pair-codes", { token: owner.device_token, body: {} })).status).toBe(403);
    const bad = await call("POST", "/v1/pair", { body: { code: "12345", platform: "android", push_token: null, app_version: "1" } });
    expect(bad.json.error.code).toBe("bad_code_format");
    // 설치 id당 10분 5번 틀리면 429
    const headers = { "X-Install-Id": crypto.randomUUID() };
    for (let i = 0; i < 5; i++) {
      const r = await call("POST", "/v1/pair", { body: { code: "000000", platform: "ios", push_token: null, app_version: "1" }, headers, ip: `ip-${i}-${headers["X-Install-Id"]}` });
      expect(r.status).toBe(404);
    }
    const locked = await call("POST", "/v1/pair", { body: { code: "000000", platform: "ios", push_token: null, app_version: "1" }, headers });
    expect(locked.status).toBe(429);
    expect(locked.res.headers.get("Retry-After")).toBeTruthy();
  });

  it("차는 연결 코드를 분당 6번까지 만든다", async () => {
    const car = await newCar();
    for (let i = 0; i < 6; i++) await call("POST", "/v1/pair-codes", { token: car.car_token, body: {} });
    expect((await call("POST", "/v1/pair-codes", { token: car.car_token, body: {} })).status).toBe(429);
  });
});

describe("초대·보호자", () => {
  it("owner가 초대를 만들고 guardian이 붙는다. 초대는 3개까지", async () => {
    const { car, owner } = await carWithOwner();
    const inv = await call("POST", "/v1/invites", { token: owner.device_token, body: envelope("invite_created", {}) });
    expect(inv.status).toBe(201);
    expect(inv.json.code).toMatch(/^[0-9A-HJKMNP-TV-Z]{10}$/);
    expect(inv.json.display).toBe(`${inv.json.code.slice(0, 5)}-${inv.json.code.slice(5)}`);
    // Crockford 정규화(소문자, O→0 같은 오타)
    const typed = inv.json.display.toLowerCase().replace(/0/g, "o");
    const g = await pairWith(typed, "ios");
    expect(g.role).toBe("guardian");
    const [p] = await inbox(car.car_token);
    expect(p.body).toMatchObject({ device_id: g.device_id, role: "guardian", handoff_alerts: true });
    expect(await inbox(g.device_token)).toEqual([]); // guardian은 paired를 받지 않는다
    expect((await call("POST", "/v1/invites", { token: g.device_token, body: envelope("invite_created", {}) })).status).toBe(403);
    for (let i = 0; i < 3; i++) await call("POST", "/v1/invites", { token: owner.device_token, body: envelope("invite_created", {}) });
    const over = await call("POST", "/v1/invites", { token: owner.device_token, body: envelope("invite_created", {}) });
    expect(over.json.error.code).toBe("invite_limit");
  });

  it("끊긴 owner가 만든 초대는 쓸 수 없다(G38)", async () => {
    const { car, owner } = await carWithOwner();
    const inv = (await call("POST", "/v1/invites", { token: owner.device_token, body: envelope("invite_created", {}) })).json;
    expect((await call("DELETE", `/v1/devices/${owner.device_id}`, { token: car.car_token })).status).toBe(204);
    const r = await call("POST", "/v1/pair", { body: { code: inv.code, platform: "ios", push_token: null, app_version: "1" } });
    expect(r.json.error.code).toBe("code_invalid");
  });

  it("기기 목록·PATCH·끊기", async () => {
    const { car, owner } = await carWithOwner();
    const g = await addGuardian(owner.device_token);
    await drain(car.car_token);
    const list = await call("GET", "/v1/devices", { token: owner.device_token });
    expect(list.json.devices.map((d: any) => d.role)).toEqual(["car", "owner", "guardian"]);
    expect(list.json.devices[0].handoff_alerts).toBeUndefined();
    expect(list.json.snapshot_seq).toBeGreaterThan(0);
    expect((await call("GET", "/v1/devices", { token: g.device_token })).status).toBe(403);

    // owner는 handoff_alerts를 못 바꾼다
    expect((await call("PATCH", "/v1/devices/me", { token: owner.device_token, body: { handoff_alerts: true } })).status).toBe(403);
    const p = await call("PATCH", "/v1/devices/me", { token: g.device_token, body: { push_token: "tok-1", handoff_alerts: false } });
    expect(p.json).toEqual({ device_id: g.device_id, role: "guardian", platform: "ios", handoff_alerts: false, push_token_set: true });
    const [re] = await inbox(car.car_token); // 바뀌면 paired를 다시 보낸다(G12)
    expect(re).toMatchObject({ type: "paired", body: { device_id: g.device_id, handoff_alerts: false } });
    const clear = await call("PATCH", "/v1/devices/me", { token: g.device_token, body: { push_token: null } });
    expect(clear.json.push_token_set).toBe(false);
    await drain(car.car_token);

    expect((await call("DELETE", "/v1/devices/me", { token: g.device_token })).status).toBe(204);
    expect((await call("GET", "/v1/inbox", { token: g.device_token })).status).toBe(401);
    const [u] = await inbox(car.car_token);
    expect(u).toMatchObject({ type: "unpaired", body: { device_id: g.device_id, role: "guardian", by: "self" } });
    expect((await inbox(owner.device_token)).at(-1)?.type).toBe("unpaired");
    // 차는 자기 자신이나 모르는 기기를 지울 수 없다
    expect((await call("DELETE", `/v1/devices/${car.device_id}`, { token: car.car_token })).status).toBe(404);
    expect((await call("DELETE", `/v1/devices/d_${"0".repeat(26)}`, { token: car.car_token })).status).toBe(404);
    expect((await call("DELETE", `/v1/devices/${car.device_id}`, { token: owner.device_token })).status).toBe(403);
  });
});

describe("메시지", () => {
  it("feeding_started: 미래 at은 중계 시각으로, 과거는 그대로", async () => {
    const { car, owner } = await carWithOwner();
    const future = new Date(Date.now() + 3600_000).toISOString();
    const r = await send(owner.device_token, "feeding_started", { at: future });
    expect(r.status).toBe(201);
    expect(r.json.recipients).toBe(1);
    expect(Date.parse(r.json.body.at)).toBeLessThanOrEqual(Date.now());
    const past = "2026-11-01T08:12:21+09:00";
    expect((await send(owner.device_token, "feeding_started", { at: past })).json.body.at).toBe(past);
    const msgs = await inbox(car.car_token);
    expect(msgs.map((m) => m.seq)).toEqual([...msgs.map((m) => m.seq)].sort((a, b) => a - b));
    expect(msgs[0]).toMatchObject({ type: "feeding_started", from: { role: "owner", device: owner.device_id }, body: { at: r.json.body.at } });
  });

  it("같은 id: 같은 내용이면 200과 처음 seq, 다르면 409", async () => {
    const { owner } = await carWithOwner();
    const e = envelope("feeding_started", { at: "2026-11-05T08:12:21+09:00" });
    const first = await call("POST", "/v1/messages", { token: owner.device_token, body: e });
    const again = await call("POST", "/v1/messages", { token: owner.device_token, body: e });
    expect(again.status).toBe(200);
    expect(again.json.seq).toBe(first.json.seq);
    const changed = await call("POST", "/v1/messages", { token: owner.device_token, body: { ...e, body: { at: "2026-11-05T09:00:00+09:00" } } });
    expect(changed.json.error.code).toBe("id_conflict");
  });

  it("같은 id: 키 순서만 다른 본문은 같은 내용이다", async () => {
    const { car } = await carWithOwner();
    const a = envelope("report_summary", { session_id: "S1", nested: { x: 1, y: { p: 1, q: [1, { b: 1, a: 2 }] } }, z: 2 });
    const b = { ...a, body: { z: 2, nested: { y: { q: [1, { a: 2, b: 1 }], p: 1 }, x: 1 }, session_id: "S1" } };
    const c = { ...a, body: { ...a.body, z: 3 } };
    const first = await call("POST", "/v1/messages", { token: car.car_token, body: a });
    const again = await call("POST", "/v1/messages", { token: car.car_token, body: b });
    expect([first.status, again.status, again.json.seq]).toEqual([first.status, 200, first.json.seq]);
    expect((await call("POST", "/v1/messages", { token: car.car_token, body: c })).json.error.code).toBe("id_conflict");
  });

  it("canonicalJson은 중첩 키를 정렬하고 배열 순서는 둔다", () => {
    expect(canonicalJson({ b: 1, a: { d: 1, c: [2, 1, { z: 1, y: 2 }] } })).toBe('{"a":{"c":[2,1,{"y":2,"z":1}],"d":1},"b":1}');
  });

  it("봉투·역할 오류", async () => {
    const { car, owner } = await carWithOwner();
    const g = await addGuardian(owner.device_token);
    const code = async (token: string, body: unknown) => (await call("POST", "/v1/messages", { token, body })).json.error.code;
    expect(await code(owner.device_token, { ...envelope("feeding_started", { at: "x" }), v: 2 })).toBe("unsupported_version");
    expect(await code(owner.device_token, envelope("nope", {}))).toBe("unknown_type");
    expect(await code(owner.device_token, envelope("feeding_started", { at: "어제" }))).toBe("bad_request");
    expect(await code(owner.device_token, { v: 1, type: "feeding_started" })).toBe("bad_request");
    expect(await code(g.device_token, envelope("state_request", {}))).toBe("forbidden_role");
    expect(await code(owner.device_token, envelope("song_status", {}))).toBe("forbidden_role");
    expect(await code(car.car_token, envelope("feeding_started", { at: "2026-11-05T08:12:21Z" }))).toBe("forbidden_role");
    expect(await code(car.car_token, envelope("report_summary", {}, { to: owner.device_id }))).toBe("bad_request");
    expect(await code(owner.device_token, envelope("song_added", { song_id: ulid(), name: "a", duration_s: 1 }))).toBe("song_blob_missing");
    const big = await call("POST", "/v1/messages", { token: owner.device_token, raw: "x".repeat(70_000), headers: { "Content-Type": "application/json" } });
    expect(big.status).toBe(413);
  });

  it("받는 쪽: to는 그 owner 하나, 리포트는 owner·guardian, 인계는 알림 켠 guardian, 차의 song_selected는 아무도", async () => {
    const { car, owner } = await carWithOwner();
    const g = await addGuardian(owner.device_token);
    await drain(owner.device_token);
    const fs = { last_feeding_start: null, window_start_expected: null };
    expect((await send(car.car_token, "feeding_state", fs, { to: owner.device_id })).json.recipients).toBe(1);
    expect((await send(car.car_token, "feeding_state", fs, { to: g.device_id })).json.recipients).toBe(0);
    expect((await send(car.car_token, "report_summary", { session_id: ulid() })).json.recipients).toBe(2);
    const sel = await send(car.car_token, "song_selected", { song_id: ulid() });
    expect(sel.json).toMatchObject({ recipients: 0 });
    expect(sel.json.seq).toBeGreaterThan(0);
    const alert = { text: "아기가 계속 울어요.", kind: "parked", rest_name: null, minutes_to_arrival: null, occurred_at: "2026-11-05T15:20:03+09:00", session_id: ulid(), episode_id: ulid() };
    expect((await send(car.car_token, "handoff_alert", alert)).json.recipients).toBe(1);
    await call("PATCH", "/v1/devices/me", { token: g.device_token, body: { handoff_alerts: false } });
    expect((await send(car.car_token, "handoff_alert", alert)).json.recipients).toBe(0);
    expect((await inbox(g.device_token)).map((m) => m.type)).toEqual(["report_summary", "handoff_alert"]);
    expect((await inbox(owner.device_token)).map((m) => m.type)).toEqual(["feeding_state", "report_summary", "paired"]);
  });

  it("state_request는 기기당 시간당 6회", async () => {
    const { owner } = await carWithOwner();
    for (let i = 0; i < 6; i++) expect((await send(owner.device_token, "state_request", {})).status).toBe(201);
    expect((await send(owner.device_token, "state_request", {})).status).toBe(429);
  });

  it("받은 함 쪽 나누기와 ack, 모두 ack하면 본문을 비운다", async () => {
    const { car, owner } = await carWithOwner();
    for (let i = 0; i < 3; i++) await send(owner.device_token, "feeding_started", { at: `2026-11-05T08:1${i}:00Z` });
    const p1 = (await call("GET", "/v1/inbox?after_seq=0&limit=2", { token: car.car_token })).json;
    expect(p1.messages).toHaveLength(2);
    expect(p1.more).toBe(true);
    const p2 = (await call("GET", `/v1/inbox?after_seq=${p1.messages[1].seq}&limit=2`, { token: car.car_token })).json;
    expect(p2).toMatchObject({ more: false });
    expect(p2.messages).toHaveLength(1);
    const id = p1.messages[0].id;
    expect((await call("POST", "/v1/ack", { token: car.car_token, body: envelope("ack", { ids: [id, ulid()] }) })).status).toBe(204);
    expect((await inbox(car.car_token)).map((m) => m.id)).not.toContain(id);
    const [row] = await sqlIn<{ body: string | null }>(car.car_id, "SELECT body FROM messages WHERE id = ?", id);
    expect(row.body).toBeNull();
    // 방금 ack한 것만 비운다. 아직 받는 쪽이 남은 다른 메시지 본문은 그대로다.
    const kept = await sqlIn<{ n: number }>(car.car_id, "SELECT count(*) AS n FROM messages WHERE type = 'feeding_started' AND body IS NOT NULL");
    expect(kept[0].n).toBe(2);
    expect((await call("POST", "/v1/ack", { token: car.car_token, body: envelope("ack", { ids: [] }) })).status).toBe(400);
  });

  it("DELETE /v1/records는 리포트·인계만 지운다", async () => {
    const { car, owner } = await carWithOwner();
    await send(owner.device_token, "feeding_started", { at: "2026-11-05T08:00:00Z" });
    await send(car.car_token, "report_summary", { session_id: ulid() });
    expect((await call("DELETE", "/v1/records", { token: owner.device_token })).status).toBe(403);
    expect((await call("DELETE", "/v1/records", { token: car.car_token })).status).toBe(204);
    expect(await inbox(owner.device_token)).toEqual([]);
    expect((await inbox(car.car_token)).map((m) => m.type)).toEqual(["feeding_started"]);
  });
});

describe("care_summary(L60)", () => {
  const cs = (o: object = {}) => ({
    as_of: "2026-11-06T15:00:00+09:00", zone: "Asia/Seoul", sleep_diaper_enabled: true,
    last_sleep_start_at: "2026-11-06T12:20:00+09:00", last_sleep_end_at: "2026-11-06T12:55:30+09:00", sleeping_since: null,
    today_sleep_min: 430, night_wakeups: 2, last_diaper_at: "2026-11-06T11:15:00+09:00", today_diaper_count: 2, today_feed_count: 3,
    ...o,
  });
  const off = { sleep_diaper_enabled: false, last_sleep_start_at: null, last_sleep_end_at: null, sleeping_since: null, today_sleep_min: null, night_wakeups: null, last_diaper_at: null, today_diaper_count: null };
  const code = async (token: string, body: unknown) => (await send(token, "care_summary", body as object)).json.error?.code;

  it("owner가 보내면 차만 받는다", async () => {
    const { car, owner } = await carWithOwner();
    const g = await addGuardian(owner.device_token);
    await drain(car.car_token);
    const r = await send(owner.device_token, "care_summary", cs());
    expect(r.status).toBe(201);
    expect(r.json.recipients).toBe(1);
    expect((await inbox(car.car_token)).map((m) => m.type)).toEqual(["care_summary"]);
    expect((await inbox(g.device_token)).map((m) => m.type)).not.toContain("care_summary");
    expect((await send(owner.device_token, "care_summary", { ...cs(), ...off })).status).toBe(201);
  });

  it("care_summary는 표의 칸만 저장해 넘긴다", async () => {
    const { car, owner } = await carWithOwner();
    await drain(car.car_token);
    expect((await send(owner.device_token, "care_summary", { ...cs(), memo: "x", nickname: "y" })).status).toBe(201);
    const [m] = await inbox(car.car_token);
    expect(m.body).toEqual(cs());
    expect(m.body).not.toHaveProperty("memo");
    expect(m.body).not.toHaveProperty("nickname");
  });

  it("care_summary zone은 시간대 ID만 받는다", async () => {
    const { owner } = await carWithOwner();
    expect((await send(owner.device_token, "care_summary", cs({ zone: "Asia/Seoul" }))).status).toBe(201);
    expect(await code(owner.device_token, cs({ zone: "메모 글" }))).toBe("bad_request");
    expect(await code(owner.device_token, cs({ zone: "A".repeat(65) }))).toBe("bad_request");
  });

  it("care_summary는 없는 날짜를 400으로 거절한다", async () => {
    const { owner } = await carWithOwner();
    for (const as_of of ["2026-02-30T12:20:00+09:00", "2026-11-06T24:00:00+09:00", "2026-11-06T12:60:00Z", "2026-11-06T12:00:60Z", "2026-11-06T12:00:00+19:00", "2026-11-06T12:00:00+09:60", "2026-11-06T12:00:00+18:01"]) {
      expect(await code(owner.device_token, cs({ as_of })), as_of).toBe("bad_request");
    }
    expect((await send(owner.device_token, "care_summary", cs({ as_of: "2028-02-29T23:59:59+09:00" }))).status).toBe(201);
    for (const as_of of ["2026-11-06T12:00:00+18:00", "2026-11-06T12:00:01-18:00"]) expect((await send(owner.device_token, "care_summary", cs({ as_of }))).status, as_of).toBe(201);
  });
  it("care_summary 시각은 차 OffsetDateTime.parse가 읽는 꼴만 받는다(L62)", async () => {
    const { owner } = await carWithOwner();
    for (const as_of of ["2026-11-06T12:00:00.1234567890+09:00", "2026-11-06t12:00:00+09:00", "2026-11-06T12:00:00z", "2026-11-06T12:00+09:00"]) {
      expect(await code(owner.device_token, cs({ as_of })), as_of).toBe("bad_request");
    }
    for (const as_of of ["2026-11-06T12:00:00.123456789+09:00", "0099-11-06T12:00:00+09:00", "2026-11-06T12:00:00Z"]) {
      expect((await send(owner.device_token, "care_summary", cs({ as_of }))).status, as_of).toBe(201);
    }
  });


  it("care_summary는 Int 범위를 넘는 정수를 400으로 거절한다", async () => {
    const { owner } = await carWithOwner();
    expect(await code(owner.device_token, cs({ today_feed_count: 2147483648 }))).toBe("bad_request");
    expect((await send(owner.device_token, "care_summary", cs({ today_feed_count: 2147483647 }))).status).toBe(201);
  });

  it("guardian·차가 보내면 403", async () => {
    const { car, owner } = await carWithOwner();
    const g = await addGuardian(owner.device_token);
    expect(await code(g.device_token, cs())).toBe("forbidden_role");
    expect(await code(car.car_token, cs())).toBe("forbidden_role");
  });

  it("형식 오류는 400", async () => {
    const { owner } = await carWithOwner();
    const t = owner.device_token;
    const { zone: _z, ...noZone } = cs();
    expect(await code(t, noZone)).toBe("bad_request"); // 필수 칸 없음
    const { night_wakeups: _n, ...noNull } = cs();
    expect(await code(t, noNull)).toBe("bad_request"); // null은 명시해야 한다
    expect(await code(t, cs({ as_of: "어제" }))).toBe("bad_request"); // 시각을 읽지 못함
    expect(await code(t, cs({ last_diaper_at: "2026-11-06 11:15" }))).toBe("bad_request");
    expect(await code(t, cs({ today_sleep_min: -1 }))).toBe("bad_request"); // 음수
    expect(await code(t, cs({ today_feed_count: 1.5 }))).toBe("bad_request"); // 정수 아님
    expect(await code(t, cs({ today_feed_count: null }))).toBe("bad_request"); // null 불가
    expect(await code(t, cs({ sleep_diaper_enabled: false }))).toBe("bad_request"); // 끔인데 잠 칸이 값
    expect(await code(t, cs({ ...off, today_diaper_count: 0 }))).toBe("bad_request");
  });

  it("DELETE /v1/records가 care_summary 행(messages·deliveries)을 실제로 지운다", async () => {
    const { car, owner } = await carWithOwner();
    const id = (await send(owner.device_token, "care_summary", cs())).json.id;
    const rows = () => Promise.all([
      sqlIn<object>(car.car_id, "SELECT 1 FROM messages WHERE id = ?", id),
      sqlIn<object>(car.car_id, "SELECT 1 FROM deliveries WHERE message_id = ?", id),
    ]);
    expect((await rows()).map((r) => r.length)).toEqual([1, 1]);
    expect((await call("DELETE", "/v1/records", { token: car.car_token })).status).toBe(204);
    expect((await rows()).map((r) => r.length)).toEqual([0, 0]);
  });
});

describe("노래", () => {
  it("올리기 → song_added → 차가 받기 → song_status가 오면 파일을 지운다", async () => {
    const { car, owner } = await carWithOwner();
    const id = ulid();
    const put = (token: string, body: BodyInit, type = "audio/mp4", sid = id) =>
      call("PUT", `/v1/songs/${sid}`, { token, raw: body, headers: { "Content-Type": type } });
    const r = await put(owner.device_token, m4a(1000));
    expect(r.json).toEqual({ song_id: id, size_bytes: 1000 });
    expect((await put(owner.device_token, m4a(1000))).json.error.code).toBe("already_exists");
    expect((await send(owner.device_token, "song_added", { song_id: id, name: "나비야", duration_s: 94.5 })).status).toBe(201);
    const got = await call("GET", `/v1/songs/${id}`, { token: car.car_token });
    expect(got.status).toBe(200);
    expect(got.res.headers.get("Content-Type")).toBe("audio/mp4");
    expect((await call("GET", `/v1/songs/${id}`, { token: owner.device_token })).status).toBe(403);
    const st = { song_id: id, name: "나비야", status: "accepted", reason: null, duration_s: 94.5, candidate: true };
    await send(car.car_token, "song_status", st);
    expect((await call("GET", `/v1/songs/${id}`, { token: car.car_token })).json.error.code).toBe("not_found");
  });

  it("형식·크기·자리 검사", async () => {
    const { owner } = await carWithOwner();
    const put = (body: BodyInit, type = "audio/mp4", sid = ulid()) =>
      call("PUT", `/v1/songs/${sid}`, { token: owner.device_token, raw: body, headers: { "Content-Type": type } });
    expect((await put(m4a(), "audio/mpeg")).json.error.code).toBe("unsupported_media");
    expect((await put(new Uint8Array(64))).json.error.code).toBe("unsupported_media");
    expect((await put(m4a(2_000_001))).json.error.code).toBe("too_large");
    expect((await put(m4a(), "audio/mp4", "not-ulid")).status).toBe(400);
    expect((await put(m4a(2_000_000))).status).toBe(201); // G30: 2,000,000바이트가 행 하나에 들어간다
    await put(m4a());
    await put(m4a());
    expect((await put(m4a())).json.error.code).toBe("song_quota");
  });
});

describe("WebSocket", () => {
  const open = async (token: string) => {
    const r = await call("GET", "/v1/ws", { token, headers: { Upgrade: "websocket" } });
    expect(r.status).toBe(101);
    const ws = r.res.webSocket!;
    const got: string[] = [];
    const closed: number[] = [];
    ws.addEventListener("message", (e) => got.push(e.data as string));
    ws.addEventListener("close", (e) => closed.push(e.code));
    ws.accept();
    return { ws, got, closed };
  };

  it("붙자마자 밀린 메시지를 seq 순서로, 이후 새 메시지를 바로, ack·ping을 받는다", async () => {
    const { car, owner } = await carWithOwner();
    await send(owner.device_token, "feeding_started", { at: "2026-11-05T08:00:00Z" });
    await send(owner.device_token, "song_removed", { song_id: ulid() });
    const c = await open(car.car_token);
    await vi.waitFor(() => expect(c.got).toHaveLength(2));
    await send(owner.device_token, "state_request", {});
    await vi.waitFor(() => expect(c.got).toHaveLength(3));
    const msgs = c.got.map((t) => JSON.parse(t));
    expect(msgs.map((m) => m.type)).toEqual(["feeding_started", "song_removed", "state_request"]);
    expect(msgs[1].seq).toBeGreaterThan(msgs[0].seq);
    c.ws.send(JSON.stringify(envelope("ack", { ids: msgs.map((m) => m.id) })));
    c.ws.send("ping");
    await vi.waitFor(() => expect(c.got.at(-1)).toBe("pong"));
    await vi.waitFor(async () => expect(await inbox(car.car_token)).toEqual([]));
    const list = (await call("GET", "/v1/devices", { token: car.car_token })).json.devices;
    expect(list[0].online).toBe(true);
    expect(list[1].online).toBe(false);
  });

  it("같은 기기의 새 연결은 이전 것을 4002로, 끊기는 4001로 닫는다", async () => {
    const { owner } = await carWithOwner();
    const a = await open(owner.device_token);
    const b = await open(owner.device_token);
    await vi.waitFor(() => expect(a.closed).toEqual([4002]));
    await call("DELETE", "/v1/devices/me", { token: owner.device_token });
    await vi.waitFor(() => expect(b.closed).toEqual([4001]));
  });

  it("업그레이드가 아니면 400, 토큰이 없으면 401", async () => {
    const car = await newCar();
    expect((await call("GET", "/v1/ws", { token: car.car_token })).status).toBe(400);
    expect((await call("GET", "/v1/ws", { headers: { Upgrade: "websocket" } })).status).toBe(401);
  });
});

describe("정리(Alarm)", () => {
  it("7일 지난 메시지·노래를 지우고, 폰 없는 방은 30일 뒤 지운다", async () => {
    const { car, owner } = await carWithOwner();
    await send(owner.device_token, "feeding_started", { at: "2026-11-05T08:00:00Z" });
    await call("PUT", `/v1/songs/${ulid()}`, { token: owner.device_token, raw: m4a(), headers: { "Content-Type": "audio/mp4" } });
    const old = Date.now() - 8 * DAY;
    await sqlIn(car.car_id, "UPDATE messages SET created_at = ?", old);
    await sqlIn(car.car_id, "UPDATE deliveries SET created_at = ?", old);
    await sqlIn(car.car_id, "UPDATE songs SET uploaded_at = ?", old);
    expect(await runDurableObjectAlarm(roomOf(car.car_id))).toBe(true);
    expect(await sqlIn(car.car_id, "SELECT id FROM messages")).toEqual([]);
    expect(await sqlIn(car.car_id, "SELECT song_id FROM song_blobs")).toEqual([]);

    const lonely = await newCar();
    await sqlIn(lonely.car_id, "UPDATE room SET last_activity_at = ?", Date.now() - 31 * DAY);
    await runDurableObjectAlarm(roomOf(lonely.car_id));
    expect((await call("GET", "/v1/devices", { token: lonely.car_token })).status).toBe(401);
  });
});

describe("검토 뒤 고친 경합·상한", () => {
  /** 본문을 천천히 보내는 요청. `finish`를 부를 때까지 본문이 끝나지 않는다. */
  const slowRequest = (method: string, path: string, token: string, payload: string | Uint8Array, contentType: string) => {
    const bytes = typeof payload === "string" ? new TextEncoder().encode(payload) : payload;
    const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>();
    const w = writable.getWriter();
    const res = exports.default.fetch(`${BASE}${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, "Content-Type": contentType, "CF-Connecting-IP": "slow" },
      body: readable,
    });
    const started = w.write(bytes.subarray(0, 8));
    return {
      res,
      finish: async () => {
        await started;
        await w.write(bytes.subarray(8));
        await w.close();
      },
    };
  };
  /**
   * 요청이 인증을 마쳤다는 신호를 기다린다. 인증은 `devices.last_seen_at`을 고치므로 0으로 돌려 두고 바뀔 때까지 본다.
   * 본문은 `finish` 전까지 끝나지 않으므로, 이 신호 뒤 요청은 본문을 읽는 중이다. 고정 시간 대기를 쓰지 않는다.
   */
  const authed = async <T,>(carId: string, deviceId: string, start: () => T): Promise<T> => {
    await sqlIn(carId, "UPDATE devices SET last_seen_at = 0 WHERE device_id = ?", deviceId);
    const r = start();
    for (let i = 0; i < 400; i++) {
      const [d] = await sqlIn<{ t: number }>(carId, "SELECT last_seen_at AS t FROM devices WHERE device_id = ?", deviceId);
      if (d.t > 0) return r;
      await new Promise((ok) => setTimeout(ok, 10));
    }
    throw new Error("요청이 인증을 마치지 못했다");
  };

  it("본문을 받는 사이 끊긴 owner의 메시지·노래는 저장하지 않고 401", async () => {
    const { car, owner } = await carWithOwner();
    const e = envelope("feeding_started", { at: "2026-11-01T08:00:00Z" });
    const msg = await authed(car.car_id, owner.device_id, () =>
      slowRequest("POST", "/v1/messages", owner.device_token, JSON.stringify(e), "application/json"),
    );
    const song = await authed(car.car_id, owner.device_id, () =>
      slowRequest("PUT", `/v1/songs/${ulid()}`, owner.device_token, m4a(100), "audio/mp4"),
    );
    expect((await call("DELETE", `/v1/devices/${owner.device_id}`, { token: car.car_token })).status).toBe(204);
    await msg.finish();
    await song.finish();
    expect((await msg.res).status).toBe(401);
    expect((await song.res).status).toBe(401);
    expect((await inbox(car.car_token)).map((m) => m.type)).toEqual(["unpaired"]);
    expect(await sqlIn(car.car_id, "SELECT song_id FROM songs")).toEqual([]);
  });

  it("ack 뒤 같은 id로 다시 보내도 처음 고친 수유 시각을 돌려준다", async () => {
    const { car, owner } = await carWithOwner();
    const e = envelope("feeding_started", { at: new Date(Date.now() + 3600_000).toISOString() });
    const first = await call("POST", "/v1/messages", { token: owner.device_token, body: e });
    await drain(car.car_token);
    const again = await call("POST", "/v1/messages", { token: owner.device_token, body: e });
    expect(again.status).toBe(200);
    expect(again.json).toMatchObject({ seq: first.json.seq, body: { at: first.json.body.at }, recipients: 0 });
  });

  it("Content-Length 없이 상한을 넘는 본문은 읽는 중에 413", async () => {
    const { owner } = await carWithOwner();
    const stream = (size: number) =>
      new ReadableStream<Uint8Array>({
        start(c) {
          for (let sent = 0; sent < size; sent += 16_384) c.enqueue(m4a(Math.min(16_384, size - sent)));
          c.close();
        },
      });
    const post = await exports.default.fetch(`${BASE}/v1/messages`, {
      method: "POST",
      headers: { Authorization: `Bearer ${owner.device_token}`, "Content-Type": "application/json" },
      body: stream(70_000),
    });
    expect(post.status).toBe(413);
    const put = await exports.default.fetch(`${BASE}/v1/songs/${ulid()}`, {
      method: "PUT",
      headers: { Authorization: `Bearer ${owner.device_token}`, "Content-Type": "audio/mp4" },
      body: stream(2_000_001),
    });
    expect(put.status).toBe(413);
  });

  it("64 KB를 넘는 WebSocket 프레임은 처리하지 않는다", async () => {
    const { car, owner } = await carWithOwner();
    await send(owner.device_token, "feeding_started", { at: "2026-11-01T08:00:00Z" });
    const [m] = await inbox(car.car_token);
    const r = await call("GET", "/v1/ws", { token: car.car_token, headers: { Upgrade: "websocket" } });
    const ws = r.res.webSocket!;
    const got: string[] = [];
    ws.addEventListener("message", (e) => got.push(e.data as string));
    ws.accept();
    ws.send(JSON.stringify({ ...envelope("ack", { ids: [m.id] }), pad: "x".repeat(70_000) }));
    ws.send("ping");
    await vi.waitFor(() => expect(got.at(-1)).toBe("pong"));
    expect((await inbox(car.car_token)).map((x) => x.id)).toEqual([m.id]);
    ws.send(JSON.stringify(envelope("ack", { ids: [m.id] })));
    await vi.waitFor(async () => expect(await inbox(car.car_token)).toEqual([]));
  });

  it("같은 기기의 소켓을 바꾸는 중에도 메시지 저장은 성공한다", async () => {
    const { car, owner } = await carWithOwner();
    const open = async () => {
      const r = await call("GET", "/v1/ws", { token: car.car_token, headers: { Upgrade: "websocket" } });
      r.res.webSocket!.accept();
      return r.res.webSocket!;
    };
    await open();
    await open(); // 이전 소켓은 4002로 닫히는 중
    for (let i = 0; i < 3; i++) {
      expect((await send(owner.device_token, "feeding_started", { at: `2026-11-01T08:0${i}:00Z` })).status).toBe(201);
    }
  });
});
