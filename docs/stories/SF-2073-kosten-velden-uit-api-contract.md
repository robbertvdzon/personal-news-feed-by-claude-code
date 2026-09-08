# SF-2073 - [Audit] Audit: haal de niet-bestaande kosten-velden uit het API-contract

## Story

[Audit] Audit: haal de niet-bestaande kosten-velden uit het API-contract

<!-- refined-by-factory -->

## Samenvatting

Het API-contract in `specs/openapi.yaml` belooft op drie plekken een kostenveld dat de backend nooit meestuurt, en de frontend-spec beschrijft twee podcastschermen die kosten tonen die er niet zijn. Omgekeerd stuurt de backend bij een podcast wél een "is dit een vertaling"-vlag mee die nergens gedocumenteerd staat, en klopt het type van één statusveld niet.

Deze story haalt de niet-bestaande kostenbelofte uit alle documentatie, voegt de ontbrekende vlag toe en corrigeert het statusveld. Waar de kosten écht bijgehouden worden (per externe AI-aanroep, zichtbaar in het admin-kostenoverzicht) wordt kort vermeld, zodat niemand denkt dat de informatie zomaar verdwenen is.

Er verandert geen enkele regel programmacode en geen enkel gedrag in de app of backend; dit is puur het rechttrekken van de documentatie.

## Scope

Documentatie-only. Er worden geen Kotlin-, Dart-, SQL- of configuratiebestanden gewijzigd en geen tests toegevoegd of aangepast.

**1. `specs/openapi.yaml` — verwijder `costUsd` (3×)**
- `NewsRequest` (`:1718-1721`)
- `CategoryResult` (`:1758-1760`)
- `Podcast` (`:1827-1829`)

Noteer bij het `NewsRequest`- en het `Podcast`-schema kort waar de kosten wél leven: per externe aanroep in de tabel `external_calls`, opvraagbaar via `/api/admin/costs/**`. Formuleer dat conform `specs/backend-technical-spec.md:251-252`.

**2. `specs/openapi.yaml` — voeg `isTranslation` toe aan `Podcast`** (schema `:1796-1866`)
`type: boolean`, met een description die vastlegt dat het een berekende, alleen-in-responses property is (`translatedFromEpisodeGuid != null`, zie `podcast/PodcastService.kt:65`). Plaats het logisch bij de `translatedFrom*`-velden.

**3. `specs/openapi.yaml` — corrigeer `TranslationStart.status`** (`:1940-1941`)
Van `$ref: '#/components/schemas/PodcastStatus'` naar `type: string`, conform `TranslationStart.status: String` (`podcast/PodcastTranslationService.kt:49-53`), gevuld met `status.name` op `PodcastTranslationServiceImpl.kt:76` en `:105`. Voeg een description toe die aangeeft dat de waarde een `PodcastStatus`-naam is, zodat de leesbaarheid niet achteruitgaat.

**4. `specs/frontend-spec.md` — haal de kosten-claims weg**
- `:221` (PodcastCard: "Toont: podcastnummer, titel, datum, duur, status, kosten, TTS-provider")
- `:241` (PodcastDetailScreen: "Toont: titel, periode, duur, kosten, TTS-provider, …")

Alleen het woord "kosten" verdwijnt; de rest van beide opsommingen blijft ongewijzigd.

**5. `specs/backend-functional-spec.md` — verwijder de kosten-per-verzoek-claims**
- `:151` — regel `"costUsd": 0.012,` uit het `NewsRequest`-JSON-voorbeeld
- `:156` — `"costUsd": 0.004,` uit het `categoryResults`-voorbeeld
- `:209` — stap 4 van de daily-summary-flow: "geactualiseerde `costUsd` en `newItemCount`" wordt "geactualiseerde `newItemCount`"

**6. `e2e/scenarios/samenvatting-scenario.md:32`** — laat de clausule "`costUsd` toont de Anthropic-kosten van de aanroep" weg; de assertie op `status: DONE` en `newItemCount = 1` blijft.

### Expliciet buiten scope

- Het daadwerkelijk bouwen van kosten-per-verzoek of kosten-per-podcast (feature-story, geen doc-story).
- Alle kosten-documentatie die wél klopt en dus blijft staan:
  - de client-side kóstenschatting vóór een podcast-vertaling (`specs/frontend-spec.md:175`, `specs/backend-functional-spec.md:349`, `e2e/scenarios/rss-podcast-scenario.md`) — die dialog bestaat echt en rekent uit de transcript-lengte (`frontend/lib/models/models.dart:464`);
  - alle admin-costs-schema's en -kostentotalen in `specs/openapi.yaml` (`:1104`, `:1128`, `:1153`, `:2078`, `:2095`, `:2137`) en `specs/backend-functional-spec.md:346-347`, `:443`, `:460`;
  - `specs/backend-technical-spec.md:251-252` en `:281` (blijven ongewijzigd; `:251-252` is juist de bronformulering voor punt 1).
