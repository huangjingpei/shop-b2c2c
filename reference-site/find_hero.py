import re

with open('E:\\shop-b2b2c\\reference-site\\16688-index.js', 'r', encoding='utf-8', errors='ignore') as f:
    code = f.read()

# '今日经营概览' unicode
target = '\\u4eca\\u65e5\\u7ecf\\u8425\\u6982\\u89c8'
idx = code.find(target)
print('Found 今日经营概览 at:', idx)
if idx != -1:
    snippet = code[max(0, idx - 800): min(len(code), idx + 2500)]
    decoded = re.sub(r'\\u[0-9a-fA-F]{4}', lambda m: m.group(0).encode('utf-8').decode('unicode-escape', errors='ignore'), snippet)
    with open('E:\\shop-b2b2c\\reference-site\\hero-snippet.txt', 'w', encoding='utf-8') as out:
        out.write(decoded)
    print('Wrote decoded snippet to hero-snippet.txt')
