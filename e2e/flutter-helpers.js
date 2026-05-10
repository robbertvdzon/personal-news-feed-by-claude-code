// Helpers voor het aansturen van de Flutter web-app via Playwright,
// plus een dunne REST-client voor backend-checks tijdens scenario's.
//
// Hoe Flutter-web automation hier werkt
// -------------------------------------
// Flutter rendert naar één <canvas>. Maar zodra je 'flt-semantics-placeholder'
// een click-event geeft schakelt 'ie de accessibility tree in: een DOM-tree
// van <flt-semantics role="..." aria-label?="...">-elementen. Dáár klikken
// we doorheen — geen coördinaten nodig.
//
// Caveats:
//   - aria-label is vaak null op deze build; tekst zit in textContent.
//   - Container-nodes hebben dezelfde tekst als hun leaves; filter daarom
//     altijd op role (button/textbox/tab/...) anders raak je een parent.
//   - TextField-semantics bestaan ook zonder focus; bij click op de
//     textbox-node creëert Flutter een hidden <input> en routeren we
//     keyboard-events daar naartoe.

const { writeFileSync, existsSync, mkdirSync } = require('fs');
const path = require('path');

// ----------------------------------------------------------------------
// REST helper — handig voor health-checks en queue-polling
// ----------------------------------------------------------------------

class Api {
  constructor(baseUrl = 'http://localhost:8080') {
    this.baseUrl = baseUrl;
    this.token = null;
  }

  async _req(method, path, body) {
    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch (_) {}
    return { status: res.status, body: json ?? text };
  }

  async health() {
    const r = await this._req('GET', '/actuator/health');
    return r.status === 200 && r.body && r.body.status === 'UP';
  }

  async listNewsRequests() {
    const r = await this._req('GET', '/api/requests');
    return r.status === 200 ? r.body : [];
  }

  // Wacht tot een specifiek news-request DONE is (of timeout).
  async waitForRequestDone(matcher, { timeoutMs = 240000, pollMs = 3000 } = {}) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const list = await this.listNewsRequests();
      const item = (list || []).find(matcher);
      if (item && (item.status === 'DONE' || item.status === 'done')) return item;
      if (item && (item.status === 'FAILED' || item.status === 'failed')) {
        throw new Error(`Request faalde: ${JSON.stringify(item)}`);
      }
      await new Promise(r => setTimeout(r, pollMs));
    }
    throw new Error(`Timeout na ${timeoutMs}ms wachten op DONE`);
  }
}

// ----------------------------------------------------------------------
// FlutterApp — wrapper rond Playwright's `page` die met semantics werkt
// ----------------------------------------------------------------------

class FlutterApp {
  constructor(page, screenshotsDir) {
    this.page = page;
    this.dir = screenshotsDir;
    if (!existsSync(this.dir)) mkdirSync(this.dir, { recursive: true });
    this.shotIdx = 0;
  }

  // ---- lifecycle ----

  async open(url) {
    await this.page.goto(url, { waitUntil: 'domcontentloaded' });
    // Eerst de Flutter root, dan even tijd voor de eerste paint/animatie.
    await this.page.waitForSelector('flutter-view, flt-glass-pane', { timeout: 30000 });
    await this.page.waitForTimeout(2000);
    await this.enableSemantics();
  }

