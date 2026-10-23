// SQLite (ডেস্কটপে tauri-plugin-sql; পরীক্ষায় node:sqlite) — শুধু এই দুটো ডাক, প্রশ্নচিহ্ন (?) দিয়ে মান।
// plugin-sql প্রতিটা ডাক আলাদা সংযোগে চালাতে পারে, তাই BEGIN/COMMIT নয়: প্রতিটা লেখা নিজেই সম্পূর্ণ
// (কাজের খাতা `ops`-এর একটা সারিই "সত্য"; বাকি টেবিল সেটা থেকে আবার বানানো যায় — engine.ts-এর repair())।

export interface Db {
  execute(sql: string, params?: unknown[]): Promise<unknown>;
  select<T = Record<string, unknown>>(sql: string, params?: unknown[]): Promise<T[]>;
  close?(): Promise<unknown>;
}

const SCHEMA_VERSION = 1;

const V1 = [
  `CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)`,
  // সার্ভারের শেষ জানা অবস্থা (bootstrap/delta) — stock এখানে সার্ভারের; সিঙ্ক না হওয়া বদল ops.effects-এ
  `CREATE TABLE IF NOT EXISTS items (id TEXT PRIMARY KEY, name TEXT NOT NULL, code TEXT, search TEXT NOT NULL, stock REAL NOT NULL, json TEXT NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS items_code ON items(code)`,
  `CREATE TABLE IF NOT EXISTS lab_prices (id TEXT PRIMARY KEY, name TEXT NOT NULL, json TEXT NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS appts (id TEXT PRIMARY KEY, date TEXT NOT NULL, json TEXT NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS appts_date ON appts(date)`,
  // কাজের খাতা: seq = ক্রম; state pending → done | rejected
  `CREATE TABLE IF NOT EXISTS ops (
     opId TEXT PRIMARY KEY, seq INTEGER NOT NULL UNIQUE, type TEXT NOT NULL, at INTEGER NOT NULL,
     data TEXT NOT NULL, effects TEXT NOT NULL, localId TEXT NOT NULL, no TEXT NOT NULL DEFAULT '',
     invoiceKey TEXT, state TEXT NOT NULL DEFAULT 'pending', tries INTEGER NOT NULL DEFAULT 0,
     result TEXT, doneAt INTEGER)`,
  `CREATE INDEX IF NOT EXISTS ops_state ON ops(state, seq)`,
  // রসিদের এখনকার রূপ (key = localId, বা সার্ভারের রসিদ হলে 'srv:'+id)
  `CREATE TABLE IF NOT EXISTS invoices (key TEXT PRIMARY KEY, id TEXT, no TEXT NOT NULL, issuedAt INTEGER NOT NULL, json TEXT NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS invoices_no ON invoices(no)`,
];

export async function migrate(db: Db): Promise<void> {
  await db.execute(`CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)`);
  const r = await db.select<{ v: string }>(`SELECT v FROM meta WHERE k = 'schema'`);
  const have = r.length ? Number(r[0].v) : 0;
  if (have < 1) for (const s of V1) await db.execute(s);
  if (have !== SCHEMA_VERSION) await setMeta(db, 'schema', String(SCHEMA_VERSION));
}

export async function getMeta(db: Db, k: string): Promise<string | null> {
  const r = await db.select<{ v: string }>(`SELECT v FROM meta WHERE k = ?`, [k]);
  return r.length ? r[0].v : null;
}

export async function setMeta(db: Db, k: string, v: string): Promise<void> {
  await db.execute(`INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v`, [k, v]);
}

/** খোঁজার জন্য ছোট হাতের লেখা (নাম, কোড, জেনেরিক) */
export const searchText = (...parts: (string | undefined | null)[]) =>
  parts.filter(Boolean).join(' ').toLowerCase().replace(/\s+/g, ' ').trim();
