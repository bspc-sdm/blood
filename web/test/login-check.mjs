import { chromium } from 'playwright-core';
import { spawn } from 'node:child_process';
const srv = spawn(process.execPath, ['test/serve.mjs', '4999'], { stdio: ['ignore', 'pipe', 'inherit'] });
await new Promise((r) => srv.stdout.once('data', r));
const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
for (const vp of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  const p = await b.newPage({ viewport: vp });
  await p.goto('http://localhost:4999/');
  await p.getByRole('button', { name: 'লগইন' }).first().click();
  await p.keyboard.type('test@example.com', { delay: 20 });
  const email = await p.locator('input[type=email]').inputValue();
  await p.getByRole('button', { name: 'কোড পাঠান' }).click();
  await p.waitForTimeout(500);
  await p.keyboard.type('123456', { delay: 40 });
  const code = await p.locator('input[autocomplete=one-time-code]').inputValue().catch((e) => 'ERR ' + e.message);
  console.log(vp.width, 'email=', email, 'code=', code);
  await p.screenshot({ path: `/tmp/login-${vp.width}.png` });
}
await b.close(); srv.kill();
