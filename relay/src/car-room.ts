// 차 방(CarRoom, 차마다 하나, 계약 0장·4.2): 기기·토큰 해시, 받는 쪽별 메시지함, seq, 노래 파일, 푸시, WebSocket.
import { DurableObject } from "cloudflare:workers";
import type { CodeKind, Directory } from "./directory";
import { AckBody, Envelope, PatchDeviceRequest, bodySchemas } from "./protocol";
import { type PushJob, pushConfigured, sendPush } from "./push";
import {
  DAY,
  HOUR,
  HttpError,
  MIN,
  RATE_LIMITS_DDL,
  ULID_RE,
  bearer,
  decode,
  errorResponse,
  instant,
  iso,
  json,
  newDeviceId,
  newToken,
  readBytes,
  readJson,
  rpc,
  canonicalJson,
  sha256,
  take,
  ulid,
  unwrap,
  utf8Length,
} from "./util";

const DDL = `
CREATE TABLE IF NOT EXISTS room (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  car_id TEXT NOT NULL,
  next_seq INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  last_activity_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  role TEXT NOT NULL CHECK (role IN ('car','owner','guardian')),
  platform TEXT NOT NULL CHECK (platform IN ('pleos','android','ios')),
  token_hash BLOB NOT NULL UNIQUE,
  push_token TEXT,
  push_token_at INTEGER,
  handoff_alerts INTEGER NOT NULL DEFAULT 0,
  paired_at INTEGER NOT NULL,
  last_seen_at INTEGER
);
CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY,
  seq INTEGER NOT NULL UNIQUE,
  type TEXT NOT NULL,
  sender_device TEXT,
  sent_ts TEXT NOT NULL,
  body TEXT,
  body_hash BLOB NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS messages_created ON messages(created_at);
CREATE TABLE IF NOT EXISTS deliveries (
  recipient_device TEXT NOT NULL,
  message_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (recipient_device, message_id)
);
CREATE INDEX IF NOT EXISTS deliveries_inbox ON deliveries(recipient_device, seq);
CREATE INDEX IF NOT EXISTS deliveries_message ON deliveries(message_id);
CREATE INDEX IF NOT EXISTS deliveries_created ON deliveries(created_at);
CREATE TABLE IF NOT EXISTS songs (
  song_id TEXT PRIMARY KEY,
  uploader_device TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  uploaded_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS songs_uploaded ON songs(uploaded_at);
CREATE TABLE IF NOT EXISTS song_blobs (
  song_id TEXT PRIMARY KEY,
  data BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS push_jobs (
  message_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  PRIMARY KEY (message_id, device_id)
);
CREATE INDEX IF NOT EXISTS push_jobs_next ON push_jobs(next_at);
${RATE_LIMITS_DDL}`;

type DeviceRole = "car" | "owner" | "guardian";
type Device = {
  device_id: string;
  role: DeviceRole;
  platform: string;
  token_hash: ArrayBuffer;
  push_token: string | null;
  handoff_alerts: number;
  paired_at: number;
  last_seen_at: number | null;
};
type Room = { car_id: string; next_seq: number; created_at: number; last_activity_at: number };
type MessageRow = { id: string; seq: number; type: string; sender_device: string | null; sent_ts: string; body: string };
type Attachment = { device_id: string; role: DeviceRole; connected_at: number };

const SONG_MAX = 2_000_000; // G30
const PUSH_VALID = 30 * MIN; // G28
const ONLINE = 90_000; // 2장 '생존 확인'
const KEEP = 7 * DAY; // 명세서 14장

/** 역할별로 `POST /v1/messages`로 보낼 수 있는 type(6.3). 표에 없으면 403. */
const SENDS: Record<"car" | "owner", readonly string[]> = {
  car: ["song_selected", "song_status", "feeding_state", "report_summary", "handoff_alert"],
  owner: ["feeding_started", "song_added", "song_removed", "song_selected", "state_request"],
};
const CAR_SENT = new Set(SENDS.car);

/** 저장한 메시지의 `from.role`. 보낸 기기가 지워져도 type으로 정해진다(차가 보낸 song_selected는 받는 쪽이 없다). */
const senderRole = (type: string, sender: string | null) =>
  sender === null ? "relay" : CAR_SENT.has(type) && type !== "song_selected" ? "car" : "owner";

const need = (dev: Device, ...roles: DeviceRole[]) => {
  if (!roles.includes(dev.role)) throw new HttpError(403, "forbidden_role");
};

/** 봉투 검사(1.1 순서: 형식 → 판 → type). */
export function decodeEnvelope(v: unknown): Envelope {
  const env = decode(Envelope, v);
  if (env.v !== 1) throw new HttpError(400, "unsupported_version");
  if (!(env.type in bodySchemas)) throw new HttpError(400, "unknown_type");
  if (!ULID_RE.test(env.id)) throw new HttpError(400, "bad_request", "id가 ULID가 아님");
  return env;
}

