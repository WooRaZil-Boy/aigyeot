// DELETE /v1/records(모든 기록 지우기)와 지운 뒤 같은 id 다시 보내기.
import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { migrateRecordClears } from "../src/schema";
import { DAY, ulid } from "../src/util";
import { call, carWithOwner, cs, inbox, roomOf, send, sqlIn } from "./helpers";

describe("DELETE /v1/records 뒤 같은 id 다시 보내기", () => {
  const clear = (token: string, dropIds: string[] = [], id = ulid()) =>
    call("DELETE", `/v1/records?id=${id}`, { body: { drop_ids: dropIds }, token });

  const setup = async () => {
    const { car, owner } = await carWithOwner();
    const id = ulid();
    const first = await send(car.car_token, "report_summary", { session_id: "S1" }, { id });
    const wipe = async () => expect((await clear(car.car_token)).status).toBe(204);
    return { car, owner, id, seq: first.json.seq, wipe };
  };

  it("본문·배달은 지우고 id·해시·seq 행은 남긴다", async () => {
    const { car, id, wipe } = await setup();
    await wipe();
    expect(await sqlIn(car.car_id, "SELECT 1 FROM deliveries WHERE message_id = ?", id)).toEqual([]);
    const [row] = await sqlIn<{ body: string | null; body_hash: ArrayBuffer }>(
      car.car_id,
      "SELECT body, body_hash FROM messages WHERE id = ?",
      id,
    );
    expect(row.body).toBeNull();
    expect(row.body_hash.byteLength).toBe(32);
  });

  it("같은 id·같은 본문은 200과 처음 seq이고 받은 함·WebSocket에 다시 나오지 않는다", async () => {
    const { car, owner, id, seq, wipe } = await setup();
    await wipe();
    const again = await send(car.car_token, "report_summary", { session_id: "S1" }, { id });
    expect(again.status).toBe(200);
    expect(again.json.seq).toBe(seq);
    expect((await inbox(owner.device_token)).map((m) => m.id)).not.toContain(id);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM deliveries WHERE message_id = ?", id)).toEqual([]);
  });

  it("같은 id·다른 본문은 409 id_conflict", async () => {
    const { car, id, wipe } = await setup();
    await wipe();
    const r = await send(car.car_token, "report_summary", { session_id: "S2" }, { id });
    expect(r.status).toBe(409);
    expect(r.json.error.code).toBe("id_conflict");
  });

  it("같은 삭제 id의 두 번째 DELETE는 그 사이 들어온 새 메시지를 지우지 않는다", async () => {
    const { car, wipe } = await setup();
    const did = ulid();
    const del = async (id: string) => (await clear(car.car_token, [], id)).status;
    expect(await del(did)).toBe(204);
    const nid = ulid();
    expect((await send(car.car_token, "report_summary", { session_id: ulid() }, { id: nid })).status).toBe(201);
    expect((await send(car.car_token, "report_summary", { session_id: ulid() })).status).toBe(201);
    expect(await del(did)).toBe(204);
    const live = "SELECT 1 FROM messages WHERE body IS NOT NULL AND type IN ('handoff_alert','report_summary')";
    expect(await sqlIn(car.car_id, live)).toHaveLength(2);
    // 다른 id면 다시 지운다
    expect(await del(ulid())).toBe(204);
    expect(await sqlIn(car.car_id, live)).toEqual([]);
    await wipe();
  });

  it("DELETE /v1/records는 care_summary를 지우지 않는다", async () => {
    const { car, owner } = await carWithOwner();
    const id = ulid();
    expect((await send(owner.device_token, "care_summary", cs(), { id })).status).toBe(201);
    expect((await clear(car.car_token)).status).toBe(204);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM messages WHERE id = ? AND body IS NOT NULL", id)).toHaveLength(1);
    expect((await inbox(car.car_token)).map((m) => m.id)).toContain(id);
  });

  it("id가 없거나 ULID가 아니면 400, 권한은 그대로", async () => {
    const { car, owner } = await setup();
    const st = async (q: string, token: string) =>
      (await call("DELETE", `/v1/records${q}`, { token, body: { drop_ids: [] } })).status;
    expect(await st("", car.car_token)).toBe(400);
    expect(await st("?id=abc", car.car_token)).toBe(400);
    expect(await st(`?id=${ulid()}`, owner.device_token)).toBe(403);
    expect((await call("DELETE", `/v1/records?id=${ulid()}`, { body: { drop_ids: [] } })).status).toBe(401);
  });

  it("drop_ids 형식 틀림 400", async () => {
    const { car } = await setup();
    const st = async (body?: unknown) =>
      (await call("DELETE", `/v1/records?id=${ulid()}`, { token: car.car_token, body })).status;
    expect(await st(undefined)).toBe(400); // 본문 없음
    expect(await st({})).toBe(400);
    expect(await st({ drop_ids: "x" })).toBe(400);
    expect(await st({ drop_ids: ["abc"] })).toBe(400);
    expect(await st({ drop_ids: Array.from({ length: 1001 }, () => ulid()) })).toBe(400);
    expect(await st({ drop_ids: [ulid()] })).toBe(204);
  });

  it("지우기는 한 요청: 1,000개가 모두 버림 표시되고 같은 id 재시도는 아무것도 안 함", async () => {
    const { car, owner, id } = await setup();
    const all = Array.from({ length: 1000 }, () => ulid());
    const cid = ulid();
    const del = async (ids: string[]) => (await clear(car.car_token, ids, cid)).status;
    expect(await del(all)).toBe(204);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM record_clears WHERE dropped = 1")).toHaveLength(1000);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM messages WHERE id = ? AND body IS NULL", id)).toHaveLength(1);
    expect(await del([ulid()])).toBe(204); // 재시도: 새 표시를 더하지 않는다
    expect(await sqlIn(car.car_id, "SELECT 1 FROM record_clears WHERE dropped = 1")).toHaveLength(1000);
    for (const late of [all[0], all[999]]) {
      expect((await send(car.car_token, "report_summary", { session_id: ulid() }, { id: late })).status).toBe(200);
      expect((await inbox(owner.device_token)).map((m) => m.id)).not.toContain(late);
    }
    expect(await sqlIn(car.car_id, "PRAGMA table_info(record_clears)")).not.toContainEqual(
      expect.objectContaining({ name: "parts" }),
    );
  });

  it("새 삭제 id 11번째는 429, 같은 id 재시도는 세지 않음", async () => {
    const { car } = await setup();
    const first = ulid();
    for (let i = 0; i < 10; i++) expect((await clear(car.car_token, [], i === 0 ? first : ulid())).status).toBe(204);
    for (let i = 0; i < 5; i++) expect((await clear(car.car_token, [], first)).status).toBe(204); // 재시도는 세지 않는다
    const r = await clear(car.car_token);
    expect(r.status).toBe(429);
    expect(r.json.error.code).toBe("rate_limited");
  });

  it("지우기 때 뺀 id가 늦게 POST되면 배달하지 않고 200", async () => {
    const { car, owner } = await setup();
    const late = ulid();
    expect((await clear(car.car_token, [late])).status).toBe(204);
    const r = await send(car.car_token, "report_summary", { session_id: ulid() }, { id: late });
    expect(r.status).toBe(200);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM messages WHERE id = ?", late)).toEqual([]);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM deliveries WHERE message_id = ?", late)).toEqual([]);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM push_jobs WHERE message_id = ?", late)).toEqual([]);
    expect((await inbox(owner.device_token)).map((m) => m.id)).not.toContain(late);
  });

  it("같은 초에 새로 만든 리포트는 배달한다", async () => {
    const { car, owner } = await setup();
    const ts = new Date().toISOString();
    expect((await clear(car.car_token, [ulid()])).status).toBe(204);
    const id = ulid();
    expect((await send(car.car_token, "report_summary", { session_id: ulid() }, { id, ts })).status).toBe(201);
    expect((await inbox(owner.device_token)).map((m) => m.id)).toContain(id);
  });

  it("이미 있던 메시지도 버림 표시가 남아 기한 뒤 늦은 POST를 막는다", async () => {
    const { car, owner, id } = await setup();
    expect((await clear(car.car_token, [id])).status).toBe(204);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM record_clears WHERE dropped = 1 AND id = ?", id)).toHaveLength(1);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM messages WHERE id = ? AND body IS NULL", id)).toHaveLength(1);
    // 원래 행이 기한으로 지워진 상황
    await sqlIn(car.car_id, "DELETE FROM messages WHERE id = ?", id);
    expect((await send(car.car_token, "report_summary", { session_id: "S1" }, { id })).status).toBe(200);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM messages WHERE id = ?", id)).toEqual([]);
    expect((await inbox(owner.device_token)).map((m) => m.id)).not.toContain(id);
  });

  it("옛 꼴 record_clears 방도 맞춤 뒤 DELETE·버림 표시·늦은 POST가 동작한다", async () => {
    const { car, owner } = await setup();
    await runInDurableObject(roomOf(car.car_id), (_: unknown, st: DurableObjectState) => {
      st.storage.sql.exec("DROP TABLE record_clears");
      st.storage.sql.exec(
        "CREATE TABLE record_clears (id TEXT PRIMARY KEY, at INTEGER NOT NULL, before_at INTEGER NOT NULL, parts INTEGER NOT NULL DEFAULT 1)",
      );
      st.storage.sql.exec("INSERT INTO record_clears (id, at, before_at) VALUES ('OLD', ?, 1)", Date.now());
      migrateRecordClears(st.storage.sql);
      migrateRecordClears(st.storage.sql); // 두 번 돌려도 같다
    });
    const late = ulid();
    expect((await clear(car.car_token, [late])).status).toBe(204);
    expect((await send(car.car_token, "report_summary", { session_id: ulid() }, { id: late })).status).toBe(200);
    expect((await inbox(owner.device_token)).map((m) => m.id)).not.toContain(late);
    expect(await sqlIn(car.car_id, "SELECT 1 FROM record_clears WHERE id = 'OLD' AND dropped = 0")).toHaveLength(1);
  });

  it("보관 기한 Alarm이 오래된 record_clears 행도 지운다", async () => {
    const { car, wipe } = await setup();
    await wipe();
    expect((await sqlIn(car.car_id, "SELECT 1 FROM record_clears")).length).toBe(1);
    await sqlIn(car.car_id, "UPDATE record_clears SET at = ?", Date.now() - 8 * DAY);
    await runDurableObjectAlarm(roomOf(car.car_id));
    expect(await sqlIn(car.car_id, "SELECT 1 FROM record_clears")).toEqual([]);
  });

  it("보관 기한 Alarm이 오래된 dropped_ids 행도 지운다", async () => {
    const { car } = await setup();
    await clear(car.car_token, [ulid()]);
    expect((await sqlIn(car.car_id, "SELECT 1 FROM record_clears WHERE dropped = 1")).length).toBe(1);
    await sqlIn(car.car_id, "UPDATE record_clears SET at = ?", Date.now() - 8 * DAY);
    await runDurableObjectAlarm(roomOf(car.car_id));
    expect(await sqlIn(car.car_id, "SELECT 1 FROM record_clears WHERE dropped = 1")).toEqual([]);
  });

  it("보관 기한 Alarm이 남긴 행도 지운다", async () => {
    const { car, id, wipe } = await setup();
    await wipe();
    await sqlIn(car.car_id, "UPDATE messages SET created_at = ?", Date.now() - 8 * DAY);
    await runDurableObjectAlarm(roomOf(car.car_id));
    expect(await sqlIn(car.car_id, "SELECT 1 FROM messages WHERE id = ?", id)).toEqual([]);
  });

  it("지우지 않는 type은 그대로 본문이 남는다", async () => {
    const { car, owner, wipe } = await setup();
    const f = await send(owner.device_token, "feeding_started", { at: "2026-11-05T08:00:00Z" });
    await wipe();
    const [row] = await sqlIn<{ body: string | null }>(car.car_id, "SELECT body FROM messages WHERE id = ?", f.json.id);
    expect(row.body).not.toBeNull();
    expect((await inbox(car.car_token)).map((m) => m.type)).toEqual(["feeding_started"]);
  });
});
