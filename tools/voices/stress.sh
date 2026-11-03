#!/bin/bash
# Does eSpeak NG take the stress from a mark in the text (U+0301 after the vowel)? Printed for review.
set -u
sudo apt-get install -y -q espeak-ng >/dev/null 2>&1
espeak-ng --version
for w in "замок" "за́мок" "замо́к" "дорога" "доро́га" "до́рога" "кілометрів" "кіломе́трів" "черговий" "чергови́й" "черго́вий" "листопада" "листопа́да"; do
  printf '%-14s ' "$w"; espeak-ng -q -v uk --ipa "$w"
done
echo "--- sentence"
espeak-ng -q -v uk --ipa "Ми пройшли чотирна́дцять кіломе́трів доро́гою до за́мку."
espeak-ng -q -v uk --ipa "Ми пройшли чотирнадцять кілометрів дорогою до замку."
