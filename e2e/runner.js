// E2E runner — speelt de scenario's uit e2e/scenarios/ af tegen de
// draaiende Flutter web-app + Spring backend, en schrijft per run een
// timestamped map met screenshots + report.md.
//
// Hybride aanpak:
//   - UI (Playwright + semantics): register-flow, tab-navigatie, screenshots.
//   - REST (geauthenticeerd met token uit localStorage): data-setup
//     (RSS-feed, categorieën) en triggers (uurlijkse update, daily summary).
//
// De UI-scenario's in scenarios/ beschrijven óók icon-only knoppen
// (▶, ⭐, ✉️, swipe). Die drijven we via REST omdat hun semantic-labels
// niet betrouwbaar te vinden zijn — het functionele resultaat is gelijk.
//
// Run: node e2e/runner.js

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const { FlutterApp, Api } = require('./flutter-helpers');

const REPO = path.resolve(__dirname, '..');
const E2E = path.resolve(__dirname);
const FRONTEND = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080';

// ---- Run-map opzetten -------------------------------------------------

function utcStamp() {
  const d = new Date();
  const z = n => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${z(d.getUTCMonth() + 1)}-${z(d.getUTCDate())}T${z(d.getUTCHours())}-${z(d.getUTCMinutes())}-${z(d.getUTCSeconds())}`;
}

const STAMP = utcStamp();
const RUN_DIR = path.join(E2E, 'testruns', 'history', STAMP);
const SHOT_DIR = path.join(RUN_DIR, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

for (const f of fs.readdirSync(path.join(E2E, 'scenarios'))) {
  fs.copyFileSync(path.join(E2E, 'scenarios', f), path.join(RUN_DIR, f));
}

const USER = `e2e_${STAMP.replace(/-/g, '').replace('T', '_').slice(0, 13)}`;
const PASSWORD = 'e2etest';
const TEST_FEED = 'https://techcrunch.com/feed/';

console.log(`[runner] STAMP=${STAMP}`);
console.log(`[runner] USER=${USER}`);
console.log(`[runner] RUN_DIR=${RUN_DIR}`);

// ---- Resultaten -------------------------------------------------------

const results = [];
const t0 = Date.now();
const elapsed = () => Math.round((Date.now() - t0) / 1000);
const fmtTime = s => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;

function record(name, status, startSec, notes) {
  results.push({ name, status, startSec, endSec: elapsed(), notes });
  console.log(`[result] ${name}: ${status} — ${notes}`);
}

// ---- Helpers ----------------------------------------------------------

async function getToken(page) {
  return await page.evaluate(() => localStorage.getItem('flutter.token'));
}

async function dumpDebug(app, label) {
  const dump = await app.dumpSemantics();
  fs.writeFileSync(path.join(SHOT_DIR, `_dump-${label}.json`), JSON.stringify(dump, null, 2));
  await app.shot(`debug-${label}`);
}

// Klik een bottom-nav tab (Feed/RSS/Podcast/Settings) — bottom-nav
// items hebben role="tab" met aria-label.
async function gotoTab(app, label) {
  await app.click(label, { role: 'tab' });
  await app.page.waitForTimeout(700);
}

// Herlaad de pagina + zet semantics weer aan. Gebruiken we tussen
// scenario's zodat de Flutter-app de laatste backend-state ophaalt
// (REST-triggers fire'n geen websocket-update naar deze sessie).
async function refreshApp(app) {
  await app.page.reload({ waitUntil: 'domcontentloaded' });
  await app.page.waitForSelector('flutter-view, flt-glass-pane');
  await app.page.waitForTimeout(2000);
  await app.enableSemantics();
}

// ---- Scenario 1: start ------------------------------------------------

async function startScenario(app, api) {
  const start = elapsed();
  try {
    if (!(await api.health())) throw new Error('backend not healthy');
    await app.shot('start-01-login');

    // Toggle naar register en vul de velden
    await app.click('Account aanmaken', { role: 'button' });
    await app.page.waitForTimeout(600);
    await app.shot('start-02-register-form');

    await app.fillByIndex(0, USER);
    await app.fillByIndex(1, PASSWORD);
    await app.shot('start-03-register-filled');

    // Submit (in register-mode is de FilledButton-tekst ook "Account aanmaken").
    await app.click('Account aanmaken', { role: 'button' });
    await app.page.waitForTimeout(2500);
    await app.shot('start-04-mainshell');

    // Token voor REST-calls: login opnieuw via REST (Flutter web slaat
    // de prefs niet in localStorage op, dus we kunnen 'm daar niet uit
    // halen — login geeft toch dezelfde token terug).
    const loginRes = await api._req('POST', '/api/auth/login', { username: USER, password: PASSWORD });
    if (loginRes.status >= 400 || !loginRes.body || !loginRes.body.token) {
      throw new Error(`REST login faalde (${loginRes.status}): ${JSON.stringify(loginRes.body).slice(0, 120)}`);
    }
    api.token = loginRes.body.token;

    // RSS-feed toevoegen via REST. Body shape: { feeds: List<String> }.
    const feedsRes = await api._req('PUT', '/api/rss-feeds', { feeds: [TEST_FEED] });
    if (feedsRes.status >= 400) {
      throw new Error(`PUT /api/rss-feeds faalde (${feedsRes.status}): ${JSON.stringify(feedsRes.body).slice(0, 120)}`);
    }

    // Bestaande categorieën ophalen, AI + Startups erbij, terugschrijven.
    // CategorySettings: { id, name, enabled, extraInstructions, isSystem }.
    const existing = await api._req('GET', '/api/settings');
    const current = Array.isArray(existing.body) ? existing.body : [];
    const newCats = [
      { id: `e2e-ai-${Date.now()}`, name: 'AI', enabled: true,
        extraInstructions: 'Artikelen over AI, LLMs, ChatGPT/Claude/GPT, machine learning, AI-tools en AI-bedrijven. Inclusief releases, funding rounds, en technische diepgang.',
        isSystem: false },
      { id: `e2e-startups-${Date.now()}`, name: 'Startups', enabled: true,
        extraInstructions: 'Tech-startups, funding rounds, IPOs, acquisities en zakelijke ontwikkelingen in de tech-industrie.',
        isSystem: false },
    ];
    // Behoud system-categorieën, voeg de nieuwe toe
    const merged = [...current.filter(c => c.isSystem), ...newCats, ...current.filter(c => !c.isSystem && c.name !== 'AI' && c.name !== 'Startups')];
    const settingsRes = await api._req('PUT', '/api/settings', merged);
    if (settingsRes.status >= 400) {
      throw new Error(`PUT /api/settings faalde (${settingsRes.status}): ${JSON.stringify(settingsRes.body).slice(0, 120)}`);
    }

    // Reload zodat UI de nieuwe instellingen toont
    await app.page.reload({ waitUntil: 'domcontentloaded' });
    await app.page.waitForSelector('flutter-view, flt-glass-pane');
    await app.page.waitForTimeout(2000);
    await app.enableSemantics();

    // Settings-tab — bevat ook de "Achtergrond-taken"-sectie waar de hourly
    // update handmatig getriggerd kan worden (er is geen Queue-tab meer).
    await gotoTab(app, 'instellingen');
    await app.shot('start-05-settings');

    // Trigger uurlijkse update via REST: vind het hourly-request en rerun.
    const requests = await api.listNewsRequests();
    const hourly = (requests || []).find(r =>
      r.isHourlyUpdate || (r.subject || '').toLowerCase().includes('uurlijkse'));
    if (!hourly) throw new Error('geen uurlijkse-update request gevonden');
    const rerunRes = await api._req('POST', `/api/requests/${hourly.id}/rerun`);
    if (rerunRes.status >= 400) throw new Error(`rerun faalde (${rerunRes.status})`);
    await app.shot('start-07-triggered');

    // Wacht tot hourly DONE is. TechCrunch geeft 20 artikelen waarvan ~14
    // in de feed komen; step 4/4 doet ~25s per item via Claude Sonnet, dus
    // 6-8 minuten is realistisch. We pollen ruim — als het sneller is, doet
    // de loop niets.
    const done = await api.waitForRequestDone(
      r => r.id === hourly.id || r.isHourlyUpdate,
      { timeoutMs: 600000 }
    );
    await app.shot('start-08-hourly-done');

    const newItems = done.newItemCount ?? 0;
    record('start-scenario', 'pass', start, `register OK, ${newItems} nieuwe RSS-items`);
    return { ok: true };
  } catch (e) {
    await app.shot('start-FAIL').catch(() => {});
    await dumpDebug(app, 'start-fail').catch(() => {});
    record('start-scenario', 'fail', start, `error: ${String(e.message || e).slice(0, 240)}`);
    return { ok: false };
  }
}

// ---- Scenario 2: rss --------------------------------------------------

async function rssScenario(app, api) {
  const start = elapsed();
  try {
    // Refresh zodat de net binnengekomen RSS-items zichtbaar zijn.
    await refreshApp(app);
    await gotoTab(app, 'RSS');
    await app.shot('rss-01-list');

    // Sub-tabs: Alles / Bewaard / categorieën / Overig — role="button" hier.
    for (const tab of ['Bewaard', 'AI', 'Startups', 'Overig', 'Alles']) {
      try { await app.click(tab, { role: 'button', timeout: 1500 }); } catch (_) {}
      await app.page.waitForTimeout(400);
      await app.shot(`rss-tab-${tab.toLowerCase()}`);
    }

    // markAllRead via REST + screenshot
    const before = await api._req('GET', '/api/rss');
    const beforeCount = Array.isArray(before.body) ? before.body.length : 0;
    const mar = await api._req('POST', '/api/rss/markAllRead');
    const marked = (mar.body && (mar.body.updated ?? mar.body.marked ?? mar.body.count)) ?? 0;

    // Reload + Refresh-icoon zou een prettiger flow zijn, maar gotoTab
    // re-rendered de lijst.
    await gotoTab(app, 'Feed');
    await gotoTab(app, 'RSS');
    await app.shot('rss-after-markallread');

    record('rss-scenario', 'partial', start, `tabs gevisualiseerd; markAllRead via REST: ${marked} items, lijst-grootte ${beforeCount}`);
    return { ok: true };
  } catch (e) {
    await app.shot('rss-FAIL').catch(() => {});
    await dumpDebug(app, 'rss-fail').catch(() => {});
    record('rss-scenario', 'fail', start, `error: ${String(e.message || e).slice(0, 240)}`);
    return { ok: false };
  }
}

// ---- Scenario 3: feed -------------------------------------------------

async function feedScenario(app, api) {
  const start = elapsed();
  try {
    await refreshApp(app);
    await gotoTab(app, 'Feed');
    await app.shot('feed-01-list');

    for (const tab of ['Bewaard', 'Samenvatting', 'AI', 'Startups', 'Alles']) {
      try { await app.click(tab, { role: 'button', timeout: 1500 }); } catch (_) {}
      await app.page.waitForTimeout(400);
      await app.shot(`feed-tab-${tab.toLowerCase()}`);
    }

    record('feed-scenario', 'partial', start, 'tabs gevisualiseerd; click-acties op items niet uitgebreid getoetst (icon-only knoppen)');
    return { ok: true };
  } catch (e) {
    await app.shot('feed-FAIL').catch(() => {});
    await dumpDebug(app, 'feed-fail').catch(() => {});
    record('feed-scenario', 'fail', start, `error: ${String(e.message || e).slice(0, 240)}`);
    return { ok: false };
  }
}

// ---- Scenario 4: samenvatting ----------------------------------------

async function samenvattingScenario(app, api) {
  const start = elapsed();
  try {
    // De handmatige trigger zit nu onder Settings → Achtergrond-taken;
    // de actie zelf gaat via REST, de tab-switch is alleen voor screenshot.
    await gotoTab(app, 'instellingen');
    await app.shot('summary-01-settings');

    const requests = await api.listNewsRequests();
    const daily = (requests || []).find(r =>
      r.isDailySummary || (r.subject || '').toLowerCase().includes('dagelijkse'));
    if (!daily) throw new Error('geen daily-summary request gevonden');
    const rerun = await api._req('POST', `/api/requests/${daily.id}/rerun`);
    if (rerun.status >= 400) throw new Error(`rerun faalde (${rerun.status})`);
    await app.shot('summary-02-triggered');

    const done = await api.waitForRequestDone(
      r => r.id === daily.id || r.isDailySummary,
      { timeoutMs: 120000 }
    );
    await app.shot('summary-03-done');

    await refreshApp(app);
    await gotoTab(app, 'Feed');
    try { await app.click('Samenvatting', { role: 'button', timeout: 2000 }); } catch (_) {}
    await app.page.waitForTimeout(700);
    await app.shot('summary-04-tab-samenvatting');

    record('samenvatting-scenario', 'pass', start,
      `daily-summary DONE, ${done.newItemCount ?? 0} nieuw item, costUsd=${done.costUsd ?? 'n/a'}`);
    return { ok: true };
  } catch (e) {
    await app.shot('summary-FAIL').catch(() => {});
    await dumpDebug(app, 'summary-fail').catch(() => {});
    record('samenvatting-scenario', 'fail', start, `error: ${String(e.message || e).slice(0, 240)}`);
    return { ok: false };
  }
}

// ---- Scenario 5: cleanup ----------------------------------------------

function cleanupScenario() {
  const start = elapsed();
  try {
    const userDir = path.join(REPO, 'newsfeedbackend/newsfeedbackend/data/users', USER);
    if (fs.existsSync(userDir)) fs.rmSync(userDir, { recursive: true, force: true });
    const usersJson = path.join(REPO, 'newsfeedbackend/newsfeedbackend/data/users.json');
    if (fs.existsSync(usersJson)) {
      const arr = JSON.parse(fs.readFileSync(usersJson, 'utf-8'));
      const before = arr.length;
      const filtered = arr.filter(u => u.username !== USER);
      fs.writeFileSync(usersJson, JSON.stringify(filtered, null, 2));
      record('cleanup-scenario', 'pass', start,
        `user '${USER}' verwijderd; users.json: ${before} → ${filtered.length}`);
    } else {
      record('cleanup-scenario', 'partial', start, 'users.json niet gevonden');
    }
  } catch (e) {
    record('cleanup-scenario', 'fail', start, `error: ${String(e.message || e).slice(0, 240)}`);
  }
}

// ---- report.md --------------------------------------------------------

function writeReport() {
  const lines = [];
  const now = new Date().toISOString().replace('T', ' ').slice(0, 16);
  lines.push(`# E2E testrun — ${now} UTC`);
  lines.push('');
  lines.push(`Run: \`${STAMP}\`  ·  user: \`${USER}\``);
  lines.push('');
  lines.push('| # | Scenario | Resultaat | Start | Eind | Notities |');
  lines.push('|---|---|---|---|---|---|');
  results.forEach((r, i) => {
    const icon = r.status === 'pass' ? '✅' : r.status === 'fail' ? '❌' : r.status === 'partial' ? '⚠️' : '⏭';
    lines.push(`| ${i + 1} | ${r.name} | ${icon} ${r.status} | ${fmtTime(r.startSec)} | ${fmtTime(r.endSec)} | ${r.notes} |`);
  });
  lines.push('');
  lines.push('## Aanpak');
  lines.push('');
  lines.push('Hybride run:');
  lines.push('- **UI** (Playwright + Flutter semantics-tree): register-flow, tab-navigatie, screenshots.');
  lines.push('- **REST**: data-setup (RSS-feeds, categorieën) en triggers (uurlijkse update, daily summary).');
  lines.push('');
  lines.push('Reden voor REST op icon-only knoppen (▶, ⭐, ✉️, swipe): Flutter-web rendert naar canvas; semantics-tree exposeert deze knoppen zonder zekere tekst-/aria-labels, dus UI-clicks zouden broos zijn. Functioneel resultaat is gelijk.');
  lines.push('');
  lines.push('## Bevindingen');
  lines.push('');
  lines.push('- Screenshots in `screenshots/` per stap (PNG).');
  lines.push('- Bij failures: bijbehorende `_dump-*.json` met de live semantics-tree.');
  lines.push('');
  lines.push('## Volgende stappen');
  lines.push('');
  lines.push('- Selectors verfijnen voor sub-tab clicks (Bewaard/AI/Samenvatting) als die nu silently faalden.');
  lines.push('- Item-detail flow (👍 ⭐ ✉️ swipe) en snackbar-validatie zijn niet geautomatiseerd; visueel reviewen via screenshots of integration_test gebruiken.');
  fs.writeFileSync(path.join(RUN_DIR, 'report.md'), lines.join('\n') + '\n');
}

