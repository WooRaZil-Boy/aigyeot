// 메시지 보내기·받기, care_summary 검사, WebSocket의 정상·오류 경로.
import { describe, expect, it, vi } from "vitest";
import { canonicalJson, ulid } from "../src/util";
import { addGuardian, call, carWithOwner, cs, drain, envelope, inbox, newCar, send, sqlIn } from "./helpers";

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
    expect(msgs[0]).toMatchObject({
      type: "feeding_started",
      from: { role: "owner", device: owner.device_id },
      body: { at: r.json.body.at },
    });
  });

  it("같은 id: 같은 내용이면 200과 처음 seq, 다르면 409", async () => {
    const { owner } = await carWithOwner();
    const e = envelope("feeding_started", { at: "2026-11-05T08:12:21+09:00" });
    const first = await call("POST", "/v1/messages", { token: owner.device_token, body: e });
    const again = await call("POST", "/v1/messages", { token: owner.device_token, body: e });
    expect(again.status).toBe(200);
    expect(again.json.seq).toBe(first.json.seq);
    const changed = await call("POST", "/v1/messages", {
      token: owner.device_token,
      body: { ...e, body: { at: "2026-11-05T09:00:00+09:00" } },
    });
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
    expect(canonicalJson({ b: 1, a: { d: 1, c: [2, 1, { z: 1, y: 2 }] } })).toBe(
      '{"a":{"c":[2,1,{"y":2,"z":1}],"d":1},"b":1}',
    );
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
    const added = envelope("song_added", { song_id: ulid(), name: "a", duration_s: 1 });
    expect(await code(owner.device_token, added)).toBe("song_blob_missing");
    const big = await call("POST", "/v1/messages", {
      token: owner.device_token,
      raw: "x".repeat(70_000),
      headers: { "Content-Type": "application/json" },
    });
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
    const alert = {
      text: "아기가 계속 울어요.",
      kind: "parked",
      rest_name: null,
      minutes_to_arrival: null,
      occurred_at: "2026-11-05T15:20:03+09:00",
      session_id: ulid(),
      episode_id: ulid(),
    };
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
    const ack = await call("POST", "/v1/ack", { token: car.car_token, body: envelope("ack", { ids: [id, ulid()] }) });
    expect(ack.status).toBe(204);
    expect((await inbox(car.car_token)).map((m) => m.id)).not.toContain(id);
    const [row] = await sqlIn<{ body: string | null }>(car.car_id, "SELECT body FROM messages WHERE id = ?", id);
    expect(row.body).toBeNull();
    // 방금 ack한 것만 비운다. 아직 받는 쪽이 남은 다른 메시지 본문은 그대로다.
    const kept = await sqlIn<{ n: number }>(
      car.car_id,
      "SELECT count(*) AS n FROM messages WHERE type = 'feeding_started' AND body IS NOT NULL",
    );
    expect(kept[0].n).toBe(2);
    expect((await call("POST", "/v1/ack", { token: car.car_token, body: envelope("ack", { ids: [] }) })).status).toBe(400);
  });

  it("DELETE /v1/records는 리포트·인계만 지운다", async () => {
    const { car, owner } = await carWithOwner();
    await send(owner.device_token, "feeding_started", { at: "2026-11-05T08:00:00Z" });
    await send(car.car_token, "report_summary", { session_id: ulid() });
    const del = (token: string) =>
      call("DELETE", `/v1/records?id=${ulid()}`, { body: { drop_ids: [] }, token });
    expect((await del(owner.device_token)).status).toBe(403);
    expect((await del(car.car_token)).status).toBe(204);
    expect(await inbox(owner.device_token)).toEqual([]);
    expect((await inbox(car.car_token)).map((m) => m.type)).toEqual(["feeding_started"]);
  });
});