- De overige frontend-spec-drift buiten `:221` en `:241`.

## Acceptance criteria

1. `grep -n costUsd specs/openapi.yaml` geeft alleen nog treffers binnen de admin-costs-schema's (`:2137`-omgeving); nul treffers in `NewsRequest`, `CategoryResult` en `Podcast`.
2. Het `NewsRequest`-schema bevat exact dezelfde 17 velden als `data class NewsRequest` (`request/RequestService.kt:27-51`) — geen veld meer en geen veld minder.
3. Het `CategoryResult`-schema bevat exact de 5 velden van `data class CategoryResult` (`request/RequestService.kt:55-61`).
4. Het `Podcast`-schema bevat exact de 20 velden van `data class Podcast` inclusief de berekende `isTranslation` (`podcast/PodcastService.kt:33-66`) — dus 19 constructor-velden plus `isTranslation`, en géén `costUsd`.
5. `TranslationStart.status` in `specs/openapi.yaml` is `type: string` en verwijst niet meer naar het `PodcastStatus`-schema; het `PodcastStatus`-schema zelf blijft ongewijzigd en houdt zijn andere `$ref`-gebruikers.
6. Bij het `NewsRequest`- en het `Podcast`-schema staat een korte notitie dat AI-kosten in de tabel `external_calls` leven en via `/api/admin/costs/**` op te vragen zijn.
7. `specs/frontend-spec.md:221` en `:241` noemen geen kosten meer; de overige items in die opsommingen zijn onveranderd.
8. `grep -rn costUsd specs/ e2e/` levert nul treffers op die kosten per verzoek, per categorie-resultaat of per podcast beloven (admin-costs-treffers uitgezonderd).
9. Geen wijzigingen buiten `specs/openapi.yaml`, `specs/frontend-spec.md`, `specs/backend-functional-spec.md` en `e2e/scenarios/samenvatting-scenario.md`: `git diff --name-only` toont geen `.kt`-, `.dart`-, `.sql`-, `.yml`- of `pom.xml`-bestand.
10. `specs/openapi.yaml` blijft geldige YAML en alle `$ref`-verwijzingen resolven nog (geen dangling refs na de `TranslationStart`-wijziging).

## Aannames

1. **`isTranslation` wordt als description gedocumenteerd, niet met `readOnly: true`.** `specs/openapi.yaml` gebruikt vandaag nul keer `readOnly`; een description die zegt "berekende property, alleen aanwezig in responses" past bij de huisstijl en vermijdt het introduceren van een nieuw spec-mechanisme voor één veld.
2. **De Flutter-app blijft ongemoeid.** De app berekent `isTranslation` zelf uit `translatedFromEpisodeGuid` (`frontend/lib/models/models.dart:434`) en leest het veld niet uit de JSON. Het veld wordt gedocumenteerd omdat de backend het wél serialiseert, niet omdat de app het nodig heeft; er is dus geen parser-wijziging nodig.
3. **De kosten-informatie verdwijnt niet uit het product, alleen uit het per-verzoek/per-podcast-contract.** Dat AI-kosten bewust niet per verzoek worden bijgehouden is een vastgelegde ontwerpkeuze (`specs/backend-technical-spec.md:251-252`); deze story bevestigt die keuze in het contract in plaats van hem te wijzigen.
4. **Punt 5 en 6 (backend-functional-spec + e2e-scenario) horen bij deze story** omdat de acceptatie "geen enkel document belooft nog kosten per verzoek" zonder die plekken niet haalbaar is. Ze bevatten exact dezelfde onjuiste belofte als de openapi-velden.
5. **Verificatie is documentaire inspectie plus greps.** Omdat er geen productiecode wijzigt, is `mvn verify` (`.factory/verification.yaml`, `backend-maven-verify`) niet nodig om deze wijziging te valideren; als de pipeline hem toch draait moet hij per definitie ongewijzigd groen blijven.
6. **De `TranslationStart.status`-correctie verruimt het contract niet feitelijk.** De impl vult altijd een `PodcastStatus`-naam; `type: string` beschrijft alleen accuraat dat de API een losse string teruggeeft en geen enum-gevalideerde waarde belooft.

## Eindsamenvatting

## Eindsamenvatting SF-2073 — Audit: niet-bestaande kosten-velden uit het API-contract

### Wat is gebouwd
Documentatie-only correctie in vier bestanden; **geen enkele regel code, geen gedragswijziging**.

