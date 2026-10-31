import re
import json

with open('E:\\shop-b2b2c\\reference-site\\16688-index.js', 'r', encoding='utf-8', errors='ignore') as f:
    code = f.read()

# 1. Routes
routes = set(re.findall(r'path:\s*["\']([^"\']+)["\']', code))
print('=== 16688 前端路由 ===')
for r in sorted(routes):
    if len(r) < 40 and not r.startswith('http'):
        print(' -', r)

# 2. APIs
apis = set(re.findall(r'["\'](/api/[^"\']+)["\']', code))
print('\n=== 16688 API 接口 ===')
for a in sorted(apis):
    print(' -', a)

# 3. Images and Assets
images = set(re.findall(r'https?://[^\s"\'()<>]+\.(?:png|jpg|jpeg|svg|webp|gif)', code))
print(f'\n=== 16688 静态图资 (共 {len(images)} 个) ===')
for img in sorted(images)[:10]:
    print(' -', img)

# 4. Color codes
colors = set(re.findall(r'#[0-9a-fA-F]{6}', code))
print(f'\n=== 核心品牌色 (共 {len(colors)} 个) ===')
print(list(colors)[:15])

# Save analysis summary
analysis = {
    'routes': sorted(list(routes)),
    'apis': sorted(list(apis)),
    'images': sorted(list(images)),
    'colors': sorted(list(colors))
}
with open('E:\\shop-b2b2c\\reference-site\\16688-analysis.json', 'w', encoding='utf-8') as f:
    json.dump(analysis, f, ensure_ascii=False, indent=2)
print('\nSaved analysis to E:\\shop-b2b2c\\reference-site\\16688-analysis.json')