  async enableSemantics() {
    // Placeholder is 1×1 op (-1,-1) — een echte .click() schiet eroverheen.
    // dispatchEvent met bubbles werkt wel.
    await this.page.evaluate(() => {
      const p = document.querySelector('flt-semantics-placeholder');
      if (p) p.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    // Even wachten tot Flutter de tree opbouwt.
    await this.page.waitForTimeout(600);
  }

  // ---- queries ----

  // Lever de inhoud van de semantics-tree als platte lijst leaves.
  async dumpSemantics() {
    return await this.page.evaluate(() => {
      const all = Array.from(document.querySelectorAll('flt-semantics'));
      // Leaves = nodes die geen <flt-semantics>-kinderen hebben.
      const leaves = all.filter(el => el.querySelector('flt-semantics') === null);
      return leaves.map(el => ({
        role: el.getAttribute('role'),
        label: el.getAttribute('aria-label'),
        text: (el.textContent || '').trim().slice(0, 120),
      }));
    });
  }

  // ---- interactions ----

  // Klik op het eerste leaf-semantics element met de gegeven tekst (of
  // role+text). `role` is optioneel maar STERK aanbevolen — zonder loop
  // je risico op parent-containers.
  //
  // BELANGRIJK: Playwright's mouse.click / element.click via locator
  // triggert de Flutter handler NIET. Flutter luistert op DOM
  // click-events op de flt-semantics elementen, niet op gesynthetiseerde
  // mouse-events bij coordinates. We dispatchen daarom direct via
  // page.evaluate(el => el.click()).
  async click(text, { role = null, timeout = 8000, nth = 0 } = {}) {
    const fired = await this.page.waitForFunction(
      ({ text, role, nth }) => {
        const all = Array.from(document.querySelectorAll('flt-semantics'))
          .filter(el => el.querySelector('flt-semantics') === null);
        const matches = all.filter(el => {
          if (role && el.getAttribute('role') !== role) return false;
          const t = (el.textContent || '').trim();
          const lbl = el.getAttribute('aria-label') || '';
          // Exact-match heeft voorrang; voorkomt dat "Alles" op
          // "Alles als gelezen" zou matchen.
          if (t === text || lbl === text) return true;
          return t.includes(text) || lbl.includes(text);
        });
        // Sorteer op exact-match eerst, dan op kortste tekst (= meest specifiek)
        matches.sort((a, b) => {
          const exA = (a.textContent || '').trim() === text || a.getAttribute('aria-label') === text;
          const exB = (b.textContent || '').trim() === text || b.getAttribute('aria-label') === text;
          if (exA && !exB) return -1;
          if (!exA && exB) return 1;
          return ((a.textContent || '').length || 0) - ((b.textContent || '').length || 0);
        });
        const target = matches[nth];
        if (!target) return false;
        target.click();
        return true;
      },
      { text, role, nth },
      { timeout }
    ).catch(() => null);
    if (!fired) throw new Error(`click: niet gevonden — text="${text}" role=${role} nth=${nth}`);
  }

  // Klik op een knop puur op role (handig voor icon-only buttons als er
  // maar één is) — meegegeven `index` selecteert welke role=button leaf.
  async clickRole(role, index = 0, { timeout = 8000 } = {}) {
    const fired = await this.page.waitForFunction(
      ({ role, index }) => {
        const all = Array.from(document.querySelectorAll(`flt-semantics[role="${role}"]`))
          .filter(el => el.querySelector('flt-semantics') === null);
        const t = all[index];
        if (!t) return false;
        t.click();
        return true;
      },
      { role, index },
      { timeout }
    ).catch(() => null);
    if (!fired) throw new Error(`clickRole: index=${index} role=${role} niet gevonden`);
  }

  // Vul een tekstveld via index. Flutter's TextField rendert in semantics
  // als <flt-semantics><input/></flt-semantics> (of <textarea> bij multi-line).
  // We klikken op de Nth zo'n flt-semantics, focus de input, en typen.
  async fillByIndex(index, value, { timeout = 8000, multiline = false } = {}) {
    const found = await this.page.waitForFunction(
      ({ index, multiline }) => {
        const tag = multiline ? 'textarea' : 'input';
        const fields = Array.from(document.querySelectorAll('flt-semantics'))
          .filter(el => el.firstElementChild && el.firstElementChild.tagName.toLowerCase() === tag);
        const t = fields[index];
        if (!t) return false;
        t.click();
        const inp = t.firstElementChild;
        inp.focus();
        return true;
      },
      { index, multiline },
      { timeout }
    ).catch(() => null);
    if (!found) throw new Error(`fillByIndex: index=${index} niet gevonden (multiline=${multiline})`);
    await this.page.waitForTimeout(150);
    await this.page.keyboard.press('Control+A').catch(() => {});
    await this.page.keyboard.press('Delete').catch(() => {});
    await this.page.keyboard.type(value, { delay: 25 });
    // Blur de field zodat Flutter de waarde naar z'n controller flush't.
    await this.page.evaluate(() => document.activeElement && document.activeElement.blur());
    await this.page.waitForTimeout(200);
  }

  // Convenience: vul achter elkaar een aantal velden in. inputs is een
  // array; gebruik 'tab' om naar het volgende veld te gaan zonder klik.
  async fillFields(values) {
    for (let i = 0; i < values.length; i++) {
      await this.fillByIndex(i, values[i]);
    }
  }

  // Wacht tot een tekst zichtbaar is in de semantics-tree.
  async expectText(text, { role = null, timeout = 10000 } = {}) {
    const sel = this._buildSelector(text, role, false);
    await this.page.locator(sel).first().waitFor({ state: 'attached', timeout });
  }

  // Levert true als de tekst aanwezig is, false als hij binnen `timeout` niet komt.
  async hasText(text, { role = null, timeout = 1500 } = {}) {
    try {
      await this.expectText(text, { role, timeout });
      return true;
    } catch (_) {
      return false;
    }
  }

  // Eenvoudige back-press (browser-back). Werkt voor Flutter Navigator-routes.
  async back() {
    await this.page.goBack({ waitUntil: 'domcontentloaded' }).catch(() => {});
    await this.page.waitForTimeout(400);
  }

  // ---- screenshots ----

  async shot(label) {
    this.shotIdx += 1;
    const name = `${String(this.shotIdx).padStart(3, '0')}-${label.replace(/[^\w-]+/g, '-')}.png`;
    const file = path.join(this.dir, name);
    await this.page.screenshot({ path: file, fullPage: false });
    return name;
  }

  // ---- internals ----

  _buildSelector(text, role, exact) {
    const t = exact
      ? `text="${text}"`   // Playwright exact text match
      : `text=${text}`;
    if (role) {
      return `flt-semantics[role="${role}"]:has-text(${JSON.stringify(text)})`;
    }
    return `flt-semantics:has-text(${JSON.stringify(text)})`;
  }
}

module.exports = { FlutterApp, Api };