describe("care_summary", () => {
  const off = {
    sleep_diaper_enabled: false,
    last_sleep_start_at: null,
    last_sleep_end_at: null,
    sleeping_since: null,
    today_sleep_min: null,
    night_wakeups: null,
    last_diaper_at: null,
    today_diaper_count: null,
  };
  const code = async (token: string, body: unknown) => (await send(token, "care_summary", body as object)).json.error?.code;
  const sends = async (token: string, body: object) => (await send(token, "care_summary", body)).status;

  it("owner가 보내면 차만 받는다", async () => {
    const { car, owner } = await carWithOwner();
    const g = await addGuardian(owner.device_token);
    await drain(car.car_token);
    const r = await send(owner.device_token, "care_summary", cs());
    expect(r.status).toBe(201);
    expect(r.json.recipients).toBe(1);
    expect((await inbox(car.car_token)).map((m) => m.type)).toEqual(["care_summary"]);
    expect((await inbox(g.device_token)).map((m) => m.type)).not.toContain("care_summary");
    expect(await sends(owner.device_token, { ...cs(), ...off })).toBe(201);
  });

  it("care_summary는 표의 칸만 저장해 넘긴다", async () => {
    const { car, owner } = await carWithOwner();
    await drain(car.car_token);
    expect(await sends(owner.device_token, { ...cs(), memo: "x", nickname: "y" })).toBe(201);
    const [m] = await inbox(car.car_token);
    expect(m.body).toEqual(cs());
    expect(m.body).not.toHaveProperty("memo");
    expect(m.body).not.toHaveProperty("nickname");
  });

  it("care_summary zone은 시간대 ID만 받는다", async () => {
    const { owner } = await carWithOwner();
    expect(await sends(owner.device_token, cs({ zone: "Asia/Seoul" }))).toBe(201);
    expect(await code(owner.device_token, cs({ zone: "메모 글" }))).toBe("bad_request");
    expect(await code(owner.device_token, cs({ zone: "A".repeat(65) }))).toBe("bad_request");
  });

  it("care_summary는 없는 날짜를 400으로 거절한다", async () => {
    const { owner } = await carWithOwner();
    const invalid = [
      "2026-02-30T12:20:00+09:00",
      "2026-11-06T24:00:00+09:00",
      "2026-11-06T12:60:00Z",
      "2026-11-06T12:00:60Z",
      "2026-11-06T12:00:00+19:00",
      "2026-11-06T12:00:00+09:60",
      "2026-11-06T12:00:00+18:01",
    ];
    for (const as_of of invalid) {
      expect(await code(owner.device_token, cs({ as_of })), as_of).toBe("bad_request");
    }
    expect(await sends(owner.device_token, cs({ as_of: "2028-02-29T23:59:59+09:00" }))).toBe(201);
    for (const as_of of ["2026-11-06T12:00:00+18:00", "2026-11-06T12:00:01-18:00"]) {
      expect(await sends(owner.device_token, cs({ as_of })), as_of).toBe(201);
    }
  });

  it("care_summary 시각은 차 OffsetDateTime.parse가 읽는 꼴만 받는다", async () => {
    const { owner } = await carWithOwner();
    const unreadable = [
      "2026-11-06T12:00:00.1234567890+09:00",
      "2026-11-06t12:00:00+09:00",
      "2026-11-06T12:00:00z",
      "2026-11-06T12:00+09:00",
    ];
    for (const as_of of unreadable) {
      expect(await code(owner.device_token, cs({ as_of })), as_of).toBe("bad_request");
    }
    for (const as_of of ["2026-11-06T12:00:00.123456789+09:00", "0099-11-06T12:00:00+09:00", "2026-11-06T12:00:00Z"]) {
      expect(await sends(owner.device_token, cs({ as_of })), as_of).toBe(201);
    }
  });

  it("care_summary는 Int 범위를 넘는 정수를 400으로 거절한다", async () => {
    const { owner } = await carWithOwner();
    expect(await code(owner.device_token, cs({ today_feed_count: 2147483648 }))).toBe("bad_request");
    expect(await sends(owner.device_token, cs({ today_feed_count: 2147483647 }))).toBe(201);
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
