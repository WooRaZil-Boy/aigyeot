// 안내 방(Directory, 전역 하나, 계약 G8·4.3): 연결·초대 코드 → 차 id 색인, IP·전역 횟수 제한.
import { DurableObject } from "cloudflare:workers";
import { HOUR, HttpError, MIN, RATE_LIMITS_DDL, randomCrockford, rpc, sixDigits, take } from "./util";

const DDL = `
CREATE TABLE IF NOT EXISTS codes (
  code TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('pair','invite')),
  car_id TEXT NOT NULL,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS codes_car ON codes(car_id, kind);
CREATE INDEX IF NOT EXISTS codes_expires ON codes(expires_at);
${RATE_LIMITS_DDL}`;

const PAIR_TTL = 5 * MIN; // 명세서 14장
const INVITE_TTL = 24 * HOUR;
const LIVE_INVITES = 3; // G6

export type CodeKind = "pair" | "invite";

export class Directory extends DurableObject<Env> {
  private sql = this.ctx.storage.sql;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql.exec(DDL);
    ctx.blockConcurrencyWhile(async () => {
      if ((await ctx.storage.getAlarm()) === null) await ctx.storage.setAlarm(Date.now() + HOUR);
    });
  }

  private exists(code: string, now: number) {
    return this.sql.exec("SELECT 1 FROM codes WHERE code = ? AND expires_at > ?", code, now).toArray().length > 0;
  }

  private insert(code: string, kind: CodeKind, carId: string, createdBy: string, now: number, ttl: number) {
    this.sql.exec("DELETE FROM codes WHERE code = ?", code); // 만료된 같은 코드
    this.sql.exec("INSERT INTO codes VALUES (?, ?, ?, ?, ?, ?)", code, kind, carId, createdBy, now, now + ttl);
    return { code, expiresAt: now + ttl };
  }

  /** 새 연결 코드. 그 차의 이전 연결 코드는 지운다(1.4). */
  createPairCode(carId: string, createdBy: string) {
    const now = Date.now();
    this.sql.exec("DELETE FROM codes WHERE car_id = ? AND kind = 'pair'", carId);
    let code: string;
    do code = sixDigits();
    while (this.exists(code, now));
    return this.insert(code, "pair", carId, createdBy, now, PAIR_TTL);
  }

  deletePairCodes(carId: string) {
    this.sql.exec("DELETE FROM codes WHERE car_id = ? AND kind = 'pair'", carId);
  }

  /** 초대 코드(10자 Crockford, 24시간, G9). 차당 살아 있는 초대 3개까지. */
  createInvite(carId: string, createdBy: string) {
    return rpc(() => {
      const now = Date.now();
      const live = this.sql
        .exec<{ n: number }>(
          "SELECT count(*) AS n FROM codes WHERE car_id = ? AND kind = 'invite' AND expires_at > ?",
          carId,
          now,
        )
        .one().n;
      if (live >= LIVE_INVITES) throw new HttpError(409, "invite_limit");
      let code: string;
      do code = randomCrockford(10);
      while (this.exists(code, now));
      return this.insert(code, "invite", carId, createdBy, now, INVITE_TTL);
    });
  }

  /** 끊긴 `owner`가 만든 쓰지 않은 초대 폐기(G38). */
  revokeInvites(createdBy: string) {
    this.sql.exec("DELETE FROM codes WHERE kind = 'invite' AND created_by = ?", createdBy);
  }

  /**
   * 코드 쓰기(한 번만). 틀리면 IP·설치 id·전역 틀린 시도를 센다(6.2).
   * 틀림·만료·이미 씀은 모두 404 `code_invalid`.
   */
  consumeCode(code: string, ip: string, installId: string | null) {
    return rpc(() => {
      const now = Date.now();
      const keys: [string, number, number][] = [
        [`pairfail:ip:${ip}`, 10, 10 * MIN],
        ["pairfail:all", 20, 5 * MIN],
      ];
      if (installId) keys.push([`pairfail:inst:${installId}`, 5, 10 * MIN]);
      for (const [k, limit, win] of keys) take(this.sql, k, limit, win, now, false);
      const row = this.sql
        .exec<{ kind: CodeKind; car_id: string; created_by: string }>(
          "SELECT kind, car_id, created_by FROM codes WHERE code = ? AND expires_at > ?",
          code,
          now,
        )
        .toArray()[0];
      if (!row) {
        for (const [k, , win] of keys) take(this.sql, k, Infinity, win, now);
        throw new HttpError(404, "code_invalid");
      }
      this.sql.exec("DELETE FROM codes WHERE code = ?", code);
      return { kind: row.kind, carId: row.car_id, createdBy: row.created_by };
    });
  }

  /** 전역 횟수 제한(`reg:ip:<ip>`, `llm:all:day`). */
  rate(key: string, limit: number, windowMs: number) {
    return rpc(() => take(this.sql, key, limit, windowMs));
  }

  async alarm() {
    const now = Date.now();
    this.sql.exec("DELETE FROM codes WHERE expires_at <= ?", now);
    this.sql.exec("DELETE FROM rate_limits WHERE expires_at <= ?", now);
    await this.ctx.storage.setAlarm(now + HOUR);
  }
}
