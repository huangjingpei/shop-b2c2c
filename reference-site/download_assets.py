import os
import requests

images = {
    'search-header-bg.png': 'https://16688.oss-accelerate.aliyuncs.com/assets/index/20261018-205613/assets/search-header-bg.da3d45c4.png',
    'deliver.png': 'https://16688.oss-accelerate.aliyuncs.com/assets/index/20261018-205613/assets/deliver.1b28e96f.png',
    'incentive.png': 'https://16688.oss-accelerate.aliyuncs.com/assets/index/20261018-205613/assets/incentive.55043fc3.png',
    'jingmall-pro.png': 'https://16688.oss-accelerate.aliyuncs.com/assets/index/20261018-205613/assets/jingmall-pro.f4d5081d.png',
    'safe.png': 'https://16688.oss-accelerate.aliyuncs.com/assets/index/20261018-205613/assets/safe.e0f48f65.png',
    'shop.png': 'https://16688.oss-accelerate.aliyuncs.com/assets/index/20261018-205613/assets/shop.5db87a8a.png',
    'source.png': 'https://16688.oss-accelerate.aliyuncs.com/assets/index/20261018-205613/assets/source.eaf818e5.png',
    'sync.png': 'https://16688.oss-accelerate.aliyuncs.com/assets/index/20261018-205613/assets/sync.09d37845.png'
}

target_dir = 'E:\\shop-b2b2c\\shop-storefront-vue\\src\\assets\\16688'
os.makedirs(target_dir, exist_ok=True)

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
}

for name, url in images.items():
    dest = os.path.join(target_dir, name)
    try:
        r = requests.get(url, headers=headers, timeout=10)
        if r.status_code == 200:
            with open(dest, 'wb') as f:
                f.write(r.content)
            print(f'Downloaded {name} ({len(r.content)} bytes)')
        else:
            print(f'Failed {name}: HTTP {r.status_code}')
    except Exception as e:
        print(f'Error {name}: {e}')

print('All 16688 official assets downloaded!')