/** 중계가 쓰는 body 필드만 검사한다(6.4). 나머지는 받는 쪽이 검사한다. */
function checkOwnFields(env: Envelope) {
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
    case "handoff_alert":
      if (instant(b.occurred_at) === null) bad("occurred_at");
      if (typeof b.text !== "string" || [...b.text].length > 80) bad("text");
      break;
  }
}

const sameBytes = (a: ArrayBuffer, b: ArrayBuffer) => {
  const x = new Uint8Array(a);
  const y = new Uint8Array(b);
  return x.length === y.length && x.every((v, i) => v === y[i]);
};

export class CarRoom extends DurableObject<Env> {
  private sql = this.ctx.storage.sql;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql.exec(DDL);
    // ping은 방을 깨우지 않는다(2장 'Hibernation').
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  // ---------------------------------------------------------------- RPC

  /** `POST /v1/cars`: 방과 차 기기 행을 만든다. */
  async init(carId: string, deviceId: string, tokenHash: ArrayBuffer) {
    const now = Date.now();
    this.ctx.storage.transactionSync(() => {
      this.sql.exec("INSERT INTO room (id, car_id, created_at, last_activity_at) VALUES (1, ?, ?, ?)", carId, now, now);
      this.sql.exec(
        "INSERT INTO devices (device_id, role, platform, token_hash, paired_at, last_seen_at) VALUES (?, 'car', 'pleos', ?, ?, ?)",
        deviceId,
        tokenHash,
        now,
        now,
      );
    });
    await this.scheduleAlarm(); // 폰이 붙지 않으면 30일 뒤 지운다(G35)
  }

  /** `POST /v1/pair`의 차 방 몫: 초대 코드면 만든 기기가 지금도 `owner`인지 본 뒤 기기 행을 만든다(1.4, G38). */
  pair(kind: CodeKind, createdBy: string, platform: "android" | "ios", pushToken: string | null) {
    return rpc(async () => {
      const role = kind === "pair" ? "owner" : "guardian";
      const room = this.room();
      if (!room) throw new HttpError(404, "code_invalid");
      const deviceId = newDeviceId();
      const token = newToken(room.car_id, deviceId);
      const hash = await sha256(token);
      const now = Date.now();
      this.ctx.storage.transactionSync(() => {
        if (kind === "invite" && !this.device(createdBy, "owner")) throw new HttpError(404, "code_invalid");
        this.sql.exec(
          "INSERT INTO devices VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
          deviceId,
          role,
          platform,
          hash,
          pushToken,
          pushToken ? now : null,
          role === "guardian" ? 1 : 0, // G12 기본값
          now,
          now,
        );
        this.touch(now);
        this.pairedMessage(deviceId, now);
      });
      await this.scheduleAlarm();
      return { car_id: room.car_id, device_id: deviceId, device_token: token, role };
    });
  }

  /** `POST /v1/llm`의 차 방 몫: 토큰·역할·차당 횟수(G6). */
  llmGate(authorization: string | null) {
    return rpc(async () => {
      const dev = await this.auth(authorization);
      need(dev, "car");
      take(this.sql, "llm:min", 6, MIN);
      take(this.sql, "llm:day", 100, DAY);
    });
  }

  // ---------------------------------------------------------------- HTTP

  async fetch(req: Request): Promise<Response> {
    try {
      return await this.route(req);
    } catch (e) {
      return errorResponse(e);
    }
  }

  private async route(req: Request): Promise<Response> {
      const dev = await this.auth(req.headers.get("Authorization"));
      const url = new URL(req.url);
      const songId = /^\/v1\/songs\/([^/]+)$/.exec(url.pathname)?.[1];
      const deviceId = /^\/v1\/devices\/([^/]+)$/.exec(url.pathname)?.[1];
      const route = `${req.method} ${songId ? "/v1/songs/:id" : deviceId && deviceId !== "me" ? "/v1/devices/:id" : url.pathname}`;
      switch (route) {
        case "GET /v1/ws":
          return this.webSocket(req, dev);
        case "POST /v1/pair-codes":
          return this.createPairCode(req, dev);
        case "DELETE /v1/pair-codes":
          need(dev, "car");
          await this.directory().deletePairCodes(this.room()!.car_id);
          return json(204);
        case "POST /v1/invites":
          return this.createInvite(req, dev);
        case "GET /v1/devices":
          return this.listDevices(dev);
        case "PATCH /v1/devices/me":
          return this.patchMe(req, dev);
        case "DELETE /v1/devices/me":
          need(dev, "owner", "guardian");
          return this.removeDevice(dev, "self");
        case "DELETE /v1/devices/:id": {
          need(dev, "car");
          const target = this.device(deviceId!);
          if (!target || target.role === "car") throw new HttpError(404, "not_found");
          return this.removeDevice(target, "car");
        }
        case "POST /v1/messages":
          return this.postMessage(req, dev);
        case "GET /v1/inbox":
          return this.inbox(url, dev);
        case "POST /v1/ack": {
          const env = decodeEnvelope(await readJson(req));
          if (env.type !== "ack") throw new HttpError(400, "bad_request", "ack 봉투가 아님");
          this.ack(dev.device_id, decode(AckBody, env.body).ids);
          return json(204);
        }
        case "PUT /v1/songs/:id":
          return this.putSong(req, dev, songId!);
        case "GET /v1/songs/:id":
          return this.getSong(dev, songId!);
        case "DELETE /v1/records":
          return this.clearRecords(dev);
      }
      throw new HttpError(404, "not_found");
  }

