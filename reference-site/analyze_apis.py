import re

with open('E:\\shop-b2b2c\\reference-site\\16688-index.js', 'r', encoding='utf-8', errors='ignore') as f:
    code = f.read()

endpoints = set(re.findall(r'["\']([a-zA-Z0-9_\-\/]{4,50})["\']', code))
matching = [e for e in endpoints if any(k in e.lower() for k in ['goods', 'product', 'order', 'category', 'shop', 'user', 'merchant', 'source', 'config', 'search', 'query'])]
print('=== 16688 核心业务路径与字段 ===')
for m in sorted(matching)[:30]:
    if not m.startswith('http') and '/' in m:
        print(' -', m)

# Look for text strings in the UI
chinese_strings = re.findall(r'[\u4e00-\u9fa5]{3,30}', code)
print('\n=== 16688 核心文案词汇 (共 %d 条) ===' % len(chinese_strings))
from collections import Counter
counts = Counter(chinese_strings).most_common(25)
for s, c in counts:
    print(f' - {s} ({c}次)')
