// অ্যাপের আইকন (res/drawable) থেকে ওয়েব প্যানেলের icons.js বানানো — দুই জায়গায় একই আইকন থাকে।
// চালানো: node scripts/build-admin-icons.js
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const DRAW = path.join(ROOT, 'app/src/main/res/drawable');
const NAMES = {
  home: 'ic_home_filled', verified: 'ic_verified', store: 'ic_storefront', ambulance: 'ic_ambulance',
  hospital: 'ic_local_hospital', flag: 'ic_flag', help: 'ic_help', calendar: 'ic_calendar_month',
  person: 'ic_person_filled', history: 'ic_history', admin: 'ic_admin_panel_settings', logout: 'ic_logout',
  refresh: 'ic_refresh', search: 'ic_search', blood: 'ic_bloodtype', medication: 'ic_medication',
  homeHealth: 'ic_home_health', event: 'ic_event_filled', mail: 'ic_mail', check: 'ic_check_circle',
  warning: 'ic_warning', close: 'ic_close', stethoscope: 'ic_stethoscope', forum: 'ic_forum_filled', call: 'ic_call', work: 'ic_nav_work_fill',
};
const out = {
  // অ্যাপে নেই, Material Symbols থেকে (Apache 2.0)
  chart: 'M640,800v-280h160v280H640Zm-240,0v-640h160v640H400Zm-240,0v-440h160v440H160Z',
  menu: 'M120,720v-80h720v80H120Zm0-200v-80h720v80H120Zm0-200v-80h720v80H120Z',
};
for (const [key, file] of Object.entries(NAMES)) {
  const xml = fs.readFileSync(path.join(DRAW, `${file}.xml`), 'utf8');
  if (!/viewportWidth="960"/.test(xml)) throw new Error(`${file}: viewport 960 নয়`);
  out[key] = [...xml.matchAll(/android:pathData="([^"]+)"/g)].map((m) => m[1]).join(' ');
}
const js = `// স্বয়ংক্রিয়ভাবে বানানো: scripts/build-admin-icons.js (হাতে বদলাবেন না)\nwindow.LF_ICONS = ${JSON.stringify(out, null, 1)};\n`;
fs.writeFileSync(path.join(ROOT, 'server/public/admin/icons.js'), js);
console.log('icons:', Object.keys(out).length, 'bytes:', js.length);
