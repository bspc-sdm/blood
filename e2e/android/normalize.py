# Maestro-র লেখা মেলানো: য়/ড়/ঢ় অ্যাপে কখনো এক অক্ষরে, কখনো দুই অক্ষরে থাকে — ফ্লোর লেখায় দুটোই মেলে এমন regex বসানো।
# বারবার চালালেও ক্ষতি নেই। ব্যবহার: python3 e2e/android/normalize.py
import glob, os
pairs = [('য়', 'য়'), ('ড়', 'ড়'), ('ঢ়', 'ঢ়')]
root = os.path.dirname(os.path.abspath(__file__))
for p in glob.glob(root + '/flows/*.yaml') + glob.glob(root + '/common/*.yaml'):
    out = []
    for l in open(p).read().split('\n'):
        if not l.lstrip().startswith('#'):
            for i, (comp, dec) in enumerate(pairs):
                alt = '(?:%s|%s)' % (comp, dec)
                l = l.replace(alt, '\x00%d' % i).replace(dec, comp).replace(comp, '\x00%d' % i)
            for i, (comp, dec) in enumerate(pairs):
                l = l.replace('\x00%d' % i, '(?:%s|%s)' % (comp, dec))
        out.append(l)
    open(p, 'w').write('\n'.join(out))
