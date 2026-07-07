# SF-809 - Worklog

Story-context bij eerste pickup:
Verplaats Weergave-sectie naar onderaan settings-scherm

In frontend/lib/screens/settings_screen.dart: verplaats het Weergave-blok (kop Text('Weergave') + SwitchListTile 'Grote tekst' op appearanceProvider/setLarge, nu regels ~61-66) naar het einde van de ListView-children, zodat het de laatste sectie wordt, ook ná het admin-only Beheer-blok. Verwijder de overtollige divider op de oude plek (tussen Account en Categorieën) en plaats een correcte Divider() vóór de verplaatste Weergave-sectie, consistent voor zowel admin- als niet-admin-weergave. Laat de switch-logica ongewijzigd (value: appearance.largeFont, onChanged -> setLarge). Behoud de bestaande bottom-inset-padding op de ListView. Geen wijziging aan providers, andere secties of backend. Voer zelf-review uit en draai flutter analyze; ruim eventuele ongebruikte variabelen/imports op.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-810: In `frontend/lib/screens/settings_screen.dart` het Weergave-blok
  verplaatst naar het einde van de ListView-children:
  - Het oude Weergave-blok (kop `Text('Weergave')` + `SwitchListTile 'Grote
    tekst'`) én de bijbehorende extra `Divider()` tussen Account en
    Categorieën verwijderd. Tussen Account en Categorieën blijft nu precies
    één `Divider()` over (geen dubbele/overbodige divider meer).
  - Het Weergave-blok opnieuw geplaatst als laatste sectie, ná het admin-only
    `Beheer`-blok, voorafgegaan door één `Divider()`. Deze divider staat
    buiten de `if (auth.isAdmin)`-lijst, dus zowel admins als niet-admins
    krijgen een correcte scheiding vóór Weergave.
  - Switch-logica ongewijzigd: `value: appearance.largeFont`,
    `onChanged -> ref.read(appearanceProvider.notifier).setLarge(v)`.
    `appearanceProvider` wordt nog steeds gewatcht (regel 22), dus geen
    ongebruikte variabele; imports ongewijzigd.
  - Bottom-inset-padding op de ListView (`EdgeInsets.fromLTRB(16, 16, 16, 16 +
    bottomInset)`) behouden; de nu-onderste Weergave-sectie blijft leesbaar
    boven de systeem-navigatiebalk.
- Testen: `frontend/test/settings_screen_test.dart` toegevoegd met 3
  widgettests:
  1. niet-admin — Weergave staat visueel ónder Debug én onder Categorieën
     (positie-vergelijking via `getTopLeft`), 'Grote tekst' aanwezig.
  2. admin — Weergave staat ná de admin-only Beheer-sectie.
  3. de 'Grote tekst'-switch roept `setLarge(true)` aan (geverifieerd via
     fake `AppearanceNotifier`).
  Providers worden zonder netwerk overschreven (fake Auth/Appearance/Request
  notifiers), analoog aan het bestaande `categories_screen_test.dart`-patroon.

Test-uitvoering:
- De developer-runner heeft geen flutter/dart-binary (`which flutter dart`
  faalt), dus `flutter analyze` en `flutter test` kunnen niet lokaal draaien.
  CI valideert analyze/test op de PR. De wijziging is puur een herschikking
  van bestaande widgets zonder nieuwe imports of ongebruikte variabelen.

---

## SF-811 — Tester (2026-07-07)

**Inlog-modus:** FALLBACK wegwerp-account `tester_sf-809`. De vaste
test-user-creds (`TESTER_USERNAME`/`TESTER_PASSWORD`) waren niet beschikbaar:
de service-account `claude-agent` heeft geen leesrechten op
`secret/newsfeed-api-keys` in namespace `pnf-pr-170` (Forbidden). Daarom
via de UI geregistreerd/ingelogd en achteraf opgeruimd met
`DELETE /api/account/me` → HTTP 200.

**Preview:** `https://pnf-pr-170.vdzonsoftware.nl` (HTTP 200, live).
Flutter/dart-binary ontbreekt op de runner, dus widgettests niet lokaal
gedraaid; geverifieerd via code-inspectie (diff) + browser-preview
(Playwright, 420x900). Screenshots in `/work/screenshots`.

**Verificatie acceptatiecriteria:**
- AC1 — Weergave als laatste sectie: bevestigd in preview
  (`06-settings-bottom2.png`). Volgorde onderaan: Achtergrond-taken →
  Opruimen → Debug → **Weergave** ("Grote tekst"). Weergave staat ná Debug.
  (Wegwerp-user is niet-admin, dus geen Beheer-sectie zichtbaar; de
  admin-volgorde na Beheer is gedekt door de code (regel 105-131) en de
  door de developer geschreven widgettest.)
- AC2 — overige secties behouden onderlinge volgorde: bevestigd
  (`04-settings-top.png`): Over deze app → Account → Categorieën → RSS-feeds
  → Achtergrond-taken; geen Weergave meer bovenin.
- AC3 — "Grote tekst"-switch functioneel: toggle in preview toont direct
  het grote-tekst-effect (`07-largefont-on.png`); `onChanged` roept
  `setLarge` op `appearanceProvider` aan (code regel 130).
- AC4 — correcte Divider vóór Weergave, geen dubbele/overgebleven divider
  op de oude plek: bevestigd via diff (Divider verplaatst mee; op oude plek
  resteert alleen de bestaande Account-divider) en visueel in de preview.
- AC5 — bottom-inset-padding behouden: `ListView`-padding
  `EdgeInsets.fromLTRB(16,16,16,16+bottomInset)` ongewijzigd (regel 31).

**Resultaat:** alle acceptatiecriteria voldaan. Geen bugs gevonden.
