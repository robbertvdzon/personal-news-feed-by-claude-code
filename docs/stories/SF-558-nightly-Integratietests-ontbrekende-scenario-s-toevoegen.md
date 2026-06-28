# SF-558 - nightly: Integratietests: ontbrekende scenario's toevoegen

## Story

nightly: Integratietests: ontbrekende scenario's toevoegen

<!-- refined-by-factory -->

## Scope
Breid de bestaande end-to-end-suite in `e2e/scenarios/` uit zodat alle functionele gebruikersflows uit `docs/factory/functional-spec.md` die daadwerkelijk in de Flutter web-app aanwezig zijn, gedekt zijn door een afspeelbaar scenario.

Concreet, conform de conventies in `e2e/readme.md` (menselijk leesbare markdown met secties Doel / Voorwaarden / Stappen / Verwacht resultaat; afspeelbaar tussen `start-scenario` en `cleanup-scenario`):

- **Nieuw `events-scenario.md`**: Events-tab openen, lijst van aankomende/afgelopen events bekijken, een event-detail openen (`event_detail_screen.dart`), een event verwijderen via het prullenbak-icoon en verifiëren dat de app terugnavigeert en het event uit de lijst is (denylist-gedrag). Optioneel: handmatige event-discovery starten via Settings → Achtergrond-taken ("Zoek nu naar nieuwe events" / "Zoek nu naar event-video's").
- **Nieuw `podcast-scenario.md`**: Podcast-tab openen (`podcast_screen.dart`), een gegenereerde podcast openen in detail (`podcast_detail_screen.dart`) en afspelen verifiëren.
- **Nieuw `settings-scenario.md`**: Settings doorlopen voor wat echt in de UI zit — categorieën beheren (toevoegen/bewerken), de RSS-/podcast-feeds-editor (`rss_feeds_screen.dart`) en de Achtergrond-taken-knoppen (RSS vernieuwen, dagelijkse samenvatting, event-/video-discovery).
- **Bestaande scenario's** (`start`, `rss`, `feed`, `samenvatting`, `cleanup`) waar nodig verbeteren als er gaten/onnauwkeurigheden blijken tijdens het opstellen van de nieuwe scenario's; anders ongemoeid laten.

Uitsluitend de e2e-/integratietestlaag wordt aangepast; er worden geen nieuwe backend-integratietests (Cucumber/WireMock/`@SpringBootTest`) opgetuigd in deze story (zie Aannames).

## Acceptance criteria
- [ ] Voor elke in de Flutter web-app aanwezige functionele flow (Feed, RSS, Events, Podcasts, Settings/Achtergrond-taken) bestaat minstens één e2e-scenario in `e2e/scenarios/`.
- [ ] Nieuwe scenario's volgen de structuur uit `e2e/readme.md` (Doel / Voorwaarden / Stappen / Verwacht resultaat), zijn in het Nederlands en passen in de volgorde `start` → … → `cleanup`.
- [ ] Verwachte-resultaat-secties bevatten concrete, verifieerbare checks (UI-tekst, aantallen, navigatiegedrag), en beschrijven een graceful-degradation-uitkomst (⚠️ Partial) voor stappen die afhangen van externe AI/Tavily-output of lege data — analoog aan het bestaande `feed-scenario`.
- [ ] Elk nieuw scenario verwijst alleen naar UI die feitelijk in de betreffende screen aanwezig is (geverifieerd via grep in `frontend/lib/screens/`); er worden geen niet-bestaande Settings-UI's voor event-preferences/denylist beschreven.
- [ ] De bestaande backend unit-test-suite blijft ongewijzigd groen: `mvn test` slaagt (4 bestaande tests) en er is geen functionele productiecode gewijzigd.
- [ ] Als bij het opstellen een scenario duidelijk buggy gedrag zou "bevriezen", wordt het niet toegevoegd maar als notitie gemeld (in error gaan), conform de randvoorwaarde.

## Aannames
- **"Integratietests (incl. e2e-suite)" = de markdown-e2e-suite in `e2e/scenarios/`.** De backend heeft geen werkende integratietest-infrastructuur (geen Cucumber-runner, feature-files, WireMock-stubs of `*IT.kt`; Cucumber/WireMock-deps in `pom.xml` zijn ongebruikt). Een volledige `@SpringBootTest` vereist een live PostgreSQL/Neon-DB die in de factory/CI niet beschikbaar is, en geen CI-workflow draait `mvn test`. Het vanaf nul optuigen van backend-integratietests is daarmee een aparte, grotere story en valt buiten deze scope; deze story dekt "ontbrekende scenario's" via de bestaande e2e-laag.
- **Ad-hoc requests vallen buiten deze e2e-uitbreiding**, omdat er geen ad-hoc-/request-screen in `frontend/lib/screens/` bestaat om in de browser af te spelen; de flow is backend-only.
- De e2e-scenario's zijn agent-/handmatig-afspeelbare scripts (geen geautomatiseerd testframework), dus "alle tests slagen" geldt voor de geautomatiseerde unit-test-suite (`mvn test`); voor e2e-scenario's geldt dat ze correct, afspeelbaar en consistent met de UI moeten zijn.
- `docs/factory/` is volledig ingevuld (SF-220) en hoeft niet als acceptatiecriterium te worden aangevuld.
- Geen wijzigingen aan productiecode of databasemigraties; uitsluitend bestanden onder `e2e/` (en eventueel `e2e/readme.md` indien een nieuwe scenario-conventie verduidelijking vraagt).