  // ---------------------------------------------------------------- 인증·저장 도우미

  private room(): Room | undefined {
    return this.sql.exec<Room>("SELECT car_id, next_seq, created_at, last_activity_at FROM room").toArray()[0];
  }

  private device(id: string, role?: DeviceRole): Device | undefined {
    const d = this.sql.exec<Device>("SELECT * FROM devices WHERE device_id = ?", id).toArray()[0];
    return d && (!role || d.role === role) ? d : undefined;
  }

  private ids(where: string, ...args: (string | number)[]): string[] {
    return this.sql
      .exec<{ device_id: string }>(`SELECT device_id FROM devices WHERE ${where} ORDER BY paired_at`, ...args)
      .toArray()
      .map((r) => r.device_id);
  }

  private touch(now: number, deviceId?: string) {
    if (deviceId) this.sql.exec("UPDATE devices SET last_seen_at = ? WHERE device_id = ?", now, deviceId);
    this.sql.exec("UPDATE room SET last_activity_at = ?", now);
  }

  /** 토큰 확인(1.2): SHA-256을 저장된 해시와 timingSafeEqual로 비교한다. */
  private async auth(header: string | null): Promise<Device> {
    const b = bearer(header);
    const hash = b ? await sha256(b.token) : null;
    const dev = b ? this.device(b.deviceId) : undefined;
    if (!b || !hash || !dev || this.room()?.car_id !== b.carId) throw new HttpError(401, "unauthorized");
    if (!crypto.subtle.timingSafeEqual(hash, dev.token_hash)) throw new HttpError(401, "unauthorized");
    this.touch(Date.now(), dev.device_id);
    return dev;
  }

