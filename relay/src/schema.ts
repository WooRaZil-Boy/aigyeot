// 차 방(CarRoom)의 SQLite 표 정의와 행 타입.
import { RATE_LIMITS_DDL } from "./util";

export const DDL = `
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
CREATE TABLE IF NOT EXISTS record_clears (
  id TEXT PRIMARY KEY,
  at INTEGER NOT NULL,
  dropped INTEGER NOT NULL DEFAULT 0 -- 1: 지우기 때 차가 뺀 메시지 id의 버림 표시
);
${RATE_LIMITS_DDL}`;

export type DeviceRole = "car" | "owner" | "guardian";
export type Device = {
  device_id: string;
  role: DeviceRole;
  platform: string;
  token_hash: ArrayBuffer;
  push_token: string | null;
  handoff_alerts: number;
  paired_at: number;
  last_seen_at: number | null;
};
export type Room = { car_id: string; next_seq: number; created_at: number; last_activity_at: number };
export type MessageRow = {
  id: string;
  seq: number;
  type: string;
  sender_device: string | null;
  sent_ts: string;
  body: string;
};

/** 칸이 다른 옛 record_clears 표(칸 id·at, before_at, part·parts)를 지금 꼴로 고친다. CREATE IF NOT EXISTS는 칸을 더하지 않는다. */
export function migrateRecordClears(sql: SqlStorage) {
  const cols = sql.exec<{ name: string }>("PRAGMA table_info(record_clears)").toArray().map((c) => c.name);
  if (!cols.includes("dropped")) sql.exec("ALTER TABLE record_clears ADD COLUMN dropped INTEGER NOT NULL DEFAULT 0");
  if (cols.includes("parts")) sql.exec("ALTER TABLE record_clears DROP COLUMN parts");
  // before_at은 NOT NULL이라 남아 있으면 새 INSERT가 실패한다.
  if (cols.includes("before_at")) sql.exec("ALTER TABLE record_clears DROP COLUMN before_at");
}
