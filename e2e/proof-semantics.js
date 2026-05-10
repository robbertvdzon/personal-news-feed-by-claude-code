// Eenmalige proef: kunnen we de Flutter web-app aansturen via de
// accessibility-tree (semantics)? We openen de app, klikken de
// flt-semantics-placeholder om semantics aan te zetten, en kijken
// of we Login-elementen kunnen vinden via aria-label.
//
// Output:
//   /tmp/proof/01-before-semantics.png    canvas, geen semantics-tree
//   /tmp/proof/02-after-semantics.png     na placeholder-click
//   /tmp/proof/03-after-create-account.png na klik op 'Account aanmaken' (als gevonden)
//   stdout: bevindingen per stap
//
// Run: node e2e/proof-semantics.js

const { chromium } = require('playwright');
const fs = require('fs');

const OUT = '/tmp/proof';
fs.mkdirSync(OUT, { recursive: true });

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();

  page.on('console', msg => {
    if (msg.type() === 'error') console.log('[browser-error]', msg.text());
  });

  console.log('[1] open http://localhost:3000');
  await page.goto('http://localhost:3000', { waitUntil: 'networkidle' });

  // Flutter rendert eerst een loading-spinner, dan plant 'ie z'n root
  // (flt-glass-pane / flutter-view) in de body. Wachten tot die er is.
  console.log('[2] wachten op Flutter root');
  await page.waitForSelector('flutter-view, flt-glass-pane', { timeout: 30000 });
  await page.waitForTimeout(1500); // animaties / eerste render

  // DOM-inspectie vóór semantics
  const before = await page.evaluate(() => ({
    bodyChildren: Array.from(document.body.children).map(c => c.tagName.toLowerCase()),
    hasSemanticsHost: !!document.querySelector('flt-semantics-host'),
    semanticsCount: document.querySelectorAll('flt-semantics').length,
    placeholderExists: !!document.querySelector('flt-semantics-placeholder'),
    placeholderRect: (() => {
      const p = document.querySelector('flt-semantics-placeholder');
      return p ? p.getBoundingClientRect().toJSON() : null;
    })(),
  }));
  console.log('[3] vóór semantics:', JSON.stringify(before, null, 2));
  await page.screenshot({ path: `${OUT}/01-before-semantics.png`, fullPage: false });

  // Probeer semantics aan te zetten via de placeholder.
  if (before.placeholderExists) {
    console.log('[4] klik flt-semantics-placeholder');
    // Placeholder is meestal pos: absolute / unsichtbar — gebruik dispatch event
    // omdat .click() kan falen op zero-size elementen. Beide proberen.
    try {
      await page.locator('flt-semantics-placeholder').click({ timeout: 2000, force: true });
    } catch (e) {
      console.log('  .click() faalde, val terug op dispatchEvent');
      await page.evaluate(() => {
        const el = document.querySelector('flt-semantics-placeholder');
        el && el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      });
    }
    await page.waitForTimeout(800);
  } else {
    console.log('[4] geen placeholder gevonden — probeer Flutter-API-call via JS');
    // In sommige Flutter-versies kan dit:
    await page.evaluate(() => {
      // Probeer wat opties:
      if (window.$flutter && window.$flutter.engineInitializer) {
        // alleen nuttig als app nog niet geboot is
      }
      // Manuele toggle bestaat niet als public API, maar sommige builds
      // honoreren een storage-flag:
      try { localStorage.setItem('flutter_semantics_enabled', 'true'); } catch (_) {}
    });
  }

  // DOM-inspectie ná
  const after = await page.evaluate(() => {
    const semElems = Array.from(document.querySelectorAll('flt-semantics'));
    const labelled = semElems
      .map(el => ({
        label: el.getAttribute('aria-label'),
        role: el.getAttribute('role'),
        text: (el.textContent || '').trim().slice(0, 60),
      }))
      .filter(x => x.label || x.role || x.text);
    return {
      hasSemanticsHost: !!document.querySelector('flt-semantics-host'),
      semanticsCount: semElems.length,
      labelled: labelled.slice(0, 30),
    };
  });
  console.log('[5] ná semantics:', JSON.stringify(after, null, 2));
  await page.screenshot({ path: `${OUT}/02-after-semantics.png`, fullPage: false });

  // Probeer 'Account aanmaken' te klikken via aria-label of zichtbare tekst
  console.log('[6] zoek "Account aanmaken"');
  let createAccountFound = false;
  const candidates = [
    'flt-semantics[aria-label*="Account aanmaken" i]',
    'flt-semantics[aria-label*="account" i]',
    'flt-semantics:has-text("Account aanmaken")',
    'text=Account aanmaken',
  ];
  for (const sel of candidates) {
    const count = await page.locator(sel).count().catch(() => 0);
    console.log(`   selector \`${sel}\`  → ${count} match(es)`);
    if (count > 0 && !createAccountFound) {
      try {
        await page.locator(sel).first().click({ timeout: 2000 });
        createAccountFound = true;
        console.log(`   klik gelukt via: ${sel}`);
        await page.waitForTimeout(1000);
        await page.screenshot({ path: `${OUT}/03-after-create-account.png` });
      } catch (e) {
        console.log(`   klik faalde (${e.message.slice(0, 80)})`);
      }
    }
  }

  await browser.close();
  console.log('\n=== SAMENVATTING ===');
  console.log('placeholder gevonden    :', before.placeholderExists);
  console.log('semantics-host vóór     :', before.hasSemanticsHost);
  console.log('semantics-host ná       :', after.hasSemanticsHost);
  console.log('aantal flt-semantics ná :', after.semanticsCount);
  console.log('aantal met aria-label   :', after.labelled.filter(x => x.label).length);
  console.log('"Account aanmaken" klikbaar:', createAccountFound);
  console.log('Screenshots in:', OUT);
})().catch(e => { console.error('FATAL:', e); process.exit(1); });
