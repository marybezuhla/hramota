import os, ukrainian_word_stress as u, importlib.metadata as md, inspect
root = os.path.dirname(u.__file__)
for d, _, fs in os.walk(root):
    for f in fs:
        p = os.path.join(d, f); print(p.replace(root, "pkg"), os.path.getsize(p))
print(md.metadata("ukrainian-word-stress").get("License"), md.requires("ukrainian-word-stress"))
for f in md.files("ukrainian-word-stress"):
    if "LICEN" in str(f).upper(): print("LICENSE FILE", f); print(open(f.locate()).read()[:1500])
src = inspect.getsource(u)
print(src[:3000])
import ukrainian_word_stress.stressify as s
print(inspect.getsource(s)[:6000])
