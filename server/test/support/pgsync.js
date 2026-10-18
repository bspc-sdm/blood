// টেস্টের জন্য: PostgreSQL-এ সাথে সাথে (synchronous) প্রশ্ন — যাতে পুরোনো টেস্টের F.store.get()/set()/keys()
// (নকল ডাটাবেসের Map) কোনো বদল ছাড়াই আসল PostgreSQL-এর ওপর চলে।
const path = require('path');
const { Worker, MessageChannel, receiveMessageOnPort } = require('worker_threads');

class SyncPg {
  constructor({ connectionString, options = '' }) {
    this.flag = new SharedArrayBuffer(4);
    this.signal = new Int32Array(this.flag);
    const { port1, port2 } = new MessageChannel();
    this.port = port1;
    this.worker = new Worker(path.join(__dirname, 'pgsync-worker.js'), {
      workerData: { port: port2, flag: this.flag, connectionString, options },
      transferList: [port2],
    });
    this.worker.unref();
    this.port.unref();
  }

  query(sql, params = []) {
    Atomics.store(this.signal, 0, 0);
    this.port.postMessage({ sql, params });
    const r = Atomics.wait(this.signal, 0, 0, 60000);
    if (r === 'timed-out') throw new Error(`SyncPg timed out: ${sql.slice(0, 80)}`);
    const msg = receiveMessageOnPort(this.port);
    if (!msg) throw new Error('SyncPg: no reply');
    if (msg.message.error) throw Object.assign(new Error(msg.message.error), { code: msg.message.code });
    return msg.message;
  }

  close() { this.worker.terminate(); }
}

module.exports = { SyncPg };
