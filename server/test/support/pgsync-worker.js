// pgsync.js-এর সাহায্যকারী thread: মূল thread আটকে থাকে (Atomics.wait), এখানে PostgreSQL-এ প্রশ্ন চলে,
// উত্তর MessagePort-এ রেখে মূল thread-কে জাগানো হয়। শুধু টেস্টের জন্য।
const { workerData } = require('worker_threads');
const { Client } = require('pg');

const { port, flag, connectionString, options } = workerData;
const signal = new Int32Array(flag);
const client = new Client({ connectionString, options });
const ready = client.connect();

port.on('message', async ({ sql, params }) => {
  let reply;
  try {
    await ready;
    const r = await client.query(sql, params);
    reply = { rows: r.rows, rowCount: r.rowCount };
  } catch (e) {
    reply = { error: e.message, code: e.code };
  }
  port.postMessage(reply);
  Atomics.store(signal, 0, 1);
  Atomics.notify(signal, 0);
});
