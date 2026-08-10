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
