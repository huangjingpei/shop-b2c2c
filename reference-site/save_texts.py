import re
from collections import Counter

with open('E:\\shop-b2b2c\\reference-site\\16688-index.js', 'r', encoding='utf-8', errors='ignore') as f:
    code = f.read()

escaped = re.findall(r'(?:\\u[0-9a-fA-F]{4})+', code)
decoded = []
for item in escaped:
    try:
        txt = item.encode('utf-8').decode('unicode-escape')
        decoded.append(txt)
    except:
        pass

counts = Counter(decoded).most_common(150)
with open('E:\\shop-b2b2c\\reference-site\\16688-texts.txt', 'w', encoding='utf-8') as f:
    for s, c in counts:
        if len(s.strip()) > 1:
            f.write(f'{s} ({c}次)\n')

print(f'Wrote {len(counts)} top texts to 16688-texts.txt')
