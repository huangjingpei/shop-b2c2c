import { createRequire } from "module";
import fs from "fs";
import path from "path";

const require = createRequire(import.meta.url);
const { chromium } = require("C:/Users/Administrator/.gemini/config/skills/web-clone/node_modules/playwright");

async function fetchHostinger() {
  const outDir = "E:/shop-b2b2c/reference-site";
  const url = "https://ai-builder.hostinger.com/b7458240-f420-4d17-9ec0-c9e3daa9f2e9?location=hpanel";

  console.log("Launching browser to inspect:", url);
  const browser = await chromium.launch({
    headless: false,
    channel: "chrome",
    args: ["--disable-blink-features=AutomationControlled"]
  });

  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
  });

  const page = await context.newPage();

  const assets = [];
  page.on("response", async (response) => {
    try {
      const resUrl = response.url();
      const status = response.status();
      const contentType = response.headers()["content-type"] || "";
      if (status === 200 && (contentType.includes("javascript") || contentType.includes("css") || contentType.includes("image") || contentType.includes("json"))) {
        assets.push({ url: resUrl, type: contentType });
      }
    } catch (e) {}
  });

  console.log("Navigating to target...");
  try {
    await page.goto(url, { waitUntil: "networkidle", timeout: 35000 });
  } catch (e) {
    console.log("Goto warning:", e.message);
  }

  console.log("Waiting 6s for dynamic content...");
  await page.waitForTimeout(6000);

  const title = await page.title();
  const finalUrl = page.url();
  console.log("Page Title:", title);
  console.log("Final URL:", finalUrl);

  await page.screenshot({ path: path.join(outDir, "preview-1440.png"), fullPage: true });
  console.log("Screenshot saved: preview-1440.png");

  const html = await page.content();
  fs.writeFileSync(path.join(outDir, "index.html"), html, "utf-8");
  console.log("Main HTML saved (bytes:", html.length, ")");

  const frames = page.frames();
  console.log("Total frames detected:", frames.length);

  for (let i = 0; i < frames.length; i++) {
    const frame = frames[i];
    const frameUrl = frame.url();
    console.log(`Frame [${i}]:`, frameUrl);
    try {
      const frameHtml = await frame.content();
      fs.writeFileSync(path.join(outDir, `frame-${i}.html`), frameHtml, "utf-8");
      console.log(`Saved frame-${i}.html (bytes: ${frameHtml.length})`);
    } catch (err) {
      console.log(`Failed to get frame [${i}] content:`, err.message);
    }
  }

  fs.writeFileSync(path.join(outDir, "assets.json"), JSON.stringify(assets, null, 2), "utf-8");
  console.log("Captured assets written to assets.json");

  await browser.close();
}

fetchHostinger().catch(console.error);
