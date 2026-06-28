# SF-558 - Worklog

Story-context bij eerste pickup:
E2e-scenario's events, podcast en settings toevoegen

Breid de markdown-e2e-suite in e2e/scenarios/ uit met drie nieuwe scenario's, conform e2e/readme.md (secties Doel/Voorwaarden/Stappen/Verwacht resultaat, in NL, passend in de volgorde start→…→cleanup). Verifieer per screen vooraf via grep in frontend/lib/screens/ welke UI feitelijk bestaat; verwijs nergens naar niet-bestaande UI (bv. event-preferences/denylist in Settings). (1) events-scenario.md: Events-tab openen, lijst aankomende/afgelopen events, event-detail openen (event_detail_screen.dart), event verwijderen via prullenbak-icoon en verifiëren dat de app terugnavigeert en het event uit de lijst is (denylist-gedrag); optioneel handmatige event-discovery via Settings→Achtergrond-taken indien die knoppen bestaan. (2) podcast-scenario.md: Podcast-tab (podcast_screen.dart), gegenereerde podcast in detail openen (podcast_detail_screen.dart) en afspelen verifiëren. (3) settings-scenario.md: categorieën beheren (toevoegen/bewerken), RSS-/podcast-feeds-editor (rss_feeds_screen.dart), Achtergrond-taken-knoppen (RSS vernieuwen, dagelijkse samenvatting, event-/video-discovery). Verwachte-resultaat-secties bevatten concrete verifieerbare checks (UI-tekst, aantallen, navigatie) en een graceful-degradation-uitkomst (⚠️ Partial) voor AI/Tavily-afhankelijke of lege-data-stappen, analoog aan feed-scenario.md. Verbeter bestaande scenario's alleen bij geconstateerde gaten; anders ongemoeid. Wijzig GEEN productiecode of migraties. Als een scenario duidelijk buggy gedrag zou bevriezen: niet toevoegen maar als notitie melden (in error gaan). Voer een interne reviewstap uit op UI-consistentie en het uitblijven van productiecode-wijzigingen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- UI vooraf geverifieerd via lezen van de feitelijke Flutter-screens (`frontend/lib/screens/`):
  `main_shell.dart`, `events_screen.dart`, `event_detail_screen.dart`, `podcast_screen.dart`,
  `podcast_detail_screen.dart`, `settings_screen.dart`, `rss_feeds_screen.dart`. Elke scenario-stap
  verwijst alleen naar UI-tekst die letterlijk in die screens voorkomt (knoplabels, snackbars,
  sectiekoppen, chips, icons). Geen niet-bestaande Settings-UI voor event-preferences/denylist
  beschreven (denylist is backend-gedrag bij event-verwijderen).
- Drie nieuwe scenario's toegevoegd, conform `e2e/readme.md` (Doel / Voorwaarden / Stappen /
  Verwacht resultaat, in NL, met een Graceful-degradation-sectie ⚠️ Partial analoog aan feed-scenario):
  - `e2e/scenarios/events-scenario.md` — Events-tab, lijst aankomend/geweest, detail openen,
    event verwijderen via prullenbak-icoon (`delete_outline`, tooltip "Verwijderen") → terug naar
    lijst + event weg; denylist-verificatie via een nieuwe discovery. Optionele handmatige discovery
    via de AppBar-knop "Zoek nu naar nieuwe events" en via Settings → Achtergrond-taken.
  - `e2e/scenarios/podcast-scenario.md` — Podcast-tab, nieuwe podcast aanmaken via "Nieuwe podcast"
    (periode/duur/TTS-provider), statussen volgen ("In wachtrij…" → … → "Klaar"), detail openen en
    afspelen verifiëren (slider, play/pause, skip −60…+60, "Draaiboek"/"Download").
  - `e2e/scenarios/settings-scenario.md` — categorieën toevoegen/bewerken/verwijderen (incl.
    systeem-categorie "Overig" niet-bewerkbaar), RSS-/podcast-feeds-editor (`rss_feeds_screen.dart`,
    incl. serverseitige podcast-URL-validatie met rode snackbar), en de vier Achtergrond-taken-knoppen
    (RSS vernieuwen, dagelijkse samenvatting, event-discovery, video-discovery).