  private directory(): DurableObjectStub<Directory> {
    return this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("directory"));
  }

  /** `seq`를 매겨 메시지와 받는 쪽마다 메시지함 줄을 넣는다. transactionSync 안에서 부른다(4.2). */
  private store(
    m: { id: string; type: string; sender: string | null; sentTs: string; body: object; hash: ArrayBuffer },
    recipients: string[],
    now: number,
  ): Envelope {
    const seq = this.sql
      .exec<{ seq: number }>("UPDATE room SET next_seq = next_seq + 1 RETURNING next_seq - 1 AS seq")
      .one().seq;
    this.sql.exec(
      "INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
      m.id,
      seq,
      m.type,
      m.sender,
      m.sentTs,
      recipients.length ? JSON.stringify(m.body) : null, // 받는 쪽이 없으면 본문을 바로 비운다
      m.hash,
      now,
    );
    for (const r of recipients) this.sql.exec("INSERT INTO deliveries VALUES (?, ?, ?, ?)", r, m.id, seq, now);
    const env = this.envelope({ id: m.id, seq, type: m.type, sender_device: m.sender, sent_ts: m.sentTs, body: "" }, m.body);
    // 열린 소켓으로 바로 보낸다(seq를 매긴 그 동기 구간이라 기기마다 seq 순서가 지켜진다, G40).
    const text = JSON.stringify(env);
    for (const r of recipients) this.sendTo(r, text);
    return env;
  }

  /** OPEN 소켓에만 보낸다. 닫히는 소켓의 예외가 메시지함 저장(transactionSync)을 되돌리지 않게 소켓마다 잡는다. */
  private sendTo(deviceId: string, text: string) {
    for (const ws of this.ctx.getWebSockets(`dev:${deviceId}`)) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      try {
        ws.send(text);
      } catch {
        // 받은 함에 남아 있으므로 다시 붙을 때 받는다.
      }
    }
  }

  private closeAll(tag: string | undefined, code: number, reason: string) {
    for (const ws of tag ? this.ctx.getWebSockets(tag) : this.ctx.getWebSockets()) {
      try {
        ws.close(code, reason);
      } catch {
        // 이미 닫히는 중
      }
    }
  }

  /** 본문을 읽고 해시를 구하는 동안 기기가 끊겼을 수 있다. 저장 트랜잭션 안에서 다시 본다(1.4 '끊긴 폰은 다음 요청에서 401'). */
  private stillPaired(dev: Device) {
    if (!this.device(dev.device_id, dev.role)) throw new HttpError(401, "unauthorized");
  }

  /** 중계가 고치는 body 필드(6.4): 미래 수유 시각은 처음 받은 시각으로 고정한다(G33). */
  private fixBody(type: string, body: Record<string, unknown>, receivedAt: number) {
    return type === "feeding_started" && instant(body.at)! > receivedAt ? { ...body, at: iso(receivedAt) } : body;
  }

  private envelope(row: MessageRow, body?: object, car = this.room()!.car_id): Envelope {
    return {
      v: 1,
      id: row.id,
      seq: row.seq,
      type: row.type,
      car,
      from: { role: senderRole(row.type, row.sender_device), device: row.sender_device },
      ts: row.sent_ts,
      body: (body ?? JSON.parse(row.body)) as Record<string, unknown>,
    };
  }

  /** 중계가 만드는 메시지(`paired`·`unpaired`): 차와 `owner` 폰 전부에게(G14). */
  private relayMessage(type: "paired" | "unpaired", body: object, now: number) {
    return this.store(
      { id: ulid(now), type, sender: null, sentTs: iso(now), body, hash: new ArrayBuffer(0) },
      this.ids("role IN ('car','owner')"),
      now,
    );
  }

  private pairedMessage(deviceId: string, now: number) {
    const d = this.device(deviceId)!;
    return this.relayMessage(
      "paired",
      {
        device_id: d.device_id,
        role: d.role,
        platform: d.platform,
        paired_at: iso(d.paired_at),
        handoff_alerts: d.handoff_alerts === 1,
      },
      now,
    );
  }

  /** 받는 쪽이 모두 ack한(메시지함 줄이 없는) 메시지의 본문을 비운다(4.4 '넘긴 뒤 바로 지움'). */
  private dropDeliveredBodies(ids?: string[]) {
    // ids가 있으면 그 메시지만 본다(ack 경로). 없으면 전체(기기 삭제처럼 줄이 한꺼번에 사라진 경우).
    const where = "body IS NOT NULL AND NOT EXISTS (SELECT 1 FROM deliveries WHERE message_id = messages.id)";
    if (!ids) return void this.sql.exec(`UPDATE messages SET body = NULL WHERE ${where}`);
    for (const id of ids) this.sql.exec(`UPDATE messages SET body = NULL WHERE id = ? AND ${where}`, id);
  }

  /** 알람: 메시지·노래 7일, 푸시, 빈 방 정리 중 가장 이른 시각(4.4). 후보가 없으면 지운다. */
  private async scheduleAlarm() {
    const t = this.sql
      .exec<{ t: number | null }>(
        `SELECT min(t) AS t FROM (
          SELECT min(created_at) + ${KEEP} AS t FROM messages
          UNION ALL SELECT min(uploaded_at) + ${KEEP} FROM songs
          UNION ALL SELECT min(next_at) FROM push_jobs WHERE ${pushConfigured(this.env) ? 1 : 0}
          UNION ALL SELECT last_activity_at + ${30 * DAY} FROM room
            WHERE NOT EXISTS (SELECT 1 FROM devices WHERE role <> 'car'))`,
      )
      .one().t;
    if (t === null) await this.ctx.storage.deleteAlarm();
    else await this.ctx.storage.setAlarm(t);
  }

  // ---------------------------------------------------------------- 엔드포인트

  private async createPairCode(req: Request, dev: Device) {
    need(dev, "car");
    await readBytes(req, 65_536); // 요청 본문({})은 쓰지 않지만 다 읽어야 스트림 오류가 나지 않는다
    take(this.sql, "paircode", 6, MIN);
    const { code, expiresAt } = await this.directory().createPairCode(this.room()!.car_id, dev.device_id);
    return json(201, { code, expires_at: iso(expiresAt), link: `aigyeot://pair?code=${code}` });
  }

  private async createInvite(req: Request, dev: Device) {
    need(dev, "owner");
    const env = decodeEnvelope(await readJson(req));
    if (env.type !== "invite_created") throw new HttpError(400, "bad_request", "invite_created 봉투가 아님");
    this.stillPaired(dev);
    take(this.sql, `invite:${dev.device_id}`, 5, HOUR);
    const { code, expiresAt } = unwrap(await this.directory().createInvite(this.room()!.car_id, dev.device_id));
    if (!this.device(dev.device_id, "owner")) {
      // 만드는 사이 끊겼으면 그 초대를 바로 폐기한다(G38).
      await this.directory().revokeInvites(dev.device_id);
      throw new HttpError(401, "unauthorized");
    }
    return json(201, {
      code,
      display: `${code.slice(0, 5)}-${code.slice(5)}`,
      expires_at: iso(expiresAt),
      link: `aigyeot://pair?code=${code}`,
    });
  }

  private listDevices(dev: Device) {
    need(dev, "car", "owner");
    const now = Date.now();
    const snapshot = this.room()!.next_seq - 1; // 목록과 같은 동기 구간에서 읽는다
    const devices = this.sql
      .exec<Device>("SELECT * FROM devices ORDER BY paired_at")
      .toArray()
      .map((d) => {
        // ping은 방을 깨우지 않으므로 자동 응답 시각(없으면 붙은 시각)과 견준다(4.2 '마지막 연락').
        const pings = this.ctx
          .getWebSockets(`dev:${d.device_id}`)
          .map(
            (ws) =>
              this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime() ??
              (ws.deserializeAttachment() as Attachment).connected_at,
          );
        const ping = pings.length ? Math.max(...pings) : null;
        const lastSeen = Math.max(d.last_seen_at ?? 0, ping ?? 0) || null;
        return {
          device_id: d.device_id,
          role: d.role,
          platform: d.platform,
          paired_at: iso(d.paired_at),
          last_seen_at: lastSeen === null ? null : iso(lastSeen),
          online: ping !== null && now - ping < ONLINE,
          ...(d.role === "car" ? {} : { handoff_alerts: d.handoff_alerts === 1 }),
        };
      });
    return json(200, { snapshot_seq: snapshot, devices });
  }

  private async patchMe(req: Request, dev: Device) {
    need(dev, "owner", "guardian");
    const p = decode(PatchDeviceRequest, await readJson(req));
    if (p.handoff_alerts !== undefined && dev.role === "owner") throw new HttpError(403, "forbidden_role");
    const now = Date.now();
    const d = this.ctx.storage.transactionSync(() => {
      const cur = this.device(dev.device_id);
      if (!cur) throw new HttpError(401, "unauthorized");
      if (p.push_token !== undefined) {
        this.sql.exec(
          "UPDATE devices SET push_token = ?, push_token_at = ? WHERE device_id = ?",
          p.push_token,
          now,
          cur.device_id,
        );
      }
      const alerts = p.handoff_alerts === undefined ? cur.handoff_alerts : p.handoff_alerts ? 1 : 0;
      if (alerts !== cur.handoff_alerts) {
        this.sql.exec("UPDATE devices SET handoff_alerts = ? WHERE device_id = ?", alerts, cur.device_id);
        this.pairedMessage(cur.device_id, now); // 바뀌면 paired를 다시 보낸다(G12)
      }
      return this.device(cur.device_id)!;
    });
    await this.scheduleAlarm();
    return json(200, {
      device_id: d.device_id,
      role: d.role,
      platform: d.platform,
      handoff_alerts: d.handoff_alerts === 1,
      push_token_set: d.push_token !== null,
    });
  }

  /** 끊기(1.4): 기기 행·메시지함 줄·푸시 작업을 지우고 `unpaired`를 보낸 뒤 소켓을 4001로 닫는다. */
  private async removeDevice(target: Device, by: "car" | "self") {
    const now = Date.now();
    const removed = this.ctx.storage.transactionSync(() => {
      if (!this.device(target.device_id)) return false; // 동시에 온 다른 끊기가 먼저 지웠다(unpaired는 한 번만)
      this.sql.exec("DELETE FROM devices WHERE device_id = ?", target.device_id);
      this.sql.exec("DELETE FROM deliveries WHERE recipient_device = ?", target.device_id);
      this.sql.exec("DELETE FROM push_jobs WHERE device_id = ?", target.device_id);
      this.dropDeliveredBodies();
      this.relayMessage("unpaired", { device_id: target.device_id, role: target.role, by }, now);
      return true;
    });
    if (!removed) return json(204);
    this.closeAll(`dev:${target.device_id}`, 4001, "unauthorized");
    if (target.role === "owner") {
      // 실패해도 끊기는 성공이다. 남은 초대는 POST /v1/pair의 created_by 확인이 막는다(G38).
      await this.directory()
        .revokeInvites(target.device_id)
        .catch(() => console.warn("invite revoke failed"));
    }
    await this.scheduleAlarm();
    return json(204);
  }

  private async postMessage(req: Request, dev: Device) {
    need(dev, "car", "owner");
    const env = decodeEnvelope(await readJson(req));
    const role = dev.role as "car" | "owner";
    if (!SENDS[role].includes(env.type)) throw new HttpError(403, "forbidden_role");
    if (env.to !== undefined && !(role === "car" && (env.type === "feeding_state" || env.type === "song_status"))) {
      throw new HttpError(400, "bad_request", "to는 차의 feeding_state·song_status에만 쓴다");
    }
    checkOwnFields(env);
    const hash = await sha256(`${env.type}\n${env.to ?? ""}\n${canonicalJson(env.body)}`);
    const now = Date.now();

    const out = this.ctx.storage.transactionSync(() => {
      // 같은 id 다시 보내기(1.4): 내용이 같으면 200과 처음 seq, 다르면 409.
      this.stillPaired(dev);
      const old = this.sql
        .exec<{ seq: number; body: string | null; body_hash: ArrayBuffer; created_at: number }>(
          "SELECT seq, body, body_hash, created_at FROM messages WHERE id = ?",
          env.id,
        )
        .toArray()[0];
      if (old) {
        if (!sameBytes(old.body_hash, hash)) throw new HttpError(409, "id_conflict");
        const left = this.sql
          .exec<{ n: number }>("SELECT count(*) AS n FROM deliveries WHERE message_id = ?", env.id)
          .one().n;
        // 모두 ack해 본문을 비웠으면 처음 받은 시각으로 같은 보정을 다시 만든다(해시가 같으니 보낸 본문도 같다).
        const body = old.body ? JSON.parse(old.body) : this.fixBody(env.type, env.body, old.created_at);
        return { status: 200, seq: old.seq, recipients: left, body };
      }

      take(this.sql, `msg:${dev.device_id}`, 60, MIN);
      if (env.type === "state_request") take(this.sql, `state:${dev.device_id}`, 6, HOUR);
      if (env.type === "song_added" && !this.sql.exec("SELECT 1 FROM songs WHERE song_id = ?", env.body.song_id).toArray().length) {
        throw new HttpError(422, "song_blob_missing");
      }

      const body = this.fixBody(env.type, env.body, now);

      const recipients = this.recipients(env.type, role, env.to);
      const stored = this.store(
        { id: env.id, type: env.type, sender: dev.device_id, sentTs: env.ts, body, hash },
        recipients,
        now,
      );

      // 차가 그 곡의 song_status를 보내면 중계의 노래 파일을 지운다(G29).
      if (env.type === "song_status") {
        this.sql.exec("DELETE FROM song_blobs WHERE song_id = ?", body.song_id as string);
        this.sql.exec("DELETE FROM songs WHERE song_id = ?", body.song_id as string);
      }
      // 인계 알림 푸시: 유효 시각은 occurred_at + 30분 고정(G28).
      if (env.type === "handoff_alert" && pushConfigured(this.env)) {
        const expires = instant(body.occurred_at)! + PUSH_VALID;
        if (expires - now >= 1000) {
          for (const r of recipients) {
            if (this.device(r)?.push_token) {
              this.sql.exec("INSERT OR IGNORE INTO push_jobs VALUES (?, ?, 0, ?, ?)", env.id, r, now, expires);
            }
          }
        }
      }
      return { status: 201, seq: stored.seq!, recipients: recipients.length, body };
    });

    if (out.status === 201) await this.scheduleAlarm();
    return json(out.status, { id: env.id, seq: out.seq, recipients: out.recipients, body: out.body });
  }

  /** 받는 쪽은 중계가 type으로 정한다(3.2). `to`는 차의 상태 답에만(G39). */
  private recipients(type: string, sender: "car" | "owner", to?: string): string[] {
    switch (type) {
      case "feeding_started":
      case "song_added":
      case "song_removed":
      case "state_request":
        return this.ids("role = 'car'");
      case "song_selected":
        return sender === "car" ? [] : this.ids("role = 'car'"); // 차가 보낸 것은 seq만 받는다(G20)
      case "song_status":
      case "feeding_state":
        return to === undefined ? this.ids("role = 'owner'") : this.ids("role = 'owner' AND device_id = ?", to);
      case "report_summary":
        return this.ids("role IN ('owner','guardian')");
      case "handoff_alert":
        return this.ids("role = 'guardian' AND handoff_alerts = 1");
    }
    return [];
  }

  /** 이 기기 몫 중 ack하지 않은 것, seq 오름차순(G40). */
  private pending(deviceId: string, afterSeq = 0, limit = -1): Envelope[] {
    const car = this.room()!.car_id;
    return this.sql
      .exec<MessageRow>(
        `SELECT m.id, m.seq, m.type, m.sender_device, m.sent_ts, m.body FROM deliveries d
         JOIN messages m ON m.id = d.message_id
         WHERE d.recipient_device = ? AND d.seq > ? ORDER BY d.seq LIMIT ?`,
        deviceId,
        afterSeq,
        limit,
      )
      .toArray()
      .map((r) => this.envelope(r, undefined, car));
  }

  private inbox(url: URL, dev: Device) {
    const after = Math.max(0, Math.trunc(Number(url.searchParams.get("after_seq") ?? 0)) || 0);
    const limit = Math.min(100, Math.max(1, Math.trunc(Number(url.searchParams.get("limit") ?? 100)) || 100));
    const rows = this.pending(dev.device_id, after, limit + 1);
    return json(200, { messages: rows.slice(0, limit), more: rows.length > limit });
  }

  private ack(deviceId: string, ids: string[]) {
    if (ids.length < 1 || ids.length > 100) throw new HttpError(400, "bad_request", "ids는 1~100개");
    this.ctx.storage.transactionSync(() => {
      for (const id of ids) {
        // 모르는 id는 조용히 넘긴다. 이 기기의 푸시 작업도 지운다(5.1).
        this.sql.exec("DELETE FROM deliveries WHERE recipient_device = ? AND message_id = ?", deviceId, id);
        this.sql.exec("DELETE FROM push_jobs WHERE message_id = ? AND device_id = ?", id, deviceId);
      }
      this.dropDeliveredBodies(ids);
    });
  }

  private async putSong(req: Request, dev: Device, songId: string) {
    need(dev, "owner");
    if (!ULID_RE.test(songId)) throw new HttpError(400, "bad_request", "song_id가 ULID가 아님");
    const type = req.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase();
    if (type !== "audio/mp4") throw new HttpError(415, "unsupported_media");
    if (Number(req.headers.get("Content-Length") ?? 0) > SONG_MAX) throw new HttpError(413, "too_large");
    take(this.sql, `song:${dev.device_id}`, 10, HOUR);
    const data = await readBytes(req, SONG_MAX);
    // MP4 ftyp 상자: 4~8번째 바이트(1.4)
    if (data.byteLength < 8 || new TextDecoder().decode(data.subarray(4, 8)) !== "ftyp") {
      throw new HttpError(415, "unsupported_media");
    }
    const now = Date.now();
    this.ctx.storage.transactionSync(() => {
      this.stillPaired(dev);
      if (this.sql.exec("SELECT 1 FROM songs WHERE song_id = ?", songId).toArray().length) {
        throw new HttpError(409, "already_exists");
      }
      if (this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM songs").one().n >= 3) {
        throw new HttpError(409, "song_quota");
      }
      this.sql.exec("INSERT INTO songs VALUES (?, ?, ?, ?)", songId, dev.device_id, data.byteLength, now);
      this.sql.exec("INSERT INTO song_blobs VALUES (?, ?)", songId, data.buffer as ArrayBuffer); // readBytes가 딱 맞는 크기로 만든다
    });
    await this.scheduleAlarm();
    return json(201, { song_id: songId, size_bytes: data.byteLength });
  }

  private getSong(dev: Device, songId: string) {
    need(dev, "car");
    const row = this.sql.exec<{ data: ArrayBuffer }>("SELECT data FROM song_blobs WHERE song_id = ?", songId).toArray()[0];
    if (!row) throw new HttpError(404, "not_found");
    return new Response(row.data, { headers: { "Content-Type": "audio/mp4", Date: new Date().toUTCString() } });
  }

  /** '모든 기록 지우기'의 중계 몫: 리포트·인계 알림 보관분만(1.4). */
  private clearRecords(dev: Device) {
    need(dev, "car");
    this.ctx.storage.transactionSync(() => {
      const sel = "SELECT id FROM messages WHERE type IN ('report_summary','handoff_alert')";
      this.sql.exec(`DELETE FROM deliveries WHERE message_id IN (${sel})`);
      this.sql.exec(`DELETE FROM push_jobs WHERE message_id IN (${sel})`);
      this.sql.exec("DELETE FROM messages WHERE type IN ('report_summary','handoff_alert')");
    });
    return json(204);
  }

  // ---------------------------------------------------------------- WebSocket(2장)

  private webSocket(req: Request, dev: Device) {
    if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      throw new HttpError(400, "bad_request", "WebSocket 업그레이드가 아님");
    }
    take(this.sql, `ws:${dev.device_id}`, 10, MIN);
    const tag = `dev:${dev.device_id}`;
    this.closeAll(tag, 4002, "replaced"); // 같은 기기의 새 연결
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [tag]);
    server.serializeAttachment({ device_id: dev.device_id, role: dev.role, connected_at: Date.now() } satisfies Attachment);
    // 붙자마자 밀린 메시지를 seq 순서로 모두 보낸다.
    for (const env of this.pending(dev.device_id)) server.send(JSON.stringify(env));
    return new Response(null, { status: 101, webSocket: client });
  }

  /** 들어오는 프레임은 `ack` 봉투만 처리한다. 나머지는 무시한다. */
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    // JSON 64 KB 상한(6.4). 계약에 이 경우의 닫기 코드는 없어 프레임만 버린다(답도 없다).
    if (typeof message !== "string" || utf8Length(message) > 65_536) return;
    try {
      const env = decodeEnvelope(JSON.parse(message));
      const { device_id } = ws.deserializeAttachment() as Attachment;
      if (env.type === "ack" && this.device(device_id)) this.ack(device_id, decode(AckBody, env.body).ids);
    } catch {
      // 형식이 틀린 프레임은 버린다(답은 없다).
    }
  }

  // ---------------------------------------------------------------- 정리(Alarm, 4.4)

  async alarm() {
    const now = Date.now();
    this.ctx.storage.transactionSync(() => {
      const old = now - KEEP;
      this.sql.exec("DELETE FROM deliveries WHERE created_at <= ?", old);
      this.sql.exec("DELETE FROM push_jobs WHERE message_id IN (SELECT id FROM messages WHERE created_at <= ?)", old);
      this.sql.exec("DELETE FROM messages WHERE created_at <= ?", old);
      this.sql.exec("DELETE FROM song_blobs WHERE song_id IN (SELECT song_id FROM songs WHERE uploaded_at <= ?)", old);
      this.sql.exec("DELETE FROM songs WHERE uploaded_at <= ?", old);
      this.sql.exec("DELETE FROM rate_limits WHERE expires_at <= ?", now);
      this.sql.exec("DELETE FROM push_jobs WHERE expires_at <= ?", now);
    });
    if (pushConfigured(this.env)) await this.runPush(now);

    const room = this.room();
    const phones = this.sql.exec("SELECT 1 FROM devices WHERE role <> 'car'").toArray().length;
    if (room && !phones && room.last_activity_at + 30 * DAY <= now) {
      // 폰이 없고 30일 활동이 없으면 방을 지운다(G35). 차는 다음에 401을 받고 다시 등록한다.
      this.closeAll(undefined, 4001, "unauthorized");
      await this.ctx.storage.deleteAlarm();
      await this.ctx.storage.deleteAll();
      this.sql.exec(DDL); // 이 객체가 다시 불리면 빈 표로 401을 준다(행은 남지 않는다)
      return;
    }
    await this.scheduleAlarm();
  }

  /**
   * 때가 된 푸시 작업 보내기(5.5). 응답을 기다리는 사이 기기가 끊기거나 ack·토큰 갱신이 올 수 있으므로
   * 작업마다 보내기 직전에 작업·기기·지금 토큰·본문을 다시 읽는다. 유효 시간은 sendPush가 요청 직전에 다시 구한다.
   */
  private async runPush(now: number) {
    type Job = { attempts: number; expires_at: number; push_token: string | null; body: string | null };
    const due = this.sql
      .exec<{ message_id: string; device_id: string; attempts: number }>(
        "SELECT message_id, device_id, attempts FROM push_jobs WHERE next_at <= ?",
        now,
      )
      .toArray();
    for (const due1 of due) {
      const { message_id, device_id } = due1;
      const done = () => this.sql.exec("DELETE FROM push_jobs WHERE message_id = ? AND device_id = ?", message_id, device_id);
      // 처음 읽은 재시도 횟수. OAuth가 실패해 read()까지 가지 못해도 같은 상한(최대 3번, 5.5)을 쓴다.
      let attempts = due1.attempts;
      // 보내기 직전마다(접근 토큰을 얻은 뒤, 401 재발송 포함) 다시 읽는다.
      const read = (): PushJob | null => {
        const j = this.sql
          .exec<Job>(
            `SELECT j.attempts, j.expires_at, d.push_token, m.body FROM push_jobs j
             JOIN devices d ON d.device_id = j.device_id AND d.role = 'guardian' AND d.handoff_alerts = 1
             JOIN messages m ON m.id = j.message_id
             JOIN deliveries x ON x.message_id = j.message_id AND x.recipient_device = j.device_id
             WHERE j.message_id = ? AND j.device_id = ?`,
            message_id,
            device_id,
          )
          .toArray()[0];
        if (!j?.push_token || !j.body) return null;
        attempts = j.attempts;
        return { msgId: message_id, token: j.push_token, text: String(JSON.parse(j.body).text), expiresAt: j.expires_at };
      };
      const r = await sendPush(this.env, read);
      const after = Date.now(); // 재시도 대기는 응답을 받은 지금부터 잰다
      if ((r.status >= 200 && r.status < 300) || r.status === -1 || r.status === -2) {
        done(); // -2: 끊김·ack·토큰 지움·알림 끔이면 보내지 않는다(메시지함에만 남는다)
      } else if (r.errorCode === "UNREGISTERED" || r.errorCode === "SENDER_ID_MISMATCH") {
        // 토큰 무효는 이 두 오류 코드로만 판단한다(5.5). 실패한 요청의 토큰이 지금 저장된 토큰과 같을 때만 지운다(L51).
        this.sql.exec("UPDATE devices SET push_token = NULL WHERE device_id = ? AND push_token = ?", device_id, r.token!);
        done();
      } else if (r.status === 429 || r.status === 0 || r.status >= 500) {
        const plan = r.status === 429 ? [60, 120, 600] : [30, 120, 600];
        if (attempts >= plan.length) {
          done();
          continue;
        }
        let waitS = r.retryAfterS ?? plan[attempts];
        if (r.status === 429 && attempts === 0) waitS = Math.max(waitS, 60); // 첫 대기는 60초 이상
        this.sql.exec(
          "UPDATE push_jobs SET attempts = attempts + 1, next_at = ? WHERE message_id = ? AND device_id = ?",
          after + waitS * 1000,
          message_id,
          device_id,
        );
      } else {
        console.warn("push dropped", r.status, r.errorCode ?? "");
        done();
      }
    }
  }

}
