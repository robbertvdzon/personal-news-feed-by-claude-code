# SF-559 — E2e-scenario's events, podcast en settings toevoegen

Onderdeel van story **SF-558** (nightly: integratietests — ontbrekende scenario's toevoegen).

Breidt de markdown-e2e-suite in `e2e/scenarios/` uit met drie nieuwe, afspeelbare scenario's
voor de Flutter web-flows die nog niet gedekt waren: **Events**, **Podcasts** en **Settings/
Achtergrond-taken**. Conform `e2e/readme.md` (Doel / Voorwaarden / Stappen / Verwacht resultaat,
in NL, met een ⚠️ Partial graceful-degradation-sectie analoog aan `feed-scenario.md`). Uitsluitend
de e2e-/testlaag is aangepast; geen productiecode of migraties.

## Stappenplan

- [x] Issue + factory-docs + bestaande scenario's en `e2e/readme.md` lezen.
- [x] Per screen de feitelijke UI verifiëren in `frontend/lib/screens/` (geen niet-bestaande UI beschrijven).
- [x] `events-scenario.md` schrijven (lijst, detail, verwijderen via prullenbak, denylist-verificatie).
- [x] `podcast-scenario.md` schrijven (aanmaken, status volgen, detail, afspelen).
- [x] `settings-scenario.md` schrijven (categorieën, RSS-/podcast-feeds-editor, Achtergrond-taken).
- [x] `e2e/readme.md` bijwerken (mappenstructuur + run-volgorde).
- [x] Backend unit-tests draaien (`mvn test`) — moeten ongewijzigd groen blijven.
- [x] Worklog + dit story-log bijwerken.

## Wat is gedaan

- **UI-verificatie vooraf** via de echte screens: `main_shell.dart`, `events_screen.dart`,
  `event_detail_screen.dart`, `podcast_screen.dart`, `podcast_detail_screen.dart`,
  `settings_screen.dart`, `rss_feeds_screen.dart`. Alle scenario-stappen citeren letterlijke
  UI-tekst (knoplabels, snackbars, sectiekoppen, chips). Er is **geen** Settings-UI voor
  event-preferences/denylist beschreven — denylist is backend-gedrag dat optreedt bij het
  verwijderen van een event.
- **Drie nieuwe bestanden** in `e2e/scenarios/`: `events-scenario.md`, `podcast-scenario.md`,
  `settings-scenario.md`. Elk met concrete, verifieerbare checks (UI-tekst, aantallen,
  navigatiegedrag) en een ⚠️ Partial-uitkomst voor AI/Tavily-afhankelijke of lege-data-stappen.
- **`e2e/readme.md`** uitgebreid met de drie scenario's in de mappenstructuur-boom en de
  run-volgorde-lijst.
- **Bestaande scenario's** (`start`, `rss`, `feed`, `samenvatting`, `cleanup`) ongemoeid gelaten;
  geen binnen-scope-gaten geconstateerd. Observatie buiten scope: `cleanup-scenario.md` beschrijft
  nog JSON-op-schijf-cleanup terwijl de backend op PostgreSQL/Neon draait — bestaande doc-drift,
  aparte/bredere correctie, niet vereist voor deze story.

## Verificatie

- `mvn test` (backend) → **BUILD SUCCESS**, Tests run: 25, Failures: 0, Errors: 0. Geen
  productiecode of migraties gewijzigd; uitsluitend bestanden onder `e2e/` (+ dit story-log/worklog).
- Geen scenario toegevoegd dat buggy gedrag zou "bevriezen": de doorlopen flows gedragen zich
  correct volgens de screens, dus geen in-error-melding nodig.
