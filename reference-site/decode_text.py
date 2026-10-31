import re

with open('E:\\shop-b2b2c\\reference-site\\16688-index.js', 'r', encoding='utf-8', errors='ignore') as f:
    code = f.read()

# Find \uXXXX escaped strings
escaped = re.findall(r'(?:\\u[0-9a-fA-F]{4}){2,}', code)
print(f'Found {len(escaped)} unicode escaped chunks.')

decoded = []
for item in escaped:
    try:
        txt = item.encode('utf-8').decode('unicode-escape')
        decoded.append(txt)
    except:
        pass

from collections import Counter
counts = Counter(decoded).most_common(30)
print('=== 16688 真实高频界面文案 ===')
for s, c in counts:
    if len(s) > 1:
        print(f' - {s} ({c}次)')
