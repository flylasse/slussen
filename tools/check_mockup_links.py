"""Check that every link and anchor between the mockups in docs/mockups resolves; exit 1 on a broken one."""
import re, os, sys, html
D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "mockups")
ids = {}
hrefs = []
for f in os.listdir(D):
    if not f.endswith(".html"):
        continue
    s = open(os.path.join(D, f), encoding="utf-8").read()
    ids[f] = set(re.findall(r'\bid="([^"]+)"', s))
    dup = [i for i in ids[f] if s.count(f'id="{i}"') > 1]
    if dup:
        print("DUPLICATE id", f, dup)
    for m in re.finditer(r'href="([^"]*)"', s):
        hrefs.append((f, m.group(1)))
bad = 0
for f, h in hrefs:
    h = html.unescape(h)
    if h.startswith(("http:", "https:", "mailto:")):
        continue
    if "#" in h:
        p, a = h.split("#", 1)
    else:
        p, a = h, ""
    tgt = p or f
    if tgt.startswith("../"):
        if not os.path.exists(os.path.normpath(os.path.join(D, tgt))):
            print("MISSING FILE", f, h); bad += 1
        continue
    if tgt not in ids:
        if not os.path.exists(os.path.join(D, tgt)):
            print("MISSING FILE", f, h); bad += 1
        continue
    if a and a not in ids[tgt]:
        print("MISSING ANCHOR", f, h); bad += 1
print(f"{len(hrefs)} links checked, {bad} broken")
sys.exit(1 if bad else 0)
