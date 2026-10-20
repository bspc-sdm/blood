#!/usr/bin/env python3
"""
Lifolo test plan — inventory, coverage and authorization tables, generated from the code.

Run from the repo root (no network, no build):   python3 docs/test-plan/tools/build_inventory.py
Writes: docs/test-plan/01-inventory.md, 02-coverage-matrix.md, 03-authorization-matrix.md, tools/data/*.json

Sources it reads (nothing is typed in by hand except the role/tap-target maps marked HAND below):
  backend/src/main/kotlin   — every ActionModule map ("name" to ActionHandler), handler bodies, HTTP mappings,
                              @Recurring jobs, DailyTask beans, push call sites ("type" to …), ActionPolicy sets
  backend/docs/API-INVENTORY.md — the auth text per action (cross-checked against the Kotlin handler body)
  backend/src/test          — action names mentioned by the Spring tests (unit + IT)
  app/src/main              — Screen sealed classes, @Composable screens/dialogs/sheets/tabs, inline dialogs,
                              permissions, intent filters, notification channels, action names the app sends
  app/src/test              — composables rendered by the Robolectric screenshot tests
  web-vue/src, web-vue/admin — routes, .vue pages/dialogs, admin pages, action names the web sends
  e2e/android/flows         — Maestro flows (Bangla texts they tap/assert)
  e2e/web/journeys          — web journeys (Bangla texts)
  tools/data/web-journeys-actions.json — /api/community actions observed while all 9 web journeys ran
                              (captured with e2e/web/run.mjs on the local stack; re-capture when journeys change)
"""
import json
import os
import re
import sys
from collections import Counter, defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
OUT = os.path.join(ROOT, 'docs', 'test-plan')
DATA = os.path.join(OUT, 'tools', 'data')
BK = os.path.join(ROOT, 'backend/src/main/kotlin/com/lifolo')
BT = os.path.join(ROOT, 'backend/src/test')
APP = os.path.join(ROOT, 'app/src/main/java/com/bspc/blood')
APPT = os.path.join(ROOT, 'app/src/test')
VUE = os.path.join(ROOT, 'web-vue/src')
ADMIN = os.path.join(ROOT, 'web-vue/admin/src')
FLOWS = os.path.join(ROOT, 'e2e/android/flows')
JOURNEYS = os.path.join(ROOT, 'e2e/web/journeys')


