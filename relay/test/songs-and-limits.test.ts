// 노래 파일, 보관 기한 정리(Alarm), 동시 요청·크기 상한 경로.
import { exports } from "cloudflare:workers";
import { runDurableObjectAlarm } from "cloudflare:test";
import { describe, expect, it, vi } from "vitest";
import { DAY, ulid } from "../src/util";
import { BASE, call, carWithOwner, drain, envelope, inbox, m4a, newCar, roomOf, send, sqlIn } from "./helpers";

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
    expect((await put(m4a(2_000_000))).status).toBe(201); // 2,000,000바이트가 행 하나에 들어간다
    await put(m4a());
    await put(m4a());
    expect((await put(m4a())).json.error.code).toBe("song_quota");
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

describe("동시 요청·크기 상한", () => {
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
