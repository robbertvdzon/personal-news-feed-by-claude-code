// Verifieer dat de nieuwe helper het volledige register-pad af kan:
// open → toggle → fillByIndex(0,1) → submit → MainShell zichtbaar.

const { chromium } = require('playwright');
const { FlutterApp } = require('./flutter-helpers');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, hasTouch: true });
  const page = await ctx.newPage();
  page.on('console', m => m.type() === 'error' && console.log('[err]', m.text().slice(0, 200)));

  const app = new FlutterApp(page, '/tmp/diag3');
  await app.open('http://localhost:3000');
  await app.shot('01-loaded');

  console.log('[1] toggle naar register-mode');
  await app.click('Account aanmaken', { role: 'button' });
  await page.waitForTimeout(800);
  await app.shot('02-toggled');

  // Verifieer toggle
  const inRegisterMode = await page.evaluate(() =>
    Array.from(document.querySelectorAll('flt-semantics'))
      .some(el => (el.textContent || '').includes('Heb je al een account')));
  console.log('  registerMode=', inRegisterMode);

  console.log('[2] fillByIndex 0 (gebruikersnaam) en 1 (wachtwoord)');
  const stamp = `e2e_diag_${Date.now()}`;
  await app.fillByIndex(0, stamp);
  await app.shot('03-username-filled');
  await app.fillByIndex(1, 'pw123');
  await app.shot('04-password-filled');

  console.log('[3] submit (tweede "Account aanmaken")');
  // Met registerMode=true heeft de submit-knop tekst "Account aanmaken"
  // en de toggle-link is "Heb je al een account? Inloggen".
  await app.click('Account aanmaken', { role: 'button' });
  await page.waitForTimeout(2500);
  await app.shot('05-after-submit');

  // Check of we naar MainShell zijn — die heeft een bottom-nav met
  // tabs zoals "Feed", "RSS", "Settings", "Queue".
  const inApp = await page.evaluate(() =>
    Array.from(document.querySelectorAll('flt-semantics'))
      .some(el => (el.textContent || '').includes('Feed')));
  console.log('  in MainShell=', inApp);

  await browser.close();
  console.log('username gebruikt:', stamp);
})().catch(e => { console.error(e); process.exit(1); });
