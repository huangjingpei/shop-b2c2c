import re

with open('E:\\shop-b2b2c\\reference-site\\16688-index.css', 'r', encoding='utf-8', errors='ignore') as f:
    css = f.read()

# find all ._hero_ and ._glass_ rules with their bodies
pattern = re.compile(r'(\._(?:hero|glass)[^{]+)\{([^}]+)\}')
matches = pattern.findall(css)
print(f'Total matches: {len(matches)}')

with open('E:\\shop-b2b2c\\reference-site\\hero_css_rules.css', 'w', encoding='utf-8') as f:
    for sel, body in matches:
        # strip hash suffix e.g. ._heroTitle_1x8bx_20 -> .heroTitle
        clean_sel = re.sub(r'_[a-zA-Z0-9]+_\d+', '', sel)
        clean_sel = clean_sel.replace('._', '.')
        f.write(f'{clean_sel} {{\n{body.strip()}\n}}\n\n')

print('Saved clean hero CSS rules to hero_css_rules.css!')
