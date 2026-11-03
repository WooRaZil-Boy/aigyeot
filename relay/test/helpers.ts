// 시험 도우미: Worker를 HTTP로 부르고 차·폰을 만든다.
import { exports } from "cloudflare:workers";
import { ulid } from "../src/util";

export const BASE = "http://relay.test";

type CallOpts = { token?: string; body?: unknown; raw?: BodyInit; headers?: Record<string, string>; ip?: string };

export async function call(method: string, path: string, o: CallOpts = {}) {
  const headers: Record<string, string> = { "CF-Connecting-IP": o.ip ?? "test-ip", ...o.headers };
  if (o.token) headers.Authorization = `Bearer ${o.token}`;
  if (o.body !== undefined) headers["Content-Type"] = "application/json; charset=utf-8";
  const res = await exports.default.fetch(`${BASE}${path}`, {
    method,
    headers,
    body: o.raw ?? (o.body === undefined ? undefined : JSON.stringify(o.body)),
  });
  const text = res.status === 101 ? "" : await res.text();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const json: any = text && res.headers.get("Content-Type")?.includes("json") ? JSON.parse(text) : undefined;
  return { status: res.status, json, res };
}

export const envelope = (type: string, body: object, extra: object = {}) => ({
  v: 1,
  id: ulid(),
  type,
  ts: new Date().toISOString(),
  body,
  ...extra,
});

const uniqueIp = () => `ip-${crypto.randomUUID()}`;

export async function newCar() {
  const r = await call("POST", "/v1/cars", { body: { platform: "pleos", app_version: "1.0.0" }, ip: uniqueIp() });
  if (r.status !== 201) throw new Error(`car ${r.status}`);
  return r.json as { car_id: string; device_id: string; car_token: string };
}

export async function pairWith(code: string, platform = "android", pushToken: string | null = null) {
  const r = await call("POST", "/v1/pair", {
    body: { code, platform, push_token: pushToken, app_version: "1.0.0" },
    ip: uniqueIp(),
  });
  if (r.status !== 201) throw new Error(`pair ${r.status} ${JSON.stringify(r.json)}`);
  return r.json as { car_id: string; device_id: string; device_token: string; role: string };
}

/** 차 하나와 owner 폰 하나. 차 받은 함의 paired는 ack해 비운다. */
export async function carWithOwner() {
  const car = await newCar();
  const code = (await call("POST", "/v1/pair-codes", { token: car.car_token, body: {} })).json.code;
  const owner = await pairWith(code);
  await drain(car.car_token);
  await drain(owner.device_token);
  return { car, owner };
}

export async function addGuardian(ownerToken: string, pushToken: string | null = null) {
  const inv = await call("POST", "/v1/invites", { token: ownerToken, body: envelope("invite_created", {}) });
  return pairWith(inv.json.code, "ios", pushToken);
}

export async function inbox(token: string) {
  return (await call("GET", "/v1/inbox", { token })).json.messages as Array<Record<string, any>>;
}

/** 받은 함을 모두 ack한다. */
export async function drain(token: string) {
  const ids = (await inbox(token)).map((m) => m.id);
  if (ids.length) await call("POST", "/v1/ack", { token, body: envelope("ack", { ids }) });
  return ids;
}

export async function send(token: string, type: string, body: object, extra: object = {}) {
  return call("POST", "/v1/messages", { token, body: envelope(type, body, extra) });
}

export const m4a = (size = 64) => {
  const b = new Uint8Array(size);
  b.set(new TextEncoder().encode("ftypM4A "), 4);
  return b;
};