## Eindsamenvatting

## Eindsamenvatting — SF-558: nightly: Integratietests — ontbrekende scenario's toevoegen

### Wat is gebouwd
De markdown-e2e-suite in `e2e/scenarios/` is uitgebreid zodat elke functionele gebruikersflow die daadwerkelijk in de Flutter web-app aanwezig is, gedekt is door een afspeelbaar scenario. Drie nieuwe scenario's toegevoegd, conform `e2e/readme.md` (secties Doel / Voorwaarden / Stappen / Verwacht resultaat, in het Nederlands, passend in de volgorde `start → … → cleanup`):

- **`events-scenario.md`** — Events-tab openen, lijst aankomend/geweest bekijken, event-detail openen, event verwijderen via het prullenbak-icoon (`delete_outline`, tooltip "Verwijderen") met terugnavigatie naar de lijst, en denylist-gedrag verifiëren via een nieuwe discovery. Optionele handmatige event-discovery via de AppBar-knop en via Settings → Achtergrond-taken.
- **`podcast-scenario.md`** — Podcast-tab openen, nieuwe podcast aanmaken (periode/duur/TTS-provider), statussen volgen ("In wachtrij…" → … → "Klaar"), detail openen en afspelen verifiëren (slider, play/pause, skip ±15/30/60, "Draaiboek"/"Download").
- **`settings-scenario.md`** — Categorieën beheren (toevoegen/bewerken/verwijderen, incl. niet-bewerkbare systeem-categorie "Overig"), de RSS-/podcast-feeds-editor (incl. serverseitige feed-validatie), en de vier Achtergrond-taken-knoppen (RSS vernieuwen, dagelijkse samenvatting, event-discovery, video-discovery).

Daarnaast is `e2e/readme.md` bijgewerkt (nieuwe scenario's toegevoegd aan de mappenboom en run-volgorde — accuraatheidsverbetering, geen conventiewijziging).

### Belangrijkste keuzes
- **UI vooraf geverifieerd per screen** tegen `frontend/lib/screens/` (events, event-detail, podcast, podcast-detail, settings, rss_feeds, main_shell). Elke scenario-stap verwijst uitsluitend naar UI-tekst die letterlijk in de screens voorkomt; geen niet-bestaande Settings-UI voor event-preferences/denylist beschreven.
- **Denylist** is correct als backend-gedrag (V14-migratie + SettingsService) beschreven bij event-verwijderen, niet als Settings-UI.
- Elk scenario bevat concrete verifieerbare checks (UI-tekst, aantallen, navigatie) én een **graceful-degradation-uitkomst (⚠️ Partial)** voor AI/Tavily-afhankelijke of lege-data-stappen, analoog aan `feed-scenario`.

### Wat is getest
- **Review (SF-559)** en **test (SF-560)**: beide goedgekeurd. Verificatie via per-screen code-inspectie tegen `frontend/lib/screens/` — claim-voor-claim bevestigd dat alle aangehaalde knoplabels, snackbars, sectiekoppen, iconen en navigatiegedrag exact kloppen.
- De story raakt **geen productiecode of migraties** (alleen `e2e/` + story-log/worklog) — geverifieerd via diff. De backend unit-test-suite is daarmee identiek aan `main` en blijft groen (developer rapporteerde lokaal `mvn test` → BUILD SUCCESS).

### Bewust niet gedaan
- **Geen nieuwe backend-integratietests** (Cucumber/WireMock/`@SpringBootTest`) opgetuigd — die infrastructuur ontbreekt en vereist een live DB; dat valt buiten scope (aparte, grotere story).
- **Ad-hoc requests** niet meegenomen — er bestaat geen ad-hoc/request-screen in de frontend (backend-only flow).
- **`cleanup-scenario.md`** ongemoeid gelaten: bevat out-of-scope doc-drift (beschrijft nog JSON-op-schijf-cleanup i.p.v. PostgreSQL/Neon). Gemeld als observatie voor een losse story.
- Geen scenario toegevoegd dat buggy gedrag zou "bevriezen" — alle doorlopen flows gedragen zich correct, dus geen in-error-melding nodig.