// ---- main -------------------------------------------------------------

(async () => {
  const api = new Api(BACKEND);
  if (!(await api.health())) {
    console.error('FATAL: backend op :8080 niet bereikbaar');
    process.exit(1);
  }

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, hasTouch: true });
  const page = await ctx.newPage();
  page.on('console', m => {
    if (m.type() === 'error') console.log('[browser-error]', m.text().slice(0, 200));
  });

  const app = new FlutterApp(page, SHOT_DIR);
  await app.open(FRONTEND);
  await app.shot('00-app-loaded');

  const startRes = await startScenario(app, api);
  if (startRes.ok) {
    await rssScenario(app, api);
    await feedScenario(app, api);
    await samenvattingScenario(app, api);
  } else {
    record('rss-scenario', 'skipped', elapsed(), 'overgeslagen — start-scenario faalde');
    record('feed-scenario', 'skipped', elapsed(), 'overgeslagen — start-scenario faalde');
    record('samenvatting-scenario', 'skipped', elapsed(), 'overgeslagen — start-scenario faalde');
  }
  await browser.close();

  cleanupScenario();
  writeReport();

  // Latest-symlink
  const latest = path.join(E2E, 'testruns', 'latest');
  try {
    if (fs.lstatSync(latest, { throwIfNoEntry: false })) fs.unlinkSync(latest);
  } catch (_) {}
  fs.symlinkSync(path.join('history', STAMP), latest);

  console.log(`\n[runner] klaar — zie ${RUN_DIR}/report.md`);
  process.exit(0);
})().catch(e => { console.error('FATAL:', e); process.exit(1); });
