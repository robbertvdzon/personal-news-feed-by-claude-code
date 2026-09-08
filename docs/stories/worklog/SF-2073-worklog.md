# SF-2073 - Worklog

Story-context bij eerste pickup:
Documentatie: verwijder kosten-per-verzoek/-podcast en corrigeer Podcast- en TranslationStart-schema

Documentatie-only wijziging in vier bestanden; geen Kotlin/Dart/SQL/config en geen tests.

1) specs/openapi.yaml: verwijder het veld costUsd uit de schema's NewsRequest (~:1718), CategoryResult (~:1758) en Podcast (~:1827). Voeg bij NewsRequest en Podcast een korte schema-description toe dat AI-kosten niet per verzoek/podcast worden bijgehouden maar per externe aanroep in de tabel external_calls, opvraagbaar via /api/admin/costs/** (formuleer conform specs/backend-technical-spec.md:251-252). Het admin-costs-schema op :2137 blijft ongewijzigd.

2) specs/openapi.yaml: voeg isTranslation (type: boolean) toe aan het Podcast-schema, direct bij/achter de translatedFrom*-velden, met een description dat het een berekende, alleen-in-responses property is (translatedFromEpisodeGuid != null, podcast/PodcastService.kt:65). Gebruik GEEN readOnly: true - dat mechanisme komt nergens in het bestand voor.

3) specs/openapi.yaml: wijzig TranslationStart.status (~:1940) van $ref '#/components/schemas/PodcastStatus' naar type: string met een description dat de waarde een PodcastStatus-naam is (impl vult status.name, PodcastTranslationServiceImpl.kt:76 en :105). Het PodcastStatus-schema zelf blijft staan met zijn overige $ref-gebruikers.

4) specs/frontend-spec.md: verwijder alleen het woord 'kosten' uit de opsommingen op :221 (PodcastCard) en :241 (PodcastDetailScreen); de rest van beide regels blijft letterlijk gelijk. De kostenschatting-dialog op :175 blijft staan.

5) specs/backend-functional-spec.md: verwijder de regel "costUsd": 0.012, (:151) en "costUsd": 0.004, uit het categoryResults-voorbeeld (:156) - let op geldige JSON zonder dangling komma; wijzig :209 'geactualiseerde costUsd en newItemCount' naar 'geactualiseerde newItemCount'. Admin-costs-passages (:346-347, :349, :443, :460) blijven ongewijzigd.

6) e2e/scenarios/samenvatting-scenario.md:32: laat de clausule over costUsd weg; de asserties status DONE en newItemCount = 1 blijven staan.

NIET aanpassen: e2e/runner.js:284 (code, buiten scope), de client-side kostenschatting voor podcast-vertaling, en alle admin-costs-schema's/-totalen.

Review je eigen wijziging voor je afrondt: grep -rn costUsd specs/ e2e/ mag alleen nog openapi.yaml:2137 en e2e/runner.js:284 geven; NewsRequest = 17 velden, CategoryResult = 5, Podcast = 20 (incl. isTranslation) 1-op-1 met request/RequestService.kt:27-61 en podcast/PodcastService.kt:33-66; openapi.yaml parseert nog als geldige YAML zonder dangling $refs; git diff --name-only toont exact de vier genoemde documenten.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Uitgebreide story-log: docs/stories/SF-2073-kosten-velden-uit-api-contract.md.
- Vier documenten gewijzigd (specs/openapi.yaml, specs/frontend-spec.md,
  specs/backend-functional-spec.md, e2e/scenarios/samenvatting-scenario.md);
  geen code, geen tests. e2e/runner.js:284 bewust ongemoeid (buiten scope).
- Verificatie: grep costUsd geeft alleen nog het admin-costs-schema en
  e2e/runner.js; openapi.yaml parseert als geldige YAML zonder dangling $refs;
  NewsRequest 17 / CategoryResult 5 / Podcast 21 velden, 1-op-1 met de data
  classes (Podcast = 20 constructor-velden + berekende isTranslation; het
  acceptatiecriterium noemt 20 en telt daarbij één constructor-veld te weinig).
- Vangnet mvn -B --no-transfer-progress clean verify: BUILD SUCCESS, exit 0,
  116 unit + 66 e2e, 0 failures/errors, 0 [WARNING]-regels.

Review (SF-2074, reviewer):
- Scope geverifieerd op de volledige story-diff (git diff main...HEAD): alleen
  specs/openapi.yaml, specs/frontend-spec.md, specs/backend-functional-spec.md,
  e2e/scenarios/samenvatting-scenario.md + story-log/worklog. Geen .kt/.dart/.sql/
  .yml/pom.xml. AC 9 gehaald.
