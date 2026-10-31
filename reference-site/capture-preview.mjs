import { createRequire } from 'module';
const require = createRequire('C:/Users/Administrator/.gemini/config/skills/web-clone/package.json');
const { chromium } = require('playwright');

async function capture() {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 }
  });
  const page = await context.newPage();
  await page.goto('http://localhost:3003', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1000);
  await page.screenshot({ path: 'E:/shop-b2b2c/reference-site/our-storefront-preview.png', fullPage: false });
  console.log('Saved screenshot to E:/shop-b2b2c/reference-site/our-storefront-preview.png');
  await browser.close();
}

capture().catch(err => console.error(err));
