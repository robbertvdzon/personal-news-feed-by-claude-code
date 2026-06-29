# SF-726 - Worklog

Story-context bij eerste pickup:
E2E-scenario's aanvullen en docs consistent maken

Inventariseer elk Flutter-scherm in frontend/lib/screens/ en map het tegen de tien bestaande scenario's in e2e/scenarios/. Vul ontbrekende sub-flows aan of verfijn verouderde scenario's in de bestaande stijl (NL: Doel/Voorwaarden/Stappen/Verwacht resultaat/Faal-condities), niet-destructief (destructieve acties alleen bekijken/annuleren; admin blijft graceful Skipped zonder ROLE_ADMIN). Verantwoord bewust niet-geteste sub-flows expliciet in het scenario. Houd e2e/readme.md en specs/e2e.md consistent met de daadwerkelijke e2e/scenarios/-inhoud. Wijzig GEEN productiecode en zet GEEN backend-integratietest-infra op (frontend-reader/ en adhoc buiten scope). Voeg geen test toe die buggy gedrag zou bevriezen; ga dan in error met een concrete notitie. Een kleine of lege diff is een geldige uitkomst en wordt onderbouwd. Voer aan het eind een review-stap uit op stijl-consistentie, niet-destructiviteit en docs/scenario-overeenkomst.

Stappenplan:
[x]: read issue and target docs
[x]: inventariseer Flutter-schermen vs. bestaande scenario's
[x]: ontbrekende dekking toevoegen (rss-podcast-scenario)
[x]: e2e/readme.md + specs/e2e.md consistent maken
[x]: consistentie-/review-stap draaien
[x]: update story-log with results

## Scherm-inventarisatie (frontend/lib/screens/ → scenario)

16 schermbestanden, gemapt tegen de scenario's:

| Scherm | Gedekt door |
|---|---|
| login_screen.dart | start-scenario |
| main_shell.dart | (navigatie; impliciet in elk scenario) |
| feed_screen.dart / feed_detail_screen.dart | feed-scenario |
| rss_screen.dart / rss_detail_screen.dart | rss-scenario |
| **rss_podcast_detail_screen.dart** | **was ONGEDEKT → nieuw rss-podcast-scenario** |
| podcast_screen.dart / podcast_detail_screen.dart | podcast-scenario |
| events_screen.dart / event_detail_screen.dart | events-scenario |
| settings_screen.dart / rss_feeds_screen.dart | settings-scenario |
| api_log_screen.dart | api-log-scenario |
| admin_screen.dart / admin_costs_screen.dart | admin-scenario |

De enige ongedekte functionele schermklasse was `RssPodcastDetailScreen`
(`rss_podcast_detail_screen.dart`): het detailscherm voor RSS-podcast-afleveringen
(`mediaType = PODCAST`), bereikt vanuit de RSS-, Feed- en (via "Vertaald van …")
PodcastDetail-schermen. Het verschilt van zowel `RssItemDetailScreen` (artikelen)
als `PodcastDetailScreen` (AI-gegenereerde podcasts) en heeft eigen sub-flows:
lange Samenvatting (Markdown), Key takeaways, inklapbaar lazy-loaded Ruw transcript
en de vertaal-flow ("Vertaal & genereer Nederlandse podcast" / "Bekijk vertaling").

## Gedaan / rationale

- **Nieuw scenario `e2e/scenarios/rss-podcast-scenario.md`** toegevoegd in de
  bestaande stijl (Doel/Voorwaarden/Stappen/Verwacht resultaat/Graceful
  degradation/Faal-condities, NL). Niet-destructief: de kostengenererende
  vertaal-actie wordt alleen bekeken en in de bevestigingsdialoog **geannuleerd**;
  "Origineel afspelen"/"Bekijk vertaling" zijn read-only. Graceful Skipped-pad
  als er geen podcast-aflevering beschikbaar is. UI-feiten geverifieerd tegen
  `rss_podcast_detail_screen.dart` + `models.dart` (`isPodcast`, `longSummary`,
  `keyTakeaways`, `isShowNotesBased`, `EpisodeLookup`, AppBar-titel `<n>/<totaal>`,
  knop-/sectie-labels).
- **Cross-references** toegevoegd zodat de scenario's elkaar niet overlappen:
  - `rss-scenario.md` stap 4: kies expliciet een **artikel**; podcast-afleveringen
    routeren naar `rss-podcast-scenario`.
  - `podcast-scenario.md` afbakening: dit gaat over AI-gegenereerde podcasts;
    RSS-podcast-afleveringen -> `rss-podcast-scenario`.
