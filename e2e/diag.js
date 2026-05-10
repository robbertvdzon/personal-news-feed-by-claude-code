// Diagnose: open Login-screen, semantics aan, dump ALLE flt-semantics
// (niet alleen leaves) + de role-attributen van TextFields.

const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
  await page.goto('http://localhost:3000', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('flutter-view, flt-glass-pane');
  await page.waitForTimeout(2000);
  await page.evaluate(() => {
    const p = document.querySelector('flt-semantics-placeholder');
    p && p.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
  await page.waitForTimeout(800);

  const all = await page.evaluate(() => {
    return Array.from(document.querySelectorAll('flt-semantics')).map((el, i) => {
      const r = el.getBoundingClientRect();
      return {
        i,
        role: el.getAttribute('role'),
        ariaLabel: el.getAttribute('aria-label'),
        flags: Array.from(el.attributes).filter(a => a.name.startsWith('aria-')).map(a => `${a.name}=${a.value}`),
        text: (el.textContent || '').trim().slice(0, 40),
        leaf: el.querySelector('flt-semantics') === null,
        rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
        tagName: el.firstElementChild?.tagName?.toLowerCase() || null,
        innerHtml: el.innerHTML.slice(0, 80),
      };
    });
  });

  // En probeer eerste textbox te focussen en kijk wat er gebeurt
  console.log(`Totaal flt-semantics: ${all.length}`);
  for (const e of all) {
    console.log(`#${String(e.i).padStart(2)} role=${(e.role||'-').padEnd(10)} leaf=${e.leaf?'Y':'N'} rect=${e.rect.x},${e.rect.y} ${e.rect.w}x${e.rect.h} tag=${e.tagName} text="${e.text}"`);
  }

  // Probeer click via mouse.click op de "Account aanmaken" button (laatste role=button volgens dump)
  const btn = all.find(e => e.role === 'button' && e.text === 'Account aanmaken' && e.leaf);
  if (btn) {
    console.log(`\n[mouse.click] op (${btn.rect.x + btn.rect.w/2|0}, ${btn.rect.y + btn.rect.h/2|0})`);
    await page.mouse.click(btn.rect.x + btn.rect.w / 2, btn.rect.y + btn.rect.h / 2);
    await page.waitForTimeout(800);
    await page.screenshot({ path: '/tmp/diag-after-toggle.png' });

    // Nieuwe dump na toggle
    const after = await page.evaluate(() => {
      const leaves = Array.from(document.querySelectorAll('flt-semantics'))
        .filter(el => el.querySelector('flt-semantics') === null);
      return leaves.map(el => ({
        role: el.getAttribute('role'),
        text: (el.textContent || '').trim().slice(0, 40),
      }));
    });
    console.log('\nNa toggle (leaves):');
    after.forEach((e, i) => console.log(`  #${i} role=${e.role||'-'} text="${e.text}"`));
  }

  await browser.close();
})();
