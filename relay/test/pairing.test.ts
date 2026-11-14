// 차 등록, 연결 코드·초대, 기기 목록·끊기의 정상·오류 경로.
import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import worker from "../src/index";
import { BASE, addGuardian, call, carWithOwner, drain, envelope, inbox, newCar, pairWith } from "./helpers";

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
    expect((await inbox(owner.device_token))[0].type).toBe("paired"); // 새 폰 포함
    // 한 번만 쓰인다
    const again = await call("POST", "/v1/pair", {
      body: { code: pc.json.code, platform: "android", push_token: null, app_version: "1" },
    });
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
    const bad = await call("POST", "/v1/pair", {
      body: { code: "12345", platform: "android", push_token: null, app_version: "1" },
    });
    expect(bad.json.error.code).toBe("bad_code_format");
    // 설치 id당 10분 5번 틀리면 429
    const headers = { "X-Install-Id": crypto.randomUUID() };
    const wrong = { code: "000000", platform: "ios", push_token: null, app_version: "1" };
    for (let i = 0; i < 5; i++) {
      const r = await call("POST", "/v1/pair", { body: wrong, headers, ip: `ip-${i}-${headers["X-Install-Id"]}` });
      expect(r.status).toBe(404);
    }
    const locked = await call("POST", "/v1/pair", { body: wrong, headers });
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
  const invite = (token: string) => call("POST", "/v1/invites", { token, body: envelope("invite_created", {}) });

  it("owner가 초대를 만들고 guardian이 붙는다. 초대는 3개까지", async () => {
    const { car, owner } = await carWithOwner();
    const inv = await invite(owner.device_token);
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
    expect((await invite(g.device_token)).status).toBe(403);
    for (let i = 0; i < 3; i++) await invite(owner.device_token);
    const over = await invite(owner.device_token);
    expect(over.json.error.code).toBe("invite_limit");
  });

  it("끊긴 owner가 만든 초대는 쓸 수 없다", async () => {
    const { car, owner } = await carWithOwner();
    const inv = (await invite(owner.device_token)).json;
    expect((await call("DELETE", `/v1/devices/${owner.device_id}`, { token: car.car_token })).status).toBe(204);
    const r = await call("POST", "/v1/pair", {
      body: { code: inv.code, platform: "ios", push_token: null, app_version: "1" },
    });
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
    const ownerPatch = await call("PATCH", "/v1/devices/me", {
      token: owner.device_token,
      body: { handoff_alerts: true },
    });
    expect(ownerPatch.status).toBe(403);
    const p = await call("PATCH", "/v1/devices/me", {
      token: g.device_token,
      body: { push_token: "tok-1", handoff_alerts: false },
    });
    expect(p.json).toEqual({
      device_id: g.device_id,
      role: "guardian",
      platform: "ios",
      handoff_alerts: false,
      push_token_set: true,
    });
    const [re] = await inbox(car.car_token); // 바뀌면 paired를 다시 보낸다
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