- **Docs consistent gemaakt:** `e2e/readme.md` (mappenstructuur + run-order-lijst)
  en `specs/e2e.md` (scenario-tabel) noemen nu exact dezelfde 11 scenario-bestanden
  als in `e2e/scenarios/`.

## Tests / verificatie

- Geen automatische testlaag: de e2e-suite bestaat uit menselijk/agent-afspeelbare
  markdown-scripts (geen framework), en de developer-runner heeft geen
  flutter/dart-binary (kan `flutter analyze/test` niet draaien). Geverifieerd is:
  - Consistentie-check (grep): de scenario-lijsten in `e2e/scenarios/`,
    `e2e/readme.md` en `specs/e2e.md` matchen exact (11 bestanden).
  - UI-feiten in het nieuwe scenario gecontroleerd tegen de werkelijke
    schermcode/modelvelden.
- Geen productiecode gewijzigd (alleen `e2e/scenarios/*.md` + de twee doc-lijsten);
  `mvn test` is niet relevant geraakt.

## Bewust niet gewijzigd (gemeld i.p.v. fixen)

- **Backend-integratietest-infra** (Cucumber/WireMock/Testcontainers/`*IT.kt`):
  buiten scope per story; ontbrekende, in de factory niet-uitvoerbare infrastructuur.
- **Anthropic->OpenAI doc-drift** in `e2e/readme.md` (PNF_ANTHROPIC_API_KEY in de
  "Vooraf"-sectie) en `samenvatting-scenario.md` (`PNF_ANTHROPIC_API_KEY`, `[Anthropic]`
  loglijnen): dit is backend-env-drift, niet een scherm-mismatch, en valt buiten de
  story-scope (scenario-/scherm-dekking + scenario-lijst-consistentie). Niet
  aangepast om scope-creep te vermijden; gemeld voor een eventuele aparte doc-story.
- **start-/cleanup-scenario** verwijzen naar `data/users.json` JSON-op-schijf
  (de repo draait inmiddels PostgreSQL/Neon). Risicovolle, niet-scherm-gerelateerde
  drift; bewust ongemoeid gelaten en hier gemeld.

## Tester-verificatie (SF-728, 2026-06-29)

Aard van de wijziging: uitsluitend e2e-scenario's + doc-lijsten + worklog (geen
productiecode). Geverifieerd via code-inspectie (Flutter web-preview is een
auth-canvas-SPA → geen DOM/WebFetch; geen dart-binary op de runner).

Resultaten:
- **Doc/bestand-consistentie** ✅ — `e2e/scenarios/` bevat 11 bestanden; zowel
  `e2e/readme.md` (boomstructuur + run-volgorde) als `specs/e2e.md` noemen exact
  diezelfde 11. Geen stale of ontbrekende namen (script-check).
- **Scherm-dekking** ✅ — elk functioneel scherm in `frontend/lib/screens/` is
  gedekt: login/main_shell→start, rss(+detail/feeds)→rss/settings,
  **rss_podcast_detail_screen→nieuw rss-podcast-scenario**, feed→feed,
  events→events, podcast→podcast, settings→settings, api_log→api-log,
  admin(+costs)→admin. De gap (RssPodcastDetailScreen) is nu gedicht.
- **Scenario klopt met de code (geen bug bevroren)** ✅ — gecontroleerd tegen
  `rss_podcast_detail_screen.dart`: AppBar-titel `${_idx+1}/${items.length}`
  ("<n>/<totaal>"), routing via `isPodcast`→`RssPodcastDetailScreen` (rss_screen
  + feed_screen), secties Samenvatting/Key takeaways/Ruw transcript, transcript
  via `GET /api/rss/<id>/transcript`, "voorlopig"-chip (hourglass_top),
  vertaal-knop-staten + bevestigingsdialoog (kosten-blok, Annuleren/Starten),
  "Origineel afspelen". Alle in het scenario genoemde teksten/iconen bestaan 1-op-1.
- **Niet-destructief** ✅ — vertaal-actie wordt alleen bekeken/geannuleerd;
  admin blijft ⏭ Skipped zonder ROLE_ADMIN.
- **Geen productiecode geraakt** ✅ — diff betreft alleen `e2e/**`, `specs/e2e.md`
  en worklog; `mvn test` niet relevant geraakt.

Conclusie: voldoet aan alle acceptance criteria → **tested**. De door de developer
gemelde, bewust-ongemoeide doc-drift (Anthropic→OpenAI, `data/users.json`) valt
buiten de story-scope en is terecht apart gerapporteerd.
