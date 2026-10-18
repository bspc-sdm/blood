// TEST_DB=pg: নকল Firestore-এর বদলে আসল PostgreSQL (lib/pgstore.js)। প্রতিটা টেস্ট ফাইল (আলাদা প্রসেস)
// নিজের নতুন schema পায়, শেষে মুছে যায়। auth, push ইত্যাদির নকল আগের মতোই থাকে।
// ডাটাবেসের ঠিকানা: শুধু TEST_DATABASE_URL (DATABASE_URL নয় — ভুল করে আসল ডাটাবেসে টেস্ট যেন না চলে)।
const fs = require('fs');
const path = require('path');
const { SyncPg } = require('./pgsync');
const PG = require('../../lib/pgstore');

const { encode, decode, docParts } = PG._internal;
const SCHEMA_SQL = fs.readFileSync(path.join(__dirname, '../../scripts/pg-schema.sql'), 'utf8');

function testDbUrl() {
  const url = process.env.TEST_DATABASE_URL;
  if (!url) throw new Error('TEST_DB=pg needs TEST_DATABASE_URL (e.g. postgres://user:pass@localhost:5432/lifolo_test)');
  return url;
}

/** নতুন schema, তাতে টেবিল আর ফাংশন; ফেরত { db, sync, schema, drop } */
function freshDb({ poolMax = 5 } = {}) {
  const connectionString = testDbUrl();
  const now = Math.floor(Date.now() / 1000);
  const schema = `t_${now}_${process.pid}_${Math.random().toString(36).slice(2, 7)}`;
  const admin = new SyncPg({ connectionString });
  // আগের ভেঙে যাওয়া টেস্টের ১ ঘণ্টার পুরোনো schema পরিষ্কার
  const old = admin.query("SELECT nspname FROM pg_namespace WHERE nspname ~ '^t_[0-9]+_[0-9]+_[a-z0-9]+$'").rows
    .map((r) => r.nspname).filter((n) => Number(n.split('_')[1]) < now - 3600);
  for (const n of old) {
    try { admin.query(`DROP SCHEMA IF EXISTS "${n}" CASCADE`); } catch { /* অন্য প্রসেস মুছছে */ }
  }
  admin.query(`CREATE SCHEMA "${schema}"`);
  admin.close();
  const sync = new SyncPg({ connectionString, options: `-c search_path=${schema}` });
  sync.query(SCHEMA_SQL);
  const db = new PG.PgFirestore({ connectionString, schema, autoSchema: false, poolMax });
  let dropped = false;
  const drop = () => {
    if (dropped) return;
    dropped = true;
    try { sync.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`); } catch (e) { console.error('drop schema failed', e.message); }
    sync.close();
  };
  return { db, sync, schema, drop };
}

/** নকল ডাটাবেসের Map-এর মতো (get/set/has/delete/keys/entries...), কিন্তু PostgreSQL-এর docs টেবিলে */
class PgSyncMap {
  constructor(sync, db) { this.sync = sync; this.db = db; }
  get(k) {
    const r = this.sync.query('SELECT data FROM docs WHERE path = $1', [k]).rows[0];
    return r ? decode(r.data, this.db) : undefined;
  }
  set(k, v) {
    const d = docParts(k);
    this.sync.query(
      `INSERT INTO docs (path, coll, grp, id, data) VALUES ($1, $2, $3, $4, $5::jsonb)
       ON CONFLICT (path) DO UPDATE SET data = EXCLUDED.data, updated_at = clock_timestamp()`,
      [k, d.coll, d.grp, d.id, JSON.stringify(encode(v))],
    );
    return this;
  }
  has(k) { return this.sync.query('SELECT 1 FROM docs WHERE path = $1', [k]).rows.length > 0; }
  delete(k) { return this.sync.query('DELETE FROM docs WHERE path = $1', [k]).rowCount > 0; }
  clear() { this.sync.query('DELETE FROM docs'); }
  get size() { return Number(this.sync.query('SELECT count(*)::int AS n FROM docs').rows[0].n); }
  // Map-এর মতো ঢোকানোর ক্রমে
  _rows(withData) {
    return this.sync.query(`SELECT path${withData ? ', data' : ''} FROM docs ORDER BY created_at, path`).rows;
  }
  keys() { return this._rows(false).map((r) => r.path)[Symbol.iterator](); }
  values() { return this._rows(true).map((r) => decode(r.data, this.db))[Symbol.iterator](); }
  entries() { return this._rows(true).map((r) => [r.path, decode(r.data, this.db)])[Symbol.iterator](); }
  forEach(fn, thisArg) { for (const [k, v] of this.entries()) fn.call(thisArg, v, k, this); }
  [Symbol.iterator]() { return this.entries(); }
}

/** fakefb-এর exports-এ ডাটাবেস বদলে দেওয়া (auth, users, pushes আগের মতো) */
function install(F) {
  const { db, sync, drop } = freshDb();
  F.store = new PgSyncMap(sync, db);
  F.firebase.db = db;
  F.firebase.FieldValue = PG.FieldValue;
  F.firebase.admin.firestore = { FieldValue: PG.FieldValue, FieldPath: PG.FieldPath, Timestamp: PG.Timestamp };
  F.pg = { db, sync };
  process.on('exit', drop);
  process.on('beforeExit', () => { drop(); db.close().catch(() => {}); });
  return F;
}

module.exports = { install, freshDb, PgSyncMap, testDbUrl };