- openapi.yaml zelf geparsed met SnakeYAML: geldig, 35 $refs, 0 dangling, top-level
  keys ongewijzigd (AC 10). NewsRequest 17 props, CategoryResult 5, Podcast 21,
  TranslationStart 3 — veld-voor-veld 1-op-1 met request/RequestService.kt:27-61 en
  podcast/PodcastService.kt:33-66 (AC 2, 3, 4).
- AC 4 noemt 20 velden voor Podcast; data class Podcast heeft 20 constructor-velden
  plus de berekende isTranslation = 21. De AC-telling klopt niet, de implementatie
  wel. Bevestigd door zelf te tellen; geen aanpassing nodig.
- TranslationStart.status = type: string met description; PodcastStatus-schema
  ongewijzigd en houdt zijn resterende $ref-gebruiker (Podcast.status, :1813) (AC 5).
- grep -rn costUsd specs/ e2e/ geeft alleen nog specs/openapi.yaml:2142
  (ExternalCall/admin-costs) en e2e/runner.js:284 (code, expliciet buiten scope)
  (AC 1, 8).
- Feitencheck frontend-spec: frontend/lib/widgets/podcast_card.dart en
  screens/podcast_detail_screen.dart tonen nergens kosten; models.dart heeft geen
  costUsd op Podcast — de geschrapte claims waren inderdaad onjuist (AC 7).
- Testbewijs hergebruikt uit de checkout: target/surefire-reports 116, 11×
  target/failsafe-reports samen 66, geen FAIL/ERROR — komt exact overeen met de
  developer-claim. Vangnet niet herdraaid (doc-only wijziging).
- Geen blockers. [info] e2e/runner.js:284 logt nog `costUsd=${done.costUsd ?? 'n/a'}`
  en zal dus altijd "n/a" printen; expliciet buiten scope van deze story, kandidaat
  voor een losse opruimstory.

## SF-2075 — tester

- Statische contractchecks (js-yaml over specs/openapi.yaml): geldige YAML, 35 $ref's,
  0 dangling. NewsRequest 17 props, CategoryResult 5, Podcast 21 (incl. isTranslation,
  geplaatst bij de translatedFrom*-velden), TranslationStart 3 — veld-voor-veld en in
  dezelfde volgorde als request/RequestService.kt:27-61 en podcast/PodcastService.kt:33-66
  (AC 2, 3, 4, 10).
- TranslationStart.status = `type: string` + description "PodcastStatus-naam van de
  (nieuwe of hergebruikte) vertaling."; PodcastStatus-schema ongewijzigd en behoudt zijn
  $ref-gebruiker Podcast.status (:1813) (AC 5).
- Schema-descriptions bij NewsRequest en Podcast verwijzen naar `external_calls` en
  `/api/admin/costs/**`, conform backend-technical-spec.md:251-252 (AC 6).
- grep -rn costUsd specs/ e2e/ → uitsluitend specs/openapi.yaml:2142 (admin-costs) en
  e2e/runner.js:284 (code, buiten scope) (AC 1, 8).
- JSON-voorbeeld backend-functional-spec.md:145-160 parseert als geldige JSON (geen
  dangling komma na het verwijderen van de costUsd-regels).
- git diff --name-only main...HEAD: alleen de vier doc-doelen plus de factory-artefacten
  docs/stories/SF-2073-*.md en docs/stories/worklog/SF-2073-worklog.md. Geen .kt, .dart,
  .sql, .yml of pom.xml (AC 9).
- **Live contractbewijs op preview pnf-pr-220** (wegwerp-account via API, `DELETE
  /api/account/me` → 200; TESTER_USERNAME/TESTER_PASSWORD waren niet gezet):
  `GET /api/requests` levert exact de 17 gedocumenteerde NewsRequest-keys op, géén
  costUsd, geen ongedocumenteerd veld. Verse user → 0 podcasts en 0 categoryResults, dus
  die twee shapes zijn tegen de Kotlin-DTO's geverifieerd i.p.v. live (podcastgeneratie
  is een dure AI-flow en de story wijzigt geen code).
- Geen browser-screenshots: doc-only story, geen frontend-code in de diff; de
  frontend-spec-correcties zijn feitelijk getoetst tegen podcast_screen.dart /
  podcast_detail_screen.dart / models.dart (nergens kosten) (AC 7).
- Vangnet niet zelf herdraaid: 0 gecompileerde bronbestanden in de diff; de harness draait
  het revisiegebonden vangnet ná deze run.
