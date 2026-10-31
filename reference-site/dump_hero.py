import re

with open('E:\\shop-b2b2c\\reference-site\\16688-index.js', 'r', encoding='utf-8', errors='ignore') as f:
    code = f.read()

pos = 436621
start = max(0, pos - 15000)
end = min(len(code), pos + 15000)

chunk = code[start:end]
decoded = re.sub(r'\\u[0-9a-fA-F]{4}', lambda m: m.group(0).encode('utf-8').decode('unicode-escape', errors='ignore'), chunk)

with open('E:\\shop-b2b2c\\reference-site\\full_hero_component.txt', 'w', encoding='utf-8') as f_out:
    f_out.write(decoded)

print('Wrote 30,000 characters of hero component around 436621 to full_hero_component.txt!')
