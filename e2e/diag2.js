// Vergelijk drie click-strategieën op de "Account aanmaken" toggle
// link, om te zien welke de Flutter-handler triggert.

const { chromium } = require('playwright');

async function setup() {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 }, hasTouch: true })).newPage();
  await page.goto('http://localhost:3000', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('flutter-view, flt-glass-pane');
  await page.waitForTimeout(2000);
  await page.evaluate(() => {
    const p = document.querySelector('flt-semantics-placeholder');
    p && p.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
  await page.waitForTimeout(800);
  return { browser, page };
}

async function getToggleRect(page) {
  return await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll('flt-semantics[role="button"]'))
      .filter(el => el.querySelector('flt-semantics') === null);
    const btn = btns.find(b => b.textContent.trim() === 'Account aanmaken');
    if (!btn) return null;
    const r = btn.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  });
}

async function isInRegisterMode(page) {
  // In register-mode is de TextButton-tekst "Heb je al een account? Inloggen"
  return await page.evaluate(() => {
    return Array.from(document.querySelectorAll('flt-semantics'))
      .some(el => (el.textContent || '').includes('Heb je al een account'));
  });
}

async function tryClickStrategy(name, fn, label) {
  const { browser, page } = await setup();
  const rect = await getToggleRect(page);
  if (!rect) { console.log(`[${name}] kon button niet vinden`); await browser.close(); return; }
  console.log(`[${name}] click op (${rect.x|0}, ${rect.y|0})`);
  await fn(page, rect);
  await page.waitForTimeout(700);
  const toggled = await isInRegisterMode(page);
  console.log(`[${name}] toggle gefired: ${toggled}`);
  await page.screenshot({ path: `/tmp/diag-${label}.png` });
  await browser.close();
}

(async () => {
  // 1. page.mouse.click — al bekend mislukt, maar reproduceer ter controle
  await tryClickStrategy('mouse.click', async (page, r) => {
    await page.mouse.click(r.x, r.y);
  }, 'mouse');

  // 2. page.touchscreen.tap — synthese als touch event
  await tryClickStrategy('touchscreen.tap', async (page, r) => {
    await page.touchscreen.tap(r.x, r.y);
  }, 'touch');

  // 3. JS el.click() op de flt-semantics
  await tryClickStrategy('js-element.click', async (page, _r) => {
    await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('flt-semantics[role="button"]'))
        .filter(el => el.querySelector('flt-semantics') === null);
      const btn = btns.find(b => b.textContent.trim() === 'Account aanmaken');
      btn && btn.click();
    });
  }, 'js');

  // 4. Echte PointerEvent dispatch op flt-glass-pane
  await tryClickStrategy('pointerEvent on glass', async (page, r) => {
    await page.evaluate(({ x, y }) => {
      const target = document.querySelector('flt-glass-pane') || document.querySelector('flutter-view');
      const opts = { bubbles: true, cancelable: true, clientX: x, clientY: y, pointerType: 'mouse', pointerId: 1, isPrimary: true };
      target.dispatchEvent(new PointerEvent('pointerdown', opts));
      target.dispatchEvent(new PointerEvent('pointerup', opts));
      target.dispatchEvent(new MouseEvent('click', opts));
    }, r);
  }, 'pointer');

  // 5. mouse.move + mouse.down + mouse.up (zonder click()-helper)
  await tryClickStrategy('manual down/up', async (page, r) => {
    await page.mouse.move(r.x, r.y);
    await page.waitForTimeout(50);
    await page.mouse.down();
    await page.waitForTimeout(80);
    await page.mouse.up();
  }, 'manual');
})();
