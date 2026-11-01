"""Merges a freshly generated gradle/verification-metadata.xml with the current one.

A refresh run with a warm Gradle cache can leave out entries for files it did not download again;
a later run with a cold cache then fails verification. The merge keeps every checksum from both and
stops on any artifact whose checksum changed (that needs a person to look at it).

python3 scripts/merge_verification.py <new file>   (updates gradle/verification-metadata.xml)
"""
import re, sys

COMP = re.compile(r'      <component group="([^"]+)" name="([^"]+)" version="([^"]+)">\n(.*?)      </component>\n', re.S)
ART = re.compile(r'(         <artifact name="([^"]+)">.*?</artifact>\n)', re.S)
SHA = re.compile(r'<sha256 value="(\w+)"')

def parse(s):
    return {(m.group(1), m.group(2), m.group(3)): {a.group(2): a.group(1) for a in ART.finditer(m.group(4))} for m in COMP.finditer(s)}

cur_path = "gradle/verification-metadata.xml"
cur, new = open(cur_path).read(), open(sys.argv[1]).read()
c, n = parse(cur), parse(new)
changed, added = [], 0
for key, arts in n.items():
    for name, block in arts.items():
        old = c.setdefault(key, {}).get(name)
        if old is None:
            c[key][name] = block; added += 1
        elif SHA.search(old).group(1) != SHA.search(block).group(1):
            changed.append(f"{key[0]}:{key[1]}:{key[2]} {name}")
if changed:
    sys.exit("Checksums changed, review before merging:\n" + "\n".join(changed))
body = "".join(
    f'      <component group="{g}" name="{nm}" version="{v}">\n' + "".join(c[(g, nm, v)][a] for a in sorted(c[(g, nm, v)])) + "      </component>\n"
    for (g, nm, v) in sorted(c))
start = cur.index("   <components>\n") + len("   <components>\n")
end = cur.index("   </components>")
open(cur_path, "w").write(cur[:start] + body + cur[end:])
print(f"added {added} artifacts; {sum(len(a) for a in c.values())} in total")
