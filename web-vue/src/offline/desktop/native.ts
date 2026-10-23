// Rust অংশের ডাক (desktop/src-tauri/src) আর Tauri প্লাগিন — শুধু ডেস্কটপ বিল্ডে লোড হয়।
import { invoke } from '@tauri-apps/api/core';
import Database from '@tauri-apps/plugin-sql';
import type { Db } from '../engine/db';
import { fromB64, makeCipher, randomKey, toB64, type Cipher } from '../engine/crypt';

export const inTauri = () => typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

export interface AppInfo {
  version: string;
  hidden_start: boolean;
}
export const appInfo = () => invoke<AppInfo>('app_info');
export const deviceName = () => invoke<string>('device_name');
export const quitApp = () => invoke<void>('quit_app');

// ---------------------------------------------------------------- DPAPI-র গোপন জিনিস (vault.rs)
export const secrets = {
  get: (name: string) => invoke<string | null>('secret_get', { name }),
  set: (name: string, value: string) => invoke<void>('secret_set', { name, value }),
  del: (name: string) => invoke<void>('secret_del', { name }),
};
export const secretsWipe = () => invoke<void>('secrets_wipe');
export const dropOfflineDb = (name: string) => invoke<void>('offline_db_drop', { name });

/** ডেটাবেসের চাবি: প্রথমবার এলোমেলো ৩২ বাইট, DPAPI-তে রাখা */
let cipherP: Promise<Cipher> | null = null;
export function dbCipher(): Promise<Cipher> {
  cipherP ??= (async () => {
    let k = await secrets.get('dbkey');
    if (!k) {
      k = toB64(randomKey());
      await secrets.set('dbkey', k);
    }
    return makeCipher(fromB64(k));
  })().catch((e) => {
    cipherP = null;
    throw e;
  });
  return cipherP;
}
export function forgetCipher() {
  cipherP = null;
}

// ---------------------------------------------------------------- SQLite (tauri-plugin-sql)
const open = new Map<string, Database>();

export async function openDb(name: string): Promise<Db> {
  let db = open.get(name);
  if (!db) {
    db = await Database.load(`sqlite:${name}.db`);
    open.set(name, db);
    await db.execute('PRAGMA journal_mode = WAL').catch(() => {});
  }
  const d = db;
  return {
    execute: (sql, params) => d.execute(sql, params ?? []),
    select: <T>(sql: string, params?: unknown[]) => d.select<T[]>(sql, params ?? []),
    close: async () => {
      open.delete(name);
      await d.close().catch(() => false);
    },
  };
}

export async function dropDb(name: string): Promise<void> {
  const d = open.get(name);
  open.delete(name);
  await d?.close().catch(() => false);
  await dropOfflineDb(name);
}

export async function closeAllDbs(): Promise<void> {
  for (const [n, d] of [...open]) {
    open.delete(n);
    await d.close().catch(() => false);
  }
}

// ---------------------------------------------------------------- প্রিন্টার (printer.rs)
export interface PrinterInfo {
  name: string;
  default: boolean;
}
export const listPrinters = () => invoke<PrinterInfo[]>('printers_list');
export const printRaw = (printer: string, bytes: Uint8Array) => invoke<void>('print_raw', bytes, { headers: { 'x-printer': encodeURIComponent(printer) } });