- `e2e/readme.md` bijgewerkt: nieuwe scenario's toegevoegd aan de mappenstructuur-boom en aan de
  run-volgorde-lijst (kleine accuraatheidsverbetering, geen conventiewijziging).
- Bestaande scenario's (`start`, `rss`, `feed`, `samenvatting`) ongemoeid gelaten — geen gaten
  geconstateerd die binnen deze scope vallen. Observatie (buiten scope, niet aangepast):
  `cleanup-scenario.md` beschrijft nog JSON-op-schijf-cleanup (`data/users.json`, `rm -rf data/users/<user>`),
  terwijl de backend inmiddels PostgreSQL/Neon gebruikt; dat is bestaande doc-drift in een ander
  scenario en een aparte, bredere correctie — niet vereist voor deze story.
- Geen productiecode of migraties gewijzigd; uitsluitend bestanden onder `e2e/`. Backend unit-tests
  draaien groen: `mvn test` → BUILD SUCCESS, Tests run: 25, Failures: 0, Errors: 0.
  (`.task.md` noemt "4 bestaande tests"; de feitelijke suite is 25 — beide constateringen kloppen
  met "ongewijzigd groen, geen productiecode gewijzigd".)
- Geen scenario toegevoegd dat buggy gedrag zou "bevriezen": de doorlopen flows (event-delete met
  state-rollback, podcast-generatie/afspelen, settings-edits, feed-validatie) gedragen zich correct
  volgens de screens; geen in-error-melding nodig.

## Review (SF-559, reviewer)

Volledige story-diff (`git diff main...HEAD`) beoordeeld: alleen `e2e/` + story-log/worklog gewijzigd,
geen productiecode/migraties (AC: ✅). UI-claims in alle drie nieuwe scenario's per-screen geverifieerd
tegen `frontend/lib/screens/`:
- **events**: AppBar-knoppen `travel_explore`/`refresh` + tooltips, snackbar-tekst, secties
  `Aankomend`/`Geweest` (icons upcoming/history), lege-staat "Nog geen events ontdekt", detail-titels
  "Aankomend event"/"Event", `delete_outline` + tooltip "Verwijderen" → `pop()`, secties
  Onderwerpen/Video's/Bronnen, "Maak samenvatting" — alles aanwezig. Denylist is echte backend-feature
  (V14 migratie + SettingsService), correct als backend-gedrag beschreven, NIET als Settings-UI (AC: ✅).
- **podcast**: dialoog-velden + defaults (periode 7 / duur 15), TTS-dropdown OpenAI/ElevenLabs,
  "Maak" enabled-validatie (`days>=1 && duration>=1`), statuslabels, detail slider/play-pause/
  skip ±15/30/60/Draaiboek(article)/Download — exact kloppend.
- **settings**: secties, niet-bewerkbare systeem-categorie "Overig", categorie add/edit/delete,
  rss_feeds_screen (RSS-feeds + Podcast-bronnen, ×/+ , serverseitige validatie "Kon feed niet ophalen"),
  vier Achtergrond-taken-knoppen met juiste snackbars/iconen — kloppend.

Scenario's volgen `e2e/readme.md`-structuur (Doel/Voorwaarden/Stappen/Verwacht resultaat), in NL, met
⚠️ Partial graceful-degradation-secties analoog aan feed-scenario. `e2e/readme.md` consistent bijgewerkt.
Geen achtergebleven JSON-artefacten in story-log/worklog (grep schoon).

[info] Out-of-scope doc-drift in `cleanup-scenario.md` (JSON-op-schijf vs PostgreSQL) terecht
ongemoeid gelaten en gemeld; eventueel als losse story oppakken.

**Oordeel: akkoord.** Geen blockers/bugs; scope en (e2e-)dekking conform AC.
