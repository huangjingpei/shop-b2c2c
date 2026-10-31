with open('E:\\shop-b2b2c\\reference-site\\16688-index.js', 'rb') as f:
    raw = f.read()

# convert '今日经营概览' to utf-8 and to unicode-escape
u_str = '今日经营概览'.encode('unicode-escape')
print('Searching for bytes:', u_str)
pos = raw.find(u_str)
print('Position in bytes:', pos)
if pos != -1:
    chunk = raw[max(0, pos - 1500): min(len(raw), pos + 3000)].decode('utf-8', errors='ignore')
    import re
    decoded = re.sub(r'\\u[0-9a-fA-F]{4}', lambda m: m.group(0).encode('utf-8').decode('unicode-escape', errors='ignore'), chunk)
    with open('E:\\shop-b2b2c\\reference-site\\hero-section-raw.txt', 'w', encoding='utf-8') as out:
        out.write(decoded)
    print('Saved hero-section-raw.txt successfully!')