| Bestand | Wijziging |
|---|---|
| `specs/openapi.yaml` | `costUsd` verwijderd uit `NewsRequest`, `CategoryResult` en `Podcast`; `isTranslation` (boolean) toegevoegd aan `Podcast` bij de `translatedFrom*`-velden; `TranslationStart.status` van `$ref: PodcastStatus` naar `type: string` met description |
| `specs/frontend-spec.md` | woord "kosten" geschrapt uit de opsommingen bij PodcastCard (`:221`) en PodcastDetailScreen (`:241`) |
| `specs/backend-functional-spec.md` | `costUsd`-regels uit het `NewsRequest`- en `categoryResults`-JSON-voorbeeld; daily-summary-stap 4 noemt alleen nog `newItemCount` |
| `e2e/scenarios/samenvatting-scenario.md` | clausule over `costUsd` weg; asserties `status: DONE` en `newItemCount = 1` blijven |

Bij `NewsRequest` en `Podcast` staat nu een korte notitie dat AI-kosten *wél* bestaan, maar per externe aanroep in de tabel `external_calls` en opvraagbaar via `/api/admin/costs/**` — zodat niemand denkt dat de informatie verdwenen is.

### Gemaakte keuzes
- `isTranslation` is gedocumenteerd via een description ("berekende property, alleen in responses") en **niet** met `readOnly: true`: dat mechanisme komt nergens anders in `openapi.yaml` voor.
- `TranslationStart.status` is bewust versoepeld naar `type: string`; de implementatie vult `status.name`, dus het contract beloofde ten onrechte een enum-gevalideerde waarde. Het `PodcastStatus`-schema zelf is ongemoeid en houdt zijn gebruiker `Podcast.status`.
- De Flutter-app is niet aangeraakt: die berekent `isTranslation` zelf uit `translatedFromEpisodeGuid` en leest het veld niet uit de JSON.
- De correcte kosten-documentatie (client-side kostenschatting vóór podcast-vertaling, alle admin-costs-schema's en -totalen) blijft ongewijzigd staan.

### Wat is getest
- `specs/openapi.yaml` geparsed (SnakeYAML én js-yaml): geldige YAML, 35 `$ref`s, **0 dangling refs**.
- Veld-voor-veld vergeleken met de Kotlin-data classes: `NewsRequest` 17, `CategoryResult` 5, `Podcast` 21, `TranslationStart` 3 — 1-op-1 en in dezelfde volgorde.
- `grep -rn costUsd specs/ e2e/` levert alleen nog het admin-costs-schema (`openapi.yaml:2142`) en `e2e/runner.js:284` op.
- Het JSON-voorbeeld in `backend-functional-spec.md` parseert nog als geldige JSON (geen dangling komma).
- Feitencheck frontend: `podcast_card.dart`, `podcast_detail_screen.dart` en `models.dart` tonen nergens kosten — de geschrapte claims waren inderdaad onjuist.
- Live contractbewijs op preview `pnf-pr-220` met een wegwerp-account: `GET /api/requests` gaf exact de 17 gedocumenteerde velden, géén `costUsd`, geen ongedocumenteerd veld.
- Vangnet `mvn clean verify`: BUILD SUCCESS, 116 unit- + 66 e2e-tests, 0 failures.

### Bewust niet gedaan
- Geen kosten-per-verzoek of kosten-per-podcast gebouwd — dat is een feature-story, geen doc-story.
- Geen code-, test-, SQL- of configwijzigingen; `git diff --name-only` toont alleen de vier documenten plus de factory-artefacten.
- `e2e/runner.js:284` bewust ongemoeid gelaten (code, buiten scope).
- De overige frontend-spec-drift buiten `:221`/`:241` is niet aangepakt.

### Aandachtspunten voor de PO
1. **Open punt voor een vervolgstory:** `e2e/runner.js:284` logt nog `costUsd=${done.costUsd ?? 'n/a'}` en print daardoor structureel `n/a`. Kandidaat voor een kleine opruimstory.
2. **Acceptatiecriterium 4 telde één veld te weinig:** het AC noemt 20 velden voor `Podcast`, maar de data class heeft 20 constructor-velden *plus* de berekende `isTranslation` = 21. Zowel reviewer als tester hebben dit onafhankelijk nageteld; de implementatie klopt, het AC-getal niet.
3. De `Podcast`- en `CategoryResult`-shapes zijn tegen de Kotlin-DTO's geverifieerd in plaats van live: een verse testgebruiker heeft 0 podcasts en podcastgeneratie is een dure AI-flow.

<!-- deploy-summary:start -->
Er verandert niets aan de app zelf: dit was een correctie van de documentatie. De beschrijving van de app beloofde op een paar plekken kosteninformatie per nieuwsverzoek en per podcast die er in werkelijkheid nooit was. Die onjuiste beloftes zijn weggehaald, met een verwijzing naar het kostenoverzicht waar de kosten wél te zien zijn.
<!-- deploy-summary:end -->
