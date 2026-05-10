// Inspecteer localStorage en de token-key na een register-flow.
const { chromium } = require('playwright');
const { FlutterApp } = require('./flutter-helpers');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 }, hasTouch: true })).newPage();
  const app = new FlutterApp(page, '/tmp/diagtok');
  await app.open('http://localhost:3000');

  const u = `e2e_diagtok_${Date.now()}`;
  await app.click('Account aanmaken', { role: 'button' });
  await page.waitForTimeout(500);
  await app.fillByIndex(0, u);
  await app.fillByIndex(1, 'pw');
  await app.click('Account aanmaken', { role: 'button' });
  await page.waitForTimeout(2500);

  const all = await page.evaluate(() => {
    const out = {};
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      out[k] = (localStorage.getItem(k) || '').slice(0, 40);
    }
    return out;
  });
  console.log('localStorage keys:');
  console.log(JSON.stringify(all, null, 2));

  // Probeer ook IndexedDB (shared_preferences gebruikt soms idb)
  const idb = await page.evaluate(async () => {
    const dbs = await indexedDB.databases();
    return dbs.map(d => d.name);
  }).catch(() => []);
  console.log('IndexedDB databases:', idb);

  await browser.close();
})();
