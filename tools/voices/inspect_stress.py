import os, inspect, ukrainian_word_stress as u
root = os.path.dirname(u.__file__)
for f in ("compile_dict.py", "stressify_.py", "tags.py"):
    print("=====", f); print(open(os.path.join(root, f)).read())
import marisa_trie
t = marisa_trie.BytesTrie(); t.load(os.path.join(root, "data/stress.trie"))
keys = t.keys()
print("entries", len(keys))
for k in ("мама", "замок", "черговий", "листопада", "шумить"):
    print(k, t.get(k))