def read(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


def walk(d, exts):
    out = {}
    for dp, _, fs in os.walk(d):
        for f in fs:
            if f.endswith(exts):
                p = os.path.join(dp, f)
                out[p] = read(p)
    return out


def rel(p):
    return os.path.relpath(p, ROOT)


def block(src, i):
    """text of the {...} block that starts at the first '{' at/after i"""
    j = src.find('{', i)
    if j < 0:
        return ''
    d = 0
    for k in range(j, len(src)):
        c = src[k]
        if c == '{':
            d += 1
        elif c == '}':
            d -= 1
            if d == 0:
                return src[j:k + 1]
    return src[j:]


def fun_body(src, i):
    """body of the Kotlin fun starting at i ({...} or = expression)"""
    k = src.find('(', i)
    d = 0
    while k < len(src):
        if src[k] == '(':
            d += 1
        elif src[k] == ')':
            d -= 1
            if d == 0:
                break
        k += 1
    m = re.compile(r'\s*(:\s*[^={\n]+)?\s*([={])').match(src, k + 1)
    if not m:
        return src[i:i + 400]
    s = m.end() - 1
    if src[s] == '{':
        return src[i:s] + block(src, s)
    nxt = re.compile(r'\n\s*(private |internal |override )?fun ').search(src, s)
    e = src.find('\n\n', s)
    end = min(x for x in [e if e > 0 else len(src), nxt.start() if nxt else len(src)])
    return src[i:end]


def norm_bn(s):
    # Maestro/regex texts: (?:য়|য়) → য়, drop regex syntax; also the two ways to write য়
    s = re.sub(r'\(\?:([^|)]*)\|[^)]*\)', r'\1', s)
    s = s.replace('\u09af\u09bc', '\u09df')
    return s


def md_escape(s):
    return str(s).replace('|', '\\|').replace('\n', ' ')


# ---------------------------------------------------------------- backend
bk = walk(BK, ('.kt',))
bt = walk(BT, ('.kt',))
ALL_TEST = '\n'.join(bt.values())

actions = []
for p, src in bk.items():
    if 'override fun actions' not in src:
        continue
    for m in re.finditer(r'"([a-z_0-9]+)" to ActionHandler\s*(\(::(\w+)\)|\{)', src):
        name = m.group(1)
        fname = None
        expr = ''
        if m.group(3):
            fname = m.group(3)
        else:
            b = block(src, m.end() - 1)
            expr = b[1:-1].strip()
            if not ('\n' in expr and len(expr) > 120):
                f = re.search(r'(\w+)\(it\b', expr) or re.search(r'(\w+)\(ctx\b', expr)
                fname = f.group(1) if f else None
        body, loc = expr, rel(p)
        if fname:
            cls = None
            recv = re.search(r'(\w+)\.' + re.escape(fname) + r'\(', expr)
            if recv:
                t = re.search(r'val\s+' + recv.group(1) + r'\s*:\s*(\w+)', src)
                cls = t.group(1) if t else None
            hits = []
            if cls:
                for fp, s2 in bk.items():
                    if re.search(r'\bclass\s+' + cls + r'\b', s2):
                        hits += [(fp, mm.start()) for mm in re.finditer(r'\bfun\s+' + re.escape(fname) + r'\s*\(', s2)]
            if not hits:
                hits = [(p, mm.start()) for mm in re.finditer(r'\bfun\s+' + re.escape(fname) + r'\s*\(', src)]
            if not hits:
                for fp, s2 in sorted(bk.items(), key=lambda x: (0 if os.path.dirname(x[0]) == os.path.dirname(p) else 1, x[0])):
                    hits += [(fp, mm.start()) for mm in re.finditer(r'\bfun\s+' + re.escape(fname) + r'\s*\(', s2)]
            if hits:
                fp, pos = hits[0]
                body = fun_body(bk[fp], pos)
                loc = rel(fp) + ':' + str(bk[fp][:pos].count('\n') + 1)
        pkg = os.path.relpath(p, BK).split(os.sep)[0]
        actions.append(dict(action=name, pkg=pkg, module=rel(p), loc=loc, body=body))
actions.sort(key=lambda a: (a['pkg'], a['action']))
ACTION_NAMES = {a['action'] for a in actions}

# ActionPolicy sets straight from the code
pol_src = read(os.path.join(BK, 'common/action/Actions.kt'))


def policy_set(name):
    m = re.search(r'val ' + name + r': Set<String> = setOf\((.*?)\)\n', pol_src, re.S)
    return set(re.findall(r'"([a-z_0-9]+)"', m.group(1))) if m else set()


PUBLIC = policy_set('PUBLIC_ACTIONS')
CARE = policy_set('CARE_ACTIONS')
WEB_READS = policy_set('WEB_PANEL_READS')

# API-INVENTORY auth text
inv = read(os.path.join(ROOT, 'backend/docs/API-INVENTORY.md'))
inv_auth, inv_desc = {}, {}
for m in re.finditer(r'- \[[ x]\] \*\*`([a-z_0-9]+)`\*\* — (.*?)\n\s+- src: .*?auth: ([^·\n]+)', inv):
    inv_desc[m.group(1)] = m.group(2).strip()
    inv_auth[m.group(1)] = m.group(3).strip()

# markers from the Kotlin body
MARKERS = [
    ('admin', r'requireAdmin\(|admins\.isAdmin\('),
    ('owner', r'requireOwner\(|\bowner\(ctx\)|admins\.isOwner\('),
    ('doctor', r'requireDoctor\(|approvedBadge\(\w+\) != "doctor"'),
    ('doctor|assistant', r'actingFor\(|doctorOrAssistant\(|controller\(ctx'),
    ('badge', r'approvedBadge\('),
    ('pharmacy', r'myPharmacy\(|myShop\(|shops\.get\(uid\)|shops\.get\(ctx\.user\.id\)|pharmacy_missed WHERE pharmacy_uid|find_off = \? WHERE uid'),
    ('lab', r'myLab\(|homeOrderOfLab\(|labId != ctx\.user\.id'),
    ('own-row', r'!= ctx\.user\.(id|uid)|!= uid\b|\.uid != uid|ownerUid != |requesterUid != |patientUid != |doctorUid != |owner != |loadMine\(|myHomeRequest\(|ownRequestId\(|ownRx\(|WHERE (uid|owner_uid|patient_uid|pharmacy_uid|lab_uid) = \?|isProvider|isPatient|isRequester|isDonor|allowed = |\(ctx\.user\.id\)|\(ctx\.user\.id,|myEmail\(ctx\.user\)|shops\.get\(uid'),
    ('same-world', r'sameWorld\('),
    ('quota', r'quotas\.use\('),
]


def markers(body):
    return [k for k, rx in MARKERS if re.search(rx, body)]


for a in actions:
    a['markers'] = markers(a['body'])
    a['auth_doc'] = inv_auth.get(a['action'], '(not in API-INVENTORY)')
    a['desc'] = inv_desc.get(a['action'], '')

# HTTP endpoints
endpoints = []
for p, src in bk.items():
    for m in re.finditer(r'@(Get|Post|Request)Mapping\("([^"]+)"', src):
        endpoints.append((m.group(2), m.group(1), rel(p) + ':' + str(src[:m.start()].count('\n') + 1)))
endpoints.sort()

# jobs
jobs = []
for p, src in bk.items():
    for m in re.finditer(r'@Recurring\(id = "([^"]+)", cron = "([^"]+)"', src):
        jobs.append(('JobRunr @Recurring', m.group(1), m.group(2) + ' UTC', rel(p)))
    for m in re.finditer(r'class (\w+)\([^)]*\)\s*:\s*DailyTask', src):
        o = re.search(r'override val order[^=]*=\s*(\d+)', src[m.start():m.start() + 3000])
        jobs.append(('DailyTask (03:00 UTC)', m.group(1), 'order ' + (o.group(1) if o else '?'), rel(p)))
daily_reminders = [p for p, s in bk.items() if p.endswith('Reminders.kt') and 'DailyTask' in s]
for p in daily_reminders:
    m = re.search(r'class (\w+)\(', bk[p])
    if m and not any(j[1] == m.group(1) for j in jobs):
        jobs.append(('DailyTask (03:00 UTC)', m.group(1), 'order ?', rel(p)))

# pushes
pushes = []
for p, src in bk.items():
    if '/demo/' in p or 'DemoSeed' in p or p.endswith('PushService.kt'):
        continue
    lines = src.split('\n')
    for i, l in enumerate(lines):
        if re.search(r'PushItem\(|pushPatient\(|pushUsers\(|PushText\(|push\.(one|patient|send)\(|pushes\.send|userPush\.send\(', l) and 'fun ' not in l and not l.strip().startswith('//'):
            win = '\n'.join(lines[i:i + 14])
            ty = re.search(r'"type" to "([a-z_]+)"', win)
            bn = re.findall(r'"([^"]*[\u0980-\u09FF][^"]*)"', win)
            pushes.append(dict(type=ty.group(1) if ty else '(helper default)', file=rel(p), line=i + 1, title=bn[0][:80] if bn else ''))
# dedupe same title in same file
seen, P2 = set(), []
for x in pushes:
    k = (x['type'], x['file'], x['title'])
    if k in seen:
        continue
    seen.add(k)
    P2.append(x)
pushes = sorted(P2, key=lambda x: (x['type'], x['file'], x['line']))
PUSH_TYPES = sorted({x['type'] for x in pushes})

# HAND: where a tap on each push type lands (MainActivity.openFromData)
TAP = {
    'request': 'RequestDetail (donor view of the request) — requestId',
    'response': 'MyRequestDetail (requester view) — requestId',
    'request_removed': 'Home (no requestId)',
    'eligible': 'Home',
    'correction': 'Home',
    'emergency': 'Home + emergency banner refreshed',
    'answer': 'QuestionDetail — questionId',
    'appointment': 'role=provider → ChamberEdit (My chamber); else MyAppointments',
    'badge': 'Home (falls to else-branch)',
    'hospital': 'Home (falls to else-branch)',
    'services': 'MyBusiness(kind) when kind ∈ ambulance/pharmacy/bloodbank/lab, else My work tab or Home',
    'support': 'Help screen',
    'care': 'with asUid+rxId → CareFor(patient) → that prescription; else Care screen',
    'rx': 'that prescription (rxId) or MyPrescriptions',
    'rx_review': 'RxReviews (doctor/assistant)',
    'rx_pharmacy': 'MyBusiness(pharmacy) › orders',
    'rx_lab': 'MyBusiness(lab) › orders',
    'med_find': 'MyBusiness(pharmacy) › finds',
    'med_find_mine': 'Services tab › pharmacy › medicine finder',
    'amb_req_mine': 'AmbulanceRequest(reqId) waiting page',
    '(helper default)': 'depends on helper (RxPush → rx/care; AmbReq driver push → services/ambulance)',
}

# ---------------------------------------------------------------- Android
app = {os.path.basename(p): s for p, s in walk(APP, ('.kt',)).items()}
APP_ALL = '\n'.join(app.values())
screen_src = app['MainActivity.kt']
nav_routes = re.findall(r'data (?:object|class) (\w+)(?:\([^)]*\))? : Screen', screen_src)
SUFFIX = [('Screen', 'screen'), ('Mode', 'screen'), ('Dialog', 'dialog'), ('Sheet', 'sheet'), ('Page', 'page'),
          ('Tabs', 'tab'), ('Tab', 'tab'), ('Form', 'form'), ('Picker', 'picker'), ('Panel', 'panel'),
          ('Section', 'section'), ('Bar', 'bar'), ('Banner', 'banner')]
ui = []
for f, s in sorted(app.items()):
    for m in re.finditer(r'@Composable\s+(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|internal|public)\s+)?fun\s+(?:[A-Za-z.<>]+\.)?([A-Z]\w*)\s*\(', s):
        name = m.group(1)
        kind = next((k for suf, k in SUFFIX if name.endswith(suf)), None)
        if not kind:
            continue
        body = block(s, m.end())
        strs = re.findall(r'tr\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\)', body)
        calls = len(re.findall(r'\b' + name + r'\s*[({]', APP_ALL)) - 1
        ui.append(dict(file=f, line=s[:m.start()].count('\n') + 2, name=name, kind=kind, calls=calls,
                       bn=[b for _, b in strs], title=strs[0][1] if strs else ''))
inline = []
for f, s in sorted(app.items()):
    L = s.split('\n')
    for i, l in enumerate(L):
        m = re.search(r'\b(FormDialog|AlertDialog|AppBottomSheet|FullScreenDialog|CardDialog|DetailSheet|ModalBottomSheet|DatePickerDialog)\s*\(', l)
        if not m or re.search(r'fun\s+' + m.group(1), l):
            continue
        enc = None
        for j in range(i, -1, -1):
            mm = re.search(r'fun\s+(?:[A-Za-z.<>]+\.)?([A-Za-z]\w*)\s*\(', L[j])
            if mm:
                enc = mm.group(1)
                break
        t = re.findall(r'tr\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\)', '\n'.join(L[i:i + 8]))
        inline.append(dict(file=f, line=i + 1, wrapper=m.group(1), inside=enc, title=t[0][1] if t else ''))

manifest = read(os.path.join(ROOT, 'app/src/main/AndroidManifest.xml'))
permissions = re.findall(r'uses-permission android:name="android\.permission\.(\w+)"', manifest)
perm_prompts = []
for f, s in sorted(app.items()):
    for i, l in enumerate(s.split('\n')):
        if re.search(r'launch\((arrayOf\()?(android\.)?Manifest\.permission|RequestPermission\(\)|RequestMultiplePermissions\(\)|PickVisualMedia\(\)', l):
            perm_prompts.append((f, i + 1, l.strip()[:150]))
channels = re.findall(r'NotificationChannel\(\s*(\w+)\s*,\s*tr\("([^"]*)",\s*"([^"]*)"\)', APP_ALL)
app_actions = sorted(ACTION_NAMES & set(re.findall(r'"([a-z_0-9]+)"', APP_ALL)))

robo = '\n'.join(walk(APPT, ('.kt',)).values())
flows = {os.path.basename(p): norm_bn(s) for p, s in walk(FLOWS, ('.yaml',)).items()}
journeys = {os.path.basename(p): norm_bn(s) for p, s in walk(JOURNEYS, ('.mjs',)).items()}


def text_hits(strings, corpus):
    hits = []
    for name, txt in corpus.items():
        for s in strings:
            s2 = norm_bn(s)
            if len(s2) >= 6 and '$' not in s2 and s2 in txt:
                hits.append(name.split('.')[0])
                break
    return hits


for u in ui:
    u['robo'] = bool(re.search(r'\b' + u['name'] + r'\s*\(', robo))
    u['maestro'] = text_hits(u['bn'], flows)

# ---------------------------------------------------------------- web
vue = walk(VUE, ('.vue', '.ts'))
adm = walk(ADMIN, ('.ts',))
VUE_ALL = '\n'.join(vue.values())
ADM_ALL = '\n'.join(adm.values())
router = read(os.path.join(VUE, 'router.ts'))
top_routes = re.findall(r"path: (?:rest\()?'([^']+)'", router)
paths = sorted(set(re.sub(r'\$\{[^}]*\}', ':x', p) for p in re.findall(r"['\"`](/(?:request|my-requests|services|appointment|my-appointments|health|prescriptions|work|profile|settings|inbox|help|care|login)(?:/[^'\"`?#$]*)?)", VUE_ALL)))
vue_comp = []
for p, s in sorted(vue.items()):
    if not p.endswith('.vue'):
        continue
    n = os.path.basename(p)[:-4]
    kind = next((k for suf, k in SUFFIX if n.endswith(suf)), 'part')
    strs = re.findall(r'tr\(\s*[\'"]([^\'"]*)[\'"]\s*,\s*[\'"]([^\'"]*)[\'"]\s*\)', s)
    vue_comp.append(dict(file=rel(p), name=n, kind=kind, bn=[b for _, b in strs]))
for c in vue_comp:
    c['journeys'] = text_hits(c['bn'], journeys)
shell = adm.get(os.path.join(ADMIN, 'Shell.ts'), '')
admin_pages = re.findall(r"\{ path: '([^']*)', label: '([^']+)'", shell)
web_actions = sorted(ACTION_NAMES & set(re.findall(r"""['"]([a-z_0-9]+)['"]""", VUE_ALL)))
admin_actions = sorted(ACTION_NAMES & set(re.findall(r"""['"]([a-z_0-9]+)['"]""", ADM_ALL)))
fixtures = '\n'.join(walk(os.path.join(ROOT, 'web-vue/test'), ('.mjs', '.json', '.ts')).values()) + '\n'.join(walk(os.path.join(ROOT, 'web-vue/e2e'), ('.ts',)).values())

# observed in web journeys (dynamic)
obs_file = os.path.join(DATA, 'web-journeys-actions.json')
observed = Counter()
observed_ok = Counter()
observed_paths = Counter()
if os.path.exists(obs_file):
    for path_, act, status, n in json.load(open(obs_file))['rows']:
        if act:
            observed[act] += n
            if (status or 0) < 400:
                observed_ok[act] += n
        else:
            observed_paths[path_] += n

# ---------------------------------------------------------------- coverage per action
for a in actions:
    n = a['action']
    a['t_backend'] = sorted({os.path.basename(p)[:-3] for p, s in bt.items() if re.search(r'"' + n + r'"', s)})
    lines = [l for s in bt.values() for l in s.split('\n') if '"' + n + '"' in l]
    a['t_backend_n'] = len(lines)
    a['t_backend_neg_only'] = bool(lines) and all(re.search(r'\b4\d\d\b|\.error|fails\(|status\)', l) for l in lines)
    a['t_web'] = observed.get(n, 0)
    a['t_web_ok'] = observed_ok.get(n, 0)
    a['t_vue_mock'] = bool(re.search(r"""['"]""" + n + r"""['"]""", fixtures))
    a['clients'] = ','.join(x for x, ok in [('app', n in app_actions), ('web', n in web_actions), ('admin', n in admin_actions)] if ok) or '—'


def cov_cell(a):
    parts = []
    if a['t_backend']:
        thin = ' **neg-only**' if a['t_backend_neg_only'] else (' thin' if a['t_backend_n'] <= 2 else '')
        parts.append('BE×%d:' % a['t_backend_n'] + ','.join(a['t_backend'][:3]) + ('…' if len(a['t_backend']) > 3 else '') + thin)
    if a['t_web']:
        parts.append('WEB-J:%d' % a['t_web'] + ('' if a['t_web_ok'] else ' (only errors)'))
    if a['t_vue_mock']:
        parts.append('VUE-mock')
    return ' · '.join(parts) if parts else '**NONE**'


# ---------------------------------------------------------------- authorization model (as the code enforces it)
ROLES = ['Guest', 'User', 'Caregiver(asUid)', 'Doctor', 'Assistant', 'Nurse/pro', 'Pharmacy', 'Lab', 'Ambulance', 'BloodBank', 'Admin', 'Owner', 'AdminCookie']


def auth_row(a):
    n, doc, mk = a['action'], a['auth_doc'], set(a['markers'])
    row = {}
    login = 'Y'
    if n in PUBLIC:
        row['Guest'] = 'Y'
    else:
        row['Guest'] = 'N401'
    # web panel cookie
    row['AdminCookie'] = 'Y' if (n.startswith('admin_') or n in WEB_READS or n == 'web_logout') else 'N403'
    if 'owner' in mk and n.startswith('admin_') and 'admin' not in mk:
        base = dict.fromkeys(ROLES[1:11], 'N403')
        base['Owner'] = 'Y'
        if n.startswith('admin_') and not ('ADMIN_EMAILS' in doc or 'owner' in doc):
            pass
        row.update({k: v for k, v in base.items() if k not in row})
        if row['AdminCookie'] == 'Y':
            row['AdminCookie'] = 'owner only'
    elif n.startswith('admin_') or 'admin' in mk and n.startswith('admin'):
        for r in ROLES[1:10]:
            row[r] = 'N403'
        row['Admin'] = 'Y'
        row['Owner'] = 'Y'
    else:
        # logged-in actions
        need = None
        if 'role:approved-pharmacy' in doc or 'approved pharmacy' in doc or 'pharmacy owner' in doc or 'pharmacy' in mk and n.startswith(('books_', 'stock_', 'rx_pharmacy', 'rx_order_update', 'rx_orders_pharmacy', 'rx_missed', 'rx_dispense', 'med_find_answer', 'med_find_requests', 'pharmacy_find')):
            need = 'Pharmacy'
        elif 'role:approved-lab' in doc or 'lab owner' in doc or n in ('lab_home_confirm', 'lab_home_decline'):
            need = 'Lab'
        elif 'doctor(chamber)|assistant' in doc or 'role:doctor|assistant' in doc or 'doctor|assistant' in mk and n not in ('rx_patient_history',):
            need = 'Doctor+Assistant'
        elif 'role:doctor' in doc or 'writing doctor' in doc or 'doctor' in mk and n.startswith(('rx_', 'review_')):
            need = 'Doctor'
        elif 'role:badge' in doc and n != 'book':  # book: the *provider* must hold a badge, any user may book
            need = 'Badge'
        for r in ROLES[1:12]:
            row[r] = 'Y'
        if need == 'Pharmacy':
            for r in ROLES[1:12]:
                row[r] = 'own shop' if r == 'Pharmacy' else 'N403'
        elif need == 'Lab':
            for r in ROLES[1:12]:
                row[r] = 'own lab' if r == 'Lab' else 'N403'
        elif need == 'Doctor+Assistant':
            for r in ROLES[1:12]:
                row[r] = 'own' if r == 'Doctor' else ('for listed doctor' if r == 'Assistant' else 'N403')
        elif need == 'Doctor':
            for r in ROLES[1:12]:
                row[r] = 'own' if r == 'Doctor' else 'N403'
        elif need == 'Badge':
            for r in ROLES[1:12]:
                row[r] = 'Y' if r in ('Doctor', 'Nurse/pro') else 'N403'
        else:
            ownish = ('own-row' in mk) or '(' in doc
            for r in ROLES[1:12]:
                row[r] = 'own' if ownish else 'Y'
        row['Caregiver(asUid)'] = ('as patient' if n in CARE else 'N403')
        if n in ('profile_get', 'profile_update'):
            row['Caregiver(asUid)'] = 'N403'
        # delete_question/answer/tip: admin may delete anyone's
        if 'or admin' in doc:
            row['Admin'] = 'any'
            row['Owner'] = 'any'
    return row


def flags_for(a, row):
    """things a reviewer must look at — not proven bugs"""
    n, doc, mk = a['action'], a['auth_doc'], set(a['markers'])
    f = []
    if n.startswith('admin_') and not ({'admin', 'owner'} & mk):
        f.append('admin_* without admin check in handler')
    if ('role:approved-pharmacy' in doc and 'pharmacy' not in mk) or ('role:approved-lab' in doc and 'lab' not in mk):
        f.append('doc says business role; handler marker not found (check helper)')
    if 'role:doctor' in doc and not ({'doctor', 'doctor|assistant', 'badge'} & mk):
        f.append('doc says doctor; marker not found (check helper)')
    if '(' in doc and '(any)' not in doc and '(also' not in doc and 'own-row' not in mk and not ({'doctor', 'doctor|assistant', 'pharmacy', 'lab', 'admin', 'owner'} & mk):
        f.append('ownership in doc; no ownership marker in handler body (check helper)')
    if n in PUBLIC and re.search(r'INSERT|UPDATE|DELETE', a['body']):
        f.append('public (guest) action body contains a write')
    return f


for a in actions:
    a['auth'] = auth_row(a)
    a['flags'] = flags_for(a, a['auth'])

# ---------------------------------------------------------------- write JSON data
os.makedirs(DATA, exist_ok=True)
with open(os.path.join(DATA, 'actions.json'), 'w', encoding='utf-8') as fh:
    json.dump([{k: v for k, v in a.items() if k != 'body'} for a in actions], fh, ensure_ascii=False, indent=1)
with open(os.path.join(DATA, 'android-ui.json'), 'w', encoding='utf-8') as fh:
    json.dump({'routes': nav_routes, 'composables': [{k: v for k, v in u.items() if k != 'bn'} for u in ui], 'inline_dialogs': inline}, fh, ensure_ascii=False, indent=1)
with open(os.path.join(DATA, 'pushes.json'), 'w', encoding='utf-8') as fh:
    json.dump(pushes, fh, ensure_ascii=False, indent=1)

# ---------------------------------------------------------------- counts
C = dict(
    actions=len(actions), endpoints=len(endpoints), public=len(PUBLIC), care=len(CARE),
    nav_routes=len(nav_routes), ui=len(ui), ui_kind=Counter(u['kind'] for u in ui), inline=len(inline),
    vue_files=len(vue_comp), vue_kind=Counter(c['kind'] for c in vue_comp), vue_paths=len(paths), admin_pages=len(admin_pages),
    push_types=len(PUSH_TYPES), push_sites=len(pushes), jobs=len(jobs), permissions=len(permissions),
    a_none=sum(1 for a in actions if cov_cell(a) == '**NONE**'),
    a_be=sum(1 for a in actions if a['t_backend']), a_web=sum(1 for a in actions if a['t_web']),
    a_thin=sum(1 for a in actions if a['t_backend'] and (a['t_backend_neg_only'] or a['t_backend_n'] <= 2) and not a['t_web']),
    a_neg=sum(1 for a in actions if a['t_backend_neg_only']),
    a_no_e2e=sum(1 for a in actions if not a['t_web']),
    ui_none=sum(1 for u in ui if not u['robo'] and not u['maestro']),
    vue_none=sum(1 for c in vue_comp if not c['journeys'] and c['kind'] != 'part'),
    vue_main=sum(1 for c in vue_comp if c['kind'] != 'part'),
    flags=sum(1 for a in actions if a['flags']),
)
with open(os.path.join(DATA, 'counts.json'), 'w', encoding='utf-8') as fh:
    json.dump({k: (dict(v) if isinstance(v, Counter) else v) for k, v in C.items()}, fh, ensure_ascii=False, indent=1)

GEN = '> এই ফাইল কোড থেকে বানানো: `python3 docs/test-plan/tools/build_inventory.py` (হাতে বদলাবেন না — RC-র কোডে আবার চালান)।\n\n'

# ---------------------------------------------------------------- 01-inventory.md
o = []
o.append('# ১. ইনভেন্টরি — কোডে যা আছে তার পুরো তালিকা\n')
o.append(GEN)
o.append('## সংখ্যা এক নজরে\n')
o.append('| কী | কত | কোথা থেকে গোনা |\n|---|---|---|')
o.append(f"| Android নেভিগেশন রুট (`Screen` sealed) | {C['nav_routes']} | `app/.../MainActivity.kt` |")
o.append(f"| Android Composable স্ক্রিন/ডায়ালগ/শিট/ট্যাব… | {C['ui']} ({', '.join(f'{k} {v}' for k, v in C['ui_kind'].most_common())}) | `@Composable fun …Screen/Dialog/Sheet/Tab…` |")
o.append(f"| Android ইনলাইন ডায়ালগ/শিট (FormDialog, AlertDialog …) | {C['inline']} | কল-সাইট |")
o.append(f"| ওয়েব (Vue) রুট/পাথ | {len(top_routes)} টপ-লেভেল, {C['vue_paths']} পাথ | `web-vue/src/router.ts` + সেকশনের লিংক |")
o.append(f"| ওয়েব (Vue) কম্পোনেন্ট ফাইল | {C['vue_files']} ({', '.join(f'{k} {v}' for k, v in C['vue_kind'].most_common())}) | `web-vue/src/**/*.vue` |")
o.append(f"| অ্যাডমিন প্যানেলের পাতা | {C['admin_pages']} (+ verify, ticket, user detail) | `web-vue/admin/src/Shell.ts` |")
o.append(f"| `/api/community` action | {C['actions']} (অতিথি {C['public']}, কেয়ারগিভার {C['care']}) | `ActionModule` ম্যাপ |")
o.append(f"| অন্য HTTP endpoint | {C['endpoints']} | `@…Mapping` |")
o.append(f"| পুশ নোটিফিকেশনের ধরন (type) | {C['push_types']} (কল-সাইট {C['push_sites']}) | `\"type\" to …` |")
o.append('| ইমেইলের ধরন | 1 (লগইন কোড) | `MailSender` |')
o.append(f"| ক্রন/ব্যাকগ্রাউন্ড কাজ | {C['jobs']} (+ VPS টাইমার ও CI ক্রন — নিচে) | `@Recurring`, `DailyTask` |")
o.append(f"| Android permission | {C['permissions']} | `AndroidManifest.xml` |")
o.append('')

o.append('## ক) রোল (কে কে অ্যাপ ব্যবহার করেন)\n')
o.append('| রোল | কীভাবে হয় | কোড যেখানে চেনে | ডেমো অ্যাকাউন্ট |\n|---|---|---|---|')
ROLE_ROWS = [  # HAND (from DemoWorld.ROLES, badge types, ActionPolicy)
    ('অতিথি (guest)', 'লগইন ছাড়া', '`ActionPolicy.PUBLIC_ACTIONS` (১৫টা)', '—'),
    ('সাধারণ ব্যবহারকারী / রোগী', 'ইমেইল কোডে লগইন', 'যেকোনো Bearer token', 'ডেমো রোগী রহিম (`patient`)'),
    ('রক্তদাতা', 'প্রোফাইলে গ্রুপ, জন্মতারিখ, ওজন; ১৮–৬০ বছর, ≥৫০ কেজি, শেষ দানের ১২০ দিন পর', '`BloodRules.isEligibleDonor`', 'ডেমো ডোনার করিম (`donor`)'),
    ('কেয়ারগিভার', 'রোগী ইমেইলে আমন্ত্রণ দেন, কেয়ারগিভার রাজি হন', '`body.asUid` + `CARE_ACTIONS` (২৫টা) + সক্রিয় `care_links`', 'ডেমো কেয়ারগিভার রুমা (`caregiver`)'),
    ('ডাক্তার', 'ব্যাজ আবেদন (`doctor`) → অ্যাডমিন অনুমোদন', '`approvedBadge == doctor`', 'ডা. ডেমো হাসান (`doctor`)'),
    ('ডাক্তারের সহকারী', 'ডাক্তার সহকারীর ইমেইল যোগ করেন (যাচাই করা ইমেইল)', '`actingFor` / `doctorOrAssistant`', 'ডেমো সহকারী সুমন (`assistant`)'),
    ('নার্স / পুষ্টিবিদ / ট্রেইনার / বিশেষজ্ঞ', 'ব্যাজ আবেদন', '`PROVIDER_TYPES`, `CHAMBER_TYPES`', 'ডেমো নার্স নাসরিন (`homecare`)'),
    ('ফার্মেসির মালিক', 'ফার্মেসি যোগ → অ্যাডমিন অনুমোদন', '`myPharmacy` / `myShop` (status approved)', 'ডেমো ফার্মেসির মালিক (`pharmacy`)'),
    ('প্যাথলজির মালিক', 'প্যাথলজি যোগ → অনুমোদন', '`myLab`', 'ডেমো প্যাথলজির মালিক (`lab`)'),
    ('অ্যাম্বুলেন্স মালিক/চালক', 'অ্যাম্বুলেন্স যোগ → অনুমোদন', '`ownerUid`, `ambreq_targets`', 'ডেমো অ্যাম্বুলেন্স চালক (`ambulance`)'),
    ('ব্লাড ব্যাংক', 'ব্লাড ব্যাংক যোগ → অনুমোদন', '`owner_uid`', 'ডেমো ব্লাড ব্যাংক (`bloodbank`)'),
    ('অ্যাডমিন', 'মালিক ইমেইল যোগ করেন (`admin_add`)', '`AdminService.isAdmin` (যাচাই করা ইমেইল)', 'নেই — মালিকের নিজের অ্যাকাউন্ট'),
    ('মালিক (owner)', 'সার্ভারের `ADMIN_EMAILS`', '`AdminService.isOwner`', 'নেই'),
    ('অ্যাডমিন ওয়েব-কুকি', '`/admin` প্যানেলে ইমেইল কোড', '`__Host-lf_admin` + হেডার `x-lifolo-admin: 1`', '—'),
    ('ডেমো রোল (১০টা)', 'লগইন পাতায় "ডেমো (পরীক্ষার জন্য)" — কোড ছাড়াই', '`DemoLoginService`, uid `demo-…`, `DemoWorld.sameWorld`', 'সব — **রিলিজের আগে মুছতে হবে**'),
]
for r in ROLE_ROWS:
    o.append('| ' + ' | '.join(r) + ' |')
o.append('')

o.append('## খ) Android — নেভিগেশন রুট (`sealed interface Screen`)\n')
o.append(', '.join(f'`{r}`' for r in nav_routes) + '\n')
o.append('নিচের বার: হোম · অ্যাপয়েন্টমেন্ট · স্বাস্থ্য · সেবা · আমার কাজ (শুধু যাঁর ব্যবসা/চেম্বার আছে); প্রোফাইল উপরের ছবিতে (`Tabs.PROFILE`)।\n')
o.append('## গ) Android — Composable স্ক্রিন, ডায়ালগ, শিট, ট্যাব\n')
o.append('| # | নাম | ধরন | ফাইল:লাইন | প্রথম বাংলা লেখা (শিরোনাম/বাটন) | কোথা থেকে ডাকা হয় (বার) |\n|---|---|---|---|---|---|')
for i, u in enumerate(ui, 1):
    o.append(f"| {i} | `{u['name']}` | {u['kind']} | `{u['file']}:{u['line']}` | {md_escape(u['title'])} | {u['calls'] if u['calls'] > 0 else '**0 — অব্যবহৃত?**'} |")
o.append('\n### ইনলাইন ডায়ালগ/শিট (অন্য স্ক্রিনের ভেতরে)\n')
o.append('| # | র‍্যাপার | কোন ফাংশনের ভেতরে | ফাইল:লাইন | শিরোনাম |\n|---|---|---|---|---|')
for i, d in enumerate(inline, 1):
    o.append(f"| {i} | {d['wrapper']} | `{d['inside']}` | `{d['file']}:{d['line']}` | {md_escape(d['title'])} |")
o.append('')

o.append('## ঘ) ওয়েব (Vue) — রুট ও পাতা\n')
o.append('টপ-লেভেল রুট (`router.ts`): ' + ', '.join(f'`{r}`' for r in top_routes) + '\n')
o.append('কোডে পাওয়া সব পাথ (লিংক/নেভিগেশন): ' + ', '.join(f'`{p}`' for p in paths) + '\n')
o.append('শেয়ার/যাচাইয়ের পাতা সার্ভার বানায় (Spring `ShareController`): `/r/{id}`, `/rx/{code}`, `/shop/{code}`, `/api/share?rxqr=|shopqr=` (QR SVG), `/.well-known/assetlinks.json`; ডাউনলোড `/download/` (nginx)।\n')
o.append('| # | কম্পোনেন্ট | ধরন | ফাইল |\n|---|---|---|---|')
for i, c in enumerate([c for c in vue_comp if c['kind'] != 'part'], 1):
    o.append(f"| {i} | `{c['name']}` | {c['kind']} | `{c['file']}` |")
o.append(f"\n(বাকি {sum(1 for c in vue_comp if c['kind'] == 'part')}টা `.vue` ফাইল কার্ড/অংশ — `tools/data`-তে নেই, `web-vue/src`-এ দেখুন।)\n")
o.append('### অ্যাডমিন প্যানেল (`/admin/#/…`)\n')
o.append('| পাথ | নাম |\n|---|---|')
for p, l in admin_pages:
    o.append(f'| `#/{p}` | {l} |')
o.append('| `#/verify/…`, `#/ticket/…`, `#/user/…` | যাচাই (কাগজের ছবি), টিকিট, ব্যবহারকারীর বিস্তারিত |\n')

o.append('## ঙ) `/api/community` action — ২২৫টা\n')
o.append('auth = `API-INVENTORY.md`-এর লেখা; markers = Kotlin হ্যান্ডলারে যা পাওয়া গেছে (admin, owner, doctor, doctor|assistant, badge, pharmacy, lab, own-row, same-world, quota); clients = কে পাঠায় (app/web/admin)।\n')
cur = None
for a in actions:
    if a['pkg'] != cur:
        cur = a['pkg']
        o.append(f'\n### package `{cur}`\n')
        o.append('| action | কী করে | auth (doc) | markers | clients | handler |\n|---|---|---|---|---|---|')
    tags = []
    if a['action'] in PUBLIC:
        tags.append('GUEST')
    if a['action'] in CARE:
        tags.append('CARE')
    o.append(f"| `{a['action']}` {' '.join(tags)} | {md_escape(a['desc'][:140])} | {md_escape(a['auth_doc'])} | {', '.join(a['markers']) or '—'} | {a['clients']} | `{a['loc']}` |")
o.append('')
o.append('## চ) অন্য HTTP endpoint\n')
o.append('| path | ফাইল |\n|---|---|')
for p, _, f in endpoints:
    o.append(f'| `{p}` | `{f}` |')
o.append('')
o.append('## ছ) পুশ নোটিফিকেশন — ধরন, কোথায় তৈরি, চাপলে কোথায় যায়\n')
o.append('| type | চাপলে (Android `openFromData`) | কল-সাইট (শিরোনাম) |\n|---|---|---|')
by_t = defaultdict(list)
for x in pushes:
    by_t[x['type']].append(x)
for t in PUSH_TYPES:
    sites = '<br>'.join(f"`{x['file']}:{x['line']}` {md_escape(x['title'])}" for x in by_t[t][:12])
    o.append(f'| `{t}` | {TAP.get(t, "?")} | {sites} |')
o.append('\nঅ্যাপের নোটিফিকেশন চ্যানেল: ' + ', '.join(f'`{c[0]}` ({c[2]})' for c in channels) + ' + `blood_requests` (FCM ডিফল্ট)। ওষুধের অ্যালার্ম ফোনেই (`MedAlarmReceiver`, বুটের পর `MedAlarmBootReceiver`)। ওয়েবে পুশ নেই (শুধু ঘণ্টার তালিকা + SSE `/api/events`)।\n')
o.append('## জ) ইমেইল\n')
o.append('| ধরন | কখন | কোড |\n|---|---|---|\n| লগইন কোড (৬ সংখ্যা, ১০ মিনিট) | `/api/auth/send-code` (অ্যাপ, ওয়েব, অ্যাডমিন প্যানেল) | `notify/MailSender.kt` (Amazon SES), `auth/AuthController.kt` |\n')
o.append('কেয়ারগিভারের আমন্ত্রণ ইমেইলে যায় না — শুধু পুশ/ঘণ্টা (যাঁকে আমন্ত্রণ, তাঁর Lifolo অ্যাকাউন্ট থাকতে হয়)। অপারেশনের ইমেইল: Uptime check-এর GitHub ইমেইল, Sentry (চালু হলে)।\n')
o.append('## ঝ) ক্রন ও ব্যাকগ্রাউন্ড কাজ\n')
o.append('| ধরন | নাম | কখন | ফাইল |\n|---|---|---|---|')
for j in sorted(jobs):
    o.append(f'| {j[0]} | `{j[1]}` | {j[2]} | `{j[3]}` |')
o.append('| VPS systemd | `lifolo-backup.timer` (pg_dump, ১৪ দিন রাখা) | 21:30 UTC রোজ | `deploy/setup-base.sh` |')
o.append('| VPS systemd | `certbot.timer` (Let\'s Encrypt নবায়ন) | দিনে ২ বার | `deploy/setup-base.sh` |')
o.append('| GitHub Actions | Uptime check (`/api/health`) | প্রতি ৩ ঘণ্টা (cron `23 */3 * * *`) | `.github/workflows/uptime.yml` |')
o.append('| GitHub Actions | Backup check (restore test, R2 কপি) | সোমবার 23:17 UTC | `.github/workflows/backup-check.yml` |')
o.append('| GitHub Actions | Backend e2e (web journeys vs Spring) | শনিবার 21:47 UTC (৭ দিনে বদল থাকলে) | `.github/workflows/backend-e2e.yml` |')
o.append('| GitHub Actions | Android e2e (Maestro, প্রোডাকশন ডেমোতে) | রবিবার 22:17 UTC | `.github/workflows/e2e-android-cutover.yml` |')
o.append('| অলস (lazy) | অ্যাম্বুলেন্স অনুরোধের ঢেউ/মেয়াদ (৬০ সে, ৩ মি, ২ মি অফার, ১২ ঘ, ২৪ ঘ purge) | প্রতি পড়া/লেখায় + `ambulance-timers` প্রতি মিনিটে | `ambulance/AmbReqService.kt` |')
o.append('| অলস | "এখনো খালি আছেন?" (১১ ঘণ্টা) | তালিকা খুললে + দৈনিক | `ambulance/AmbulanceService.kt` |\n')
o.append('## ঞ) শেয়ার লিংক, QR, অ্যাপ-লিংক\n')
o.append('| কী | কোথায় তৈরি | কে খোলে |\n|---|---|---|')
o.append('| রক্তের রিকোয়েস্ট লিংক `https://lifolo.com/r/{id}` | অ্যাপ `shareRequest` (`SHARE_BASE`), ওয়েব | ব্রাউজারে সার্ভারের পাতা (নাম/ফোন/নোট ছাড়া); অ্যাপ থাকলে App Link (`autoVerify`) |')
o.append('| পুরোনো লিংক `https://blood-server-ashy.vercel.app/r/{id}` | ২.৩৪.০-এর আগের অ্যাপ | অ্যাপে খোলে (আলাদা intent-filter) — Vercel বন্ধ হলে ব্রাউজারে খুলবে না |')
o.append('| `getblood://r/{id}` | শেয়ার পাতার "অ্যাপে খুলুন" | অ্যাপ |')
o.append('| প্রেসক্রিপশন যাচাই `/rx/{code}` + QR `/api/share?rxqr=CODE` | প্রেসক্রিপশন, PDF (`RxPdf.kt`) | যে কেউ — রোগীর নাম, বয়স, ওষুধ, পরীক্ষা দেখায় (by design) |')
o.append('| দোকানের পোস্টার `/shop/{code}` + QR `?shopqr=` | ফার্মেসি "ছাপার পোস্টার খুলুন" | রোগী স্ক্যান করে দোকানে প্রেসক্রিপশন পাঠান (Google code scanner, ক্যামেরা permission লাগে না) |')
o.append('| `/.well-known/assetlinks.json` | Spring | Android App Links যাচাই — **release key-এর SHA-256 লাগবে** |')
o.append('| Google Maps → "শেয়ার" → Lifolo (`ShareActivity`, `text/plain`) | — | অ্যাম্বুলেন্স ডাকার জায়গা |')
o.append('| প্রেসক্রিপশন PDF শেয়ার (`FileProvider`) | `RxPdf.share` | অন্য অ্যাপ |\n')
o.append('## ট) Permission prompt\n')
o.append('Manifest: ' + ', '.join(f'`{p}`' for p in permissions) + '\n')
o.append('| ফাইল:লাইন | কোড |\n|---|---|')
for f, l, c in perm_prompts:
    o.append(f'| `{f}:{l}` | `{md_escape(c)}` |')
o.append('\nনোট: ক্যামেরা permission নেই (QR = Google code scanner); ছবি = Photo Picker (permission লাগে না); exact alarm permission manifest-এ নেই → Android 12+ এ ওষুধের অ্যালার্ম inexact (`MedAlarms.kt:161`)।\n')
o.append('## ঠ) সেটিংস\n')
o.append('| কোথায় | সেটিং |\n|---|---|')
o.append('| অ্যাপ সেটিংস (`Settings.kt`) | ভাষা (বাংলা/English); থিম (ফোনের মতো/লাইট/ডার্ক); নোটিফিকেশন: "আশেপাশে কারো রক্ত লাগলে" চালু/বন্ধ, "দিনে সর্বোচ্চ", "রোগীর যে গ্রুপগুলোর জন্য"; "রক্ত দিতে কতদূর যেতে পারবেন?" (কিমি); "যাতায়াত খরচ"; "যে গ্রুপের রিকোয়েস্ট দেখাবে"; এলাকা; "আপনার ব্যবসা যোগ করুন"; প্রাইভেসি পলিসি; ব্যবহারের শর্ত; "সাপোর্টে যোগাযোগ"; ভার্সন |')
o.append('| প্রোফাইল (`Profile.kt`) | প্রোফাইল এডিট (নাম, মোবাইল, ছবি, রক্তদাতার তথ্য), রোল বদলান (ডেমো), লগআউট, অ্যাকাউন্ট মুছুন, ব্যবসা যোগ করুন, কেয়ারগিভার ও পরিবার, সাহায্য ও আপিল, ফোন যাচাই (SMS চালু থাকলে) |')
o.append('| ব্যবসার সেটিং | ফার্মেসি: অনলাইনে বিক্রি (দূরত্ব, ফি, নোট), ওষুধ খোঁজার অনুরোধ নেওয়া, কয়েক দিন বন্ধ; অ্যাম্বুলেন্স: "এখন খালি আছি", ভাড়ার চার্ট, অপেক্ষার জায়গা; ডাক্তার: চেম্বার, "তালিকায় আমাকে দেখাও", "অ্যাপে অ্যাপয়েন্টমেন্ট নেব", বাসায় সেবা, সহকারী, রিপোর্ট দেখার ফি; প্যাথলজি: দাম, বাসা থেকে নমুনা, স্বাভাবিক মাত্রা |')
o.append('| ওয়েব (`/settings`) | ভাষা, থিম, এলাকা (ব্রাউজারে রাখা), নোটিফিকেশনের পছন্দ (প্রোফাইলে) |')
o.append('| সার্ভার (env, `/etc/lifolo/app.env`) | `ADMIN_EMAILS`, `CRON_SECRET`, `WEB_SESSION_SECRET`, SES, `PUSH_MODE`, R2, `SENTRY_DSN`, `SMS_URL_TEMPLATE` (না থাকলে ফোন যাচাই বন্ধ), `DEMO_KEEP_FIREBASE_USERS` |\n')
with open(os.path.join(OUT, '01-inventory.md'), 'w', encoding='utf-8') as fh:
    fh.write('\n'.join(o))

# ---------------------------------------------------------------- 02-coverage-matrix.md
o = []
o.append('# ২. কভারেজ ম্যাট্রিক্স — কোন জিনিস কোন স্বয়ংক্রিয় টেস্টে আছে\n')
o.append(GEN)
o.append('সংকেত: **BE** = Spring টেস্ট (`backend/src/test`, unit + IT, ফাইলের নাম), **WEB-J:n** = ৯টা ওয়েব যাত্রা চালানোর সময় এই action কতবার ডাকা হয়েছে '
         '(`tools/data/web-journeys-actions.json`, লোকাল স্ট্যাক; যাত্রাগুলো **পুরোনো React ওয়েব** আর Node সার্ভারে চলেছে — নাম একই, তাই action-কভারেজ খাটে, কিন্তু Vue ওয়েবের UI কভারেজ নয়), '
         '**VUE-mock** = Vue Playwright নকল API-র fixture-এ আছে (শুধু UI দেখায়, সার্ভার নয়), **Robo** = Android Robolectric স্ক্রিনশট টেস্ট (শুধু হাতে চালানো workflow), '
         '**Maestro:jN** = Android Maestro যাত্রা (প্রোডাকশন ডেমোতে, হাতে/সাপ্তাহিক)। **NONE** = কোনো স্বয়ংক্রিয় টেস্ট নেই।\n')
o.append('## সংখ্যা\n')
o.append(f"- action: {C['actions']} — Spring টেস্টে নাম আছে {C['a_be']}, ওয়েব যাত্রায় ডাকা {C['a_web']}, কোনোটাতেই নেই {C['a_none']}; **UI-র পুরো পথে (e2e) নেই {C['a_no_e2e']}**, Spring-এ পাতলা আর e2e-তেও নেই {C['a_thin']}")
o.append(f"- Android স্ক্রিন/ডায়ালগ/শিট/ট্যাব: {C['ui']} — Robolectric বা Maestro-তে নেই **{C['ui_none']}**")
o.append(f"- Vue কম্পোনেন্ট ফাইল: {C['vue_files']} — যাত্রার লেখায় মেলেনি {C['vue_none']} (Vue-র নিজের Playwright নকল-API টেস্ট 219টা পাতা দেখে, কিন্তু আসল সার্ভারে নয়)")
o.append('- পুশ: সার্ভারে পুশ লেখা হয় কি না কয়েকটা IT দেখে (`RecordingPushTransport`); **ফোনে আসা আর চাপলে ঠিক পাতা খোলা — কোনো স্বয়ংক্রিয় টেস্ট নেই**')
o.append('- ইমেইল: `AuthIT` নকল মেইলে কোড দেখে; **আসল ইনবক্সে পৌঁছানো (SES, Spam) — নেই**')
o.append('- ক্রন: DailyTask-গুলোর IT আছে কিছুর (নিচে); **প্রোডাকশনে 03:00 UTC-তে সত্যি চলল কি না — নজরদারি নেই**\n')
o.append('## ক) action কভারেজ\n')
o.append('| action | package | কভারেজ |\n|---|---|---|')
for a in actions:
    o.append(f"| `{a['action']}` | {a['pkg']} | {cov_cell(a)} |")
RISK = {  # HAND: why it matters
    'admin_user_action': 'অ্যাকাউন্ট বন্ধ/চালু, নিষেধাজ্ঞা — ভুল হলে নিরীহ মানুষ বন্ধ',
    'admin_add': 'অ্যাডমিন বানানো — নিরাপত্তা',
    'admin_remove': 'অ্যাডমিন সরানো — নিরাপত্তা',
    'device_register': 'পুশ টোকেন — না চললে কোনো নোটিফিকেশন যায় না',
    'device_unregister': 'লগআউটের পর অন্যের ফোনে পুশ যাওয়া ঠেকায়',
    'phone_send_code': 'SMS — এখন বন্ধ (SMS_URL_TEMPLATE নেই)',
    'phone_verify_code': 'SMS যাচাই',
    'admin_emergency_create': 'জরুরি রক্তের আহ্বান — পুশের ঢেউ',
    'admin_emergency_more': 'জরুরি আহ্বানের পরের ঢেউ',
    'admin_emergency_close': 'জরুরি আহ্বান শেষ',
    'admin_emergency_group': 'একটা গ্রুপ বন্ধ',
    'request_delete': 'রিকোয়েস্ট মোছা — রাজি ডোনারদের জানানো',
    'request_reopen': 'ভুল করে বন্ধ — আবার খোলা',
    'provider_save': 'ডাক্তারের চেম্বার সেভ — ভুল হলে অ্যাপয়েন্টমেন্ট বাতিল হয়',
    'lab_save': 'প্যাথলজি নিবন্ধন',
    'ambulance_delete': 'অ্যাম্বুলেন্স মোছা',
    'verify_docs_upload': 'কাগজের ছবি — যাচাই',
    'admin_request_docs': 'কাগজ চাওয়া',
    'ambreq_create': 'অ্যাম্বুলেন্স ডাকা — জীবন-মরণ',
    'ambreq_accept': 'চালক রাজি — জীবন-মরণ',
    'ambreq_agree': 'রোগী ভাড়ায় রাজি',
    'ambreq_cancel': 'রোগী বাতিল — চালক মুক্ত',
    'ambreq_step': 'পৌঁছেছি/শেষ',
    'ambreq_decline': 'চালক না — পরের ঢেউ',
    'ambreq_budget': 'ভাড়া বাড়ানো',
    'ambreq_byphone': 'ফোনে পাওয়া গাড়ি',
    'ambreq_replace': 'চালক বদল',
    'ambreq_count': 'ভাড়ার মধ্যে কয়টা গাড়ি',
    'ticket_create': 'আপিল/সাপোর্ট — একমাত্র যোগাযোগের পথ',
    'admin_tickets': 'আপিলের তালিকা',
    'admin_reports': 'রিপোর্টের তালিকা',
    'pharmacy_delete': 'দোকান মোছা — অর্ডারের ঠিকানা মোছা',
    'lab_home_cancel': 'বাসা থেকে নমুনা বাতিল — ঠিকানা মোছা',
}
o.append('\n### NONE — কোনো স্বয়ংক্রিয় টেস্টে নেই এমন action\n')
o.append('০টা — phase 2-এর পোর্টে প্রতিটা action-এর অন্তত একটা Spring IT/unit আছে। আসল ফাঁক নিচের দুটো তালিকায়।\n')
o.append('\n### ফাঁক ১ — কোনো end-to-end (অ্যাপ/ওয়েবের UI দিয়ে) টেস্টে নেই, শুধু Spring IT\n')
nn = [a for a in actions if not a['t_web']]
o.append(f'{len(nn)}টা। UI থেকে সার্ভার পর্যন্ত পুরো পথ কেউ চালায় না — এগুলো হাতে টেস্টে (04) আর Phase 2-এর নতুন যাত্রায়।\n')
o.append('| # | action | কী করে | Spring টেস্ট | কেন জরুরি |\n|---|---|---|---|---|')
for i, a in enumerate(sorted(nn, key=lambda a: (0 if a['action'] in RISK else 1, a['pkg'], a['action'])), 1):
    o.append(f"| {i} | `{a['action']}` | {md_escape(a['desc'][:100])} | ×{a['t_backend_n']}{' neg-only' if a['t_backend_neg_only'] else ''} | {RISK.get(a['action'], '')} |")
o.append('\n### ফাঁক ২ — Spring টেস্টে পাতলা (≤২টা লাইনে নাম, বা শুধু ভুলের পথ) আর e2e-তেও নেই\n')
th = [a for a in actions if (a['t_backend_neg_only'] or a['t_backend_n'] <= 2) and not a['t_web']]
o.append(', '.join(f"`{a['action']}`" + (' (শুধু ভুলের পথ)' if a['t_backend_neg_only'] else '') for a in th) or '—')
o.append('')
o.append('### শুধু ভুলের পথে দেখা (ওয়েব যাত্রায় ডাকা হয়েছে কিন্তু সফল উত্তর নেই)\n')
only_err = [a['action'] for a in actions if a['t_web'] and not a['t_web_ok']]
o.append(', '.join(f'`{x}`' for x in only_err) or '—')
o.append('\n### যাত্রায় ডাকা হয়েছে কিন্তু Spring টেস্টে নাম নেই (Spring-এ শুধু e2e দিয়ে ঢাকা)\n')
o.append(', '.join(f"`{a['action']}`" for a in actions if a['t_web'] and not a['t_backend']) or '—')
o.append('')
o.append('## খ) অন্য HTTP endpoint\n')
o.append('| path | Spring টেস্ট (নাম উল্লেখ) | ওয়েব যাত্রায় |\n|---|---|---|')
for p, _, f in endpoints:
    key = p.replace('{id}', '').replace('{code}', '').rstrip('/')
    t = sorted({os.path.basename(x)[:-3] for x, s in bt.items() if key and key in s})
    w = sum(v for k, v in observed_paths.items() if k and key and k.startswith(key.split('{')[0]))
    o.append(f"| `{p}` | {', '.join(t) or '**NONE**'} | {w or '—'} |")
o.append('')
o.append('## গ) Android স্ক্রিন/ডায়ালগ কভারেজ\n')
o.append('| নাম | ধরন | Robo | Maestro | অবস্থা |\n|---|---|---|---|---|')
for u in ui:
    st = '**NONE**' if not u['robo'] and not u['maestro'] else ''
    o.append(f"| `{u['name']}` | {u['kind']} | {'✓' if u['robo'] else ''} | {', '.join(u['maestro'])} | {st} |")
o.append('\nইনলাইন ডায়ালগগুলোর (FormDialog/AlertDialog …) আলাদা কোনো স্বয়ংক্রিয় টেস্ট নেই — হাতে টেস্টের ফাইলগুলোতে ঢাকা।\n')
o.append('## ঘ) Vue পাতা/ডায়ালগ — আসল সার্ভারের যাত্রায় লেখা মেলে কি না\n')
o.append('Vue-র নিজের Playwright (নকল API) সব পাতা খোলে; নিচে শুধু দেখা হয়েছে e2e যাত্রার লেখা এই কম্পোনেন্টের কোনো বাংলা লেখার সাথে মেলে কি না।\n')
o.append('| কম্পোনেন্ট | ধরন | যাত্রা |\n|---|---|---|')
for c in vue_comp:
    if c['kind'] == 'part':
        continue
    o.append(f"| `{c['name']}` | {c['kind']} | {', '.join(c['journeys']) or '**NONE**'} |")
o.append('')
o.append('## ঙ) পুশ, ইমেইল, ক্রন, লিংক, permission, সেটিংস\n')
o.append('| জিনিস | স্বয়ংক্রিয় টেস্ট | ফাঁক |\n|---|---|---|')
push_be = sorted({t for t in PUSH_TYPES if re.search(r'"' + re.escape(t) + r'"', ALL_TEST)})
o.append(f"| পুশ — সার্ভারে তৈরি | type-এর নাম টেস্টে আছে: {', '.join(push_be) or '—'} | বাকি: {', '.join(t for t in PUSH_TYPES if t not in push_be)} |")
o.append('| পুশ — FCM দিয়ে ফোনে পৌঁছানো | **NONE** (CI-তে `PUSH_MODE=fake`) | হাতে + প্রোডাকশন ডেলিভারি চেক |')
o.append('| পুশ — চাপলে সঠিক পাতা (`openFromData`) | **NONE** | হাতে টেস্ট N-সিরিজ |')
o.append('| ইমেইল — লগইন কোড | `AuthIT` (নকল) | আসল SES ডেলিভারি, Spam, sandbox |')
jobs_t = [j[1] for j in jobs if re.search(r'\b' + j[1] + r'\b', ALL_TEST)]
o.append(f"| ক্রন/DailyTask | টেস্টে ক্লাসের নাম: {', '.join(jobs_t) or '—'}; `/api/cron/daily` রানার টেস্ট আছে কি না নিচে | প্রোডাকশনে রোজ চলা আর ফল দেখা — **NONE** |")
o.append('| শেয়ার লিংক `/r`, `/rx`, `/shop`, QR, assetlinks | `ShareIT` | App Link (release key) যাচাই **NONE** |')
o.append('| permission prompt (লোকেশন, নোটিফিকেশন) | **NONE** | হাতে |')
o.append('| সেটিংস (ভাষা, থিম, নোটিফিকেশনের পছন্দ) | আংশিক (যাত্রা ০৭) | অ্যাপে **NONE** |')
o.append('| অফলাইন/ধীর নেটওয়ার্ক | **NONE** | হাতে |')
o.append('| ছোট পর্দা, ডার্ক থিম | Vue mocked (mobile/desktop), Robo (কিছু) | Android ছোট পর্দা/বড় লেখা **NONE** |')
o.append('| লোড/স্ট্রেস | **NONE** | 05-non-functional |')
o.append('| ব্যাকআপ ফেরত | Backup check workflow (সাপ্তাহিক) | পুরো VPS হারানোর মহড়া **NONE** |')
o.append('| Vue ওয়েব + Spring একসাথে (আসল স্ট্যাক) | **NONE** (CI-র যাত্রা React ওয়েবে; Vue-র যাত্রা শুধু Node-এ, হাতে) | বড় ফাঁক |')
o.append('| Android + Spring | Maestro (প্রোডাকশন ডেমো, ৮ যাত্রা, বেশিরভাগ পড়া) | লেখার কাজ, নোটিফিকেশন **NONE** |')
o.append('')
with open(os.path.join(OUT, '02-coverage-matrix.md'), 'w', encoding='utf-8') as fh:
    fh.write('\n'.join(o))

# ---------------------------------------------------------------- 03-authorization-matrix.md
o = []
o.append('# ৩. অনুমতির ম্যাট্রিক্স (authorization) — কোড যেমন মানে\n')
o.append(GEN)
o.append('কীভাবে বানানো: সব action-এর জন্য (১) কেন্দ্রীয় নিয়ম কোড থেকে — অতিথি = `ActionPolicy.PUBLIC_ACTIONS`, কেয়ারগিভার = `CARE_ACTIONS` (`asUid`), '
         'অ্যাডমিন কুকি = `admin_*` + `WEB_PANEL_READS` + `web_logout` (`CommunityController.webUser`); (২) হ্যান্ডলারের নিজের চেক — Kotlin বডিতে খোঁজা '
         'markers আর `API-INVENTORY.md`-এর auth লেখা মিলিয়ে।\n')
o.append('সংকেত: **Y** = চলে · **own** = চলে কিন্তু শুধু নিজের সারি (অন্যের id দিলে 404/403/খালি) · **own shop / own lab** = নিজের অনুমোদিত দোকান/প্যাথলজি · '
         '**for listed doctor** = যে ডাক্তার সহকারী হিসেবে ইমেইল যোগ করেছেন · **as patient** = কেয়ারগিভার রোগীর হয়ে (সক্রিয় care link) · **any** = অন্যেরটাও (অ্যাডমিন) · '
         '**N401** = লগইন লাগবে · **N403** = নিষেধ (কিছু ক্ষেত্রে 404 দিয়ে লুকানো)।\n')
o.append('রোল কলাম: ' + ' · '.join(ROLES) + '। "User" = যেকোনো লগইন করা মানুষ (রোগী, রক্তদাতা)। ব্যবসার রোলও একজন User — তাই User-এ Y মানে সবার Y।\n')
o.append('> ⚠️ এটা স্থির বিশ্লেষণ (static)। Phase 2-এ প্রতিটা সারি একটা স্বয়ংক্রিয় টেস্টে প্রমাণ করতে হবে (`AuthorizationMatrixIT` — ০৬-এ)। '
         'সন্দেহজনক জিনিসগুলো আলাদা ফাইলে: [03a-security-findings.md](03a-security-findings.md)।\n')
o.append('| action | ' + ' | '.join(ROLES) + ' | doc auth | রিভিউ-সংকেত |')
o.append('|' + '---|' * (len(ROLES) + 3))
for a in actions:
    r = a['auth']
    o.append(f"| `{a['action']}` | " + ' | '.join(r.get(k, '?') for k in ROLES) + f" | {md_escape(a['auth_doc'])} | {'; '.join(a['flags']) or ''} |")
o.append('')
o.append('## অন্য endpoint\n')
o.append('| endpoint | কে পারে |\n|---|---|')
EP_AUTH = {  # HAND (read from the controllers)
    '/api/health': 'সবাই', '/api/auth/send-code': 'সবাই (IP/ইমেইল সীমা)', '/api/auth/verify-code': 'সবাই; `web:true` শুধু অ্যাডমিন ইমেইল; `demo`/`demoRoles` — **ডেমো চালু থাকলে যে কেউ, কোড ছাড়া**',
    '/api/community': 'ওপরের ম্যাট্রিক্স', '/api/events': 'Bearer — শুধু নিজের `user:{uid}` স্ট্রিম',
    '/api/notify-request': 'Bearer — রক্তপ্রার্থী নিজে', '/api/respond': 'Bearer — যোগ্য ডোনার (একই world, গ্রুপ মেলে)', '/api/close-request': 'Bearer — রক্তপ্রার্থী', '/api/edit-request': 'Bearer — রক্তপ্রার্থী',
    '/api/admin/corrections': 'অ্যাডমিন (Bearer বা কুকি)', '/api/admin/resolve': 'অ্যাডমিন (Bearer বা কুকি)',
    '/api/cron/daily': '`Bearer $CRON_SECRET` (GET)', '/api/cron/storage': '`Bearer $CRON_SECRET`', '/api/cron/storage/migrate': '`Bearer $CRON_SECRET`', '/api/cron/storage/probe': '`Bearer $CRON_SECRET`', '/api/cron/sentry-test': '`Bearer $CRON_SECRET`',
    '/api/share': 'সবাই', '/r/{id}': 'সবাই', '/rx/{code}': 'সবাই (কোড জানলে)', '/shop/{code}': 'সবাই', '/.well-known/assetlinks.json': 'সবাই',
}
for p, _, f in endpoints:
    o.append(f'| `{p}` | {EP_AUTH.get(p, "?")} |')
o.append('')
with open(os.path.join(OUT, '03-authorization-matrix.md'), 'w', encoding='utf-8') as fh:
    fh.write('\n'.join(o))

print(json.dumps({k: (dict(v) if isinstance(v, Counter) else v) for k, v in C.items()}, ensure_ascii=False))
print('flagged actions:', [a['action'] for a in actions if a['flags']])
