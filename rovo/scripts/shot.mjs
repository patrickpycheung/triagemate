import { chromium } from 'playwright';

const dir = process.argv[2];
const URL = 'http://localhost:8080';
const b = await chromium.launch();

async function runAndWait(page) {
  await page.goto(URL, { waitUntil: 'networkidle' });
  return page;
}
async function diagnose(page) {
  await page.click('#go');
  await page.waitForFunction(
    () => { const o = document.querySelector('#out'); return o && o.textContent.includes('Posted to ServiceNow'); },
    { timeout: 20000 });
  await page.waitForTimeout(600);
}

// 1 — landing (desktop)
const land = await b.newPage({ viewport: { width: 1300, height: 900 }, deviceScaleFactor: 2 });
await runAndWait(land);
await land.screenshot({ path: `${dir}/demo-landing.png` });

// 2 — full result (desktop)
const desk = await b.newPage({ viewport: { width: 1300, height: 1000 }, deviceScaleFactor: 2 });
await runAndWait(desk);
await diagnose(desk);
await desk.screenshot({ path: `${dir}/demo-desktop.png`, fullPage: true });

// 3 — close-up: the two auto-posted ServiceNow comments
const card = desk.locator('.card', { hasText: 'Posted to ServiceNow' });
await card.screenshot({ path: `${dir}/demo-writeback.png` });

// 4 — mobile full result
const mob = await b.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
await runAndWait(mob);
await diagnose(mob);
await mob.screenshot({ path: `${dir}/demo-mobile.png`, fullPage: true });

await b.close();
console.log('screenshots written to', dir);
