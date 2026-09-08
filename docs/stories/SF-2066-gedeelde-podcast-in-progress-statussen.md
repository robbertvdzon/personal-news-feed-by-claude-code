# SF-2066 - [Audit] Zet de "podcast is nog bezig"-statuslijst op één gedeelde plek, zodat de poll-timer ook bij vertalen blijft lopen

## Story

[Audit] Zet de "podcast is nog bezig"-statuslijst op één gedeelde plek, zodat de poll-timer ook bij vertalen blijft lopen

<!-- refined-by-factory -->

## Samenvatting

Als een podcast wordt vertaald, laat het podcast-overzicht een draaiend rondje met "Vertalen…" zien. Maar het scherm ververst zichzelf op dat moment níet: het rondje blijft eindeloos draaien tot je zelf het scherm ververst. Dat komt doordat de lijst met "podcast is nog bezig"-statussen op drie plekken los van elkaar in de code staat en één van die drie de vertaalstatussen mist.

Deze story zet die lijst op één gedeelde plek en laat alle drie de plekken die ene lijst gebruiken. Daarmee is het draaiende rondje en het automatisch verversen het per definitie met elkaar eens, en verdwijnt het vastgelopen rondje bij vertalen. Er komt een test bij zodat de drie plekken niet opnieuw uit elkaar kunnen lopen. Voor de gebruiker verandert er verder niets: dezelfde statussen, dezelfde teksten, geen nieuwe knoppen.

## Scope

In scope (alleen `frontend/`):

1. **Gedeelde constante** in `frontend/lib/models/models.dart`, naast het `Podcast`-model:
   ```dart
   const kPodcastInProgressStatuses = <String>{
     'PENDING', 'DETERMINING_TOPICS', 'GENERATING_SCRIPT',
     'GENERATING_AUDIO', 'TRANSLATING', 'TTS_GENERATING',
   };
   ```
   Met een korte comment die vastlegt dat dit de enige plek is waar deze lijst mag staan, en waarom: spinner/label en de poll-timer moeten het per definitie eens zijn (anders draait het rondje door zonder dat er nog ververst wordt).
2. `_isInProgress` (`frontend/lib/screens/podcast_screen.dart:16-22`) gebruikt de gedeelde set in plaats van de zes `||`-vergelijkingen.
3. **De eigenlijke fix:** de inline lijst in `_maybePoll` (`podcast_screen.dart:56-61`) wordt vervangen door dezelfde gedeelde set, zodat `TRANSLATING` en `TTS_GENERATING` de poll-timer óók starten/aanhouden. De `if`/`else`-structuur (`:62-68`) blijft ongewijzigd.
4. `_inProgressStatuses` (`frontend/lib/screens/podcast_detail_screen.dart:30-37`) wordt verwijderd; het gebruik op `:52` wijst naar de gedeelde set. De bestaande KDoc-comment op `:23-26` over de translate-flow blijft behouden (eventueel licht aangepast naar de nieuwe constante).
5. Eén nieuwe test in `frontend/test/` die vastlegt dat de gedeelde set precies de statussen bevat die het overzichtsscherm als "bezig" toont.

Buiten scope (bewust, past niet in deze story):

- Uitfactoren van `_statusLabel` (letterlijk dubbel in `podcast_screen.dart:24-45` en `podcast_detail_screen.dart:263-284`).
- De afwijkende `_phaseLabel` in `rss_podcast_detail_screen.dart:707-718`.
- De duplicatie tussen `feed_screen.dart` en `rss_screen.dart`.
- `Podcast.translationInProgress` (`models.dart:497-499`) met zijn eigen lijst `['PENDING','TRANSLATING','TTS_GENERATING']`. Dat is géén vierde kopie van dezelfde vraag maar een smallere, correcte lijst voor uitsluitend de vertaalflow (een vertaling doorloopt nooit `DETERMINING_TOPICS`/`GENERATING_SCRIPT`/`GENERATING_AUDIO`). Deze blijft ongewijzigd; de gedeelde set mag hier níet worden ingevuld.
- Backend, `specs/openapi.yaml`, database en de statuswaarden zelf: ongewijzigd. Ook `frontend-reader/` blijft ongemoeid (heeft geen podcastschermen).

## Acceptance criteria

1. `frontend/lib/models/models.dart` bevat een publieke, top-level constante `kPodcastInProgressStatuses` als `Set<String>` met exact deze zes waarden: `PENDING`, `DETERMINING_TOPICS`, `GENERATING_SCRIPT`, `GENERATING_AUDIO`, `TRANSLATING`, `TTS_GENERATING`, voorzien van een comment die uitlegt dat dit de enige plek voor deze lijst is en waarom.
2. `grep -n "DETERMINING_TOPICS" frontend/lib/screens/` levert nog uitsluitend treffers op in de `_statusLabel`/`_phaseLabel`-`switch`-blokken (`podcast_screen.dart`, `podcast_detail_screen.dart`, `rss_podcast_detail_screen.dart`) — geen enkele "is-bezig"-lijst meer in de schermen.
3. `_isInProgress` in `podcast_screen.dart` beslist via `kPodcastInProgressStatuses.contains(status)`; het spinner- en labelgedrag in de lijst (`:103`, `:113-115`) is voor alle bestaande statussen ongewijzigd.
4. `_maybePoll` in `podcast_screen.dart` bepaalt `pending` via dezelfde gedeelde set. Gevolg: staat er minstens één podcast op `TRANSLATING` of `TTS_GENERATING`, dan wordt de 4-secondentimer gestart c.q. niet gestopt; staat er geen enkele podcast meer in een bezig-status, dan wordt de timer nog steeds gestopt en op `null` gezet.
5. `podcast_detail_screen.dart` heeft geen eigen `_inProgressStatuses` meer; de poll-beslissing op `:52` gebruikt de gedeelde set en het detailscherm blijft pollen bij exact dezelfde statussen als voorheen (gedragsneutraal).
6. Er is één nieuwe test in `frontend/test/` die faalt zodra de gedeelde set en de "bezig"-weergave van het overzichtsscherm uit elkaar lopen (zie aanname 2 voor de vorm).
7. `flutter analyze` in `frontend/` geeft geen nieuwe waarschuwingen (geen ongebruikte imports of dode private velden achterlaten) en `flutter test` in `frontend/` slaagt volledig, inclusief de vijf bestaande testbestanden.
8. Geen wijzigingen buiten `frontend/lib/models/models.dart`, `frontend/lib/screens/podcast_screen.dart`, `frontend/lib/screens/podcast_detail_screen.dart` en één nieuw bestand in `frontend/test/`.

## Aannames

1. **Naamgeving en vorm.** `kPodcastInProgressStatuses` als `const Set<String>` op top-level in `models.dart` (het `k`-prefix is de Dart/Flutter-conventie voor globale constanten). `models.dart` heeft nu nog geen top-level constanten; dit is de eerste. Een `Set` in plaats van een `List` zodat `contains` semantisch klopt en volgorde geen betekenis krijgt.
2. **Vorm van de test.** `_isInProgress` en `_statusLabel` zijn private, dus niet direct testbaar. De test wordt daarom een unittest (bijv. `frontend/test/podcast_in_progress_statuses_test.dart`) die asserteert dat `kPodcastInProgressStatuses` exact gelijk is aan de zes verwachte statussen, met een comment die uitlegt dat dit de enige bron is voor zowel spinner als poll-timer. Een aanvullende widgettest die `PodcastScreen` rendert met een podcast op `TRANSLATING` en de aanwezigheid van de `CircularProgressIndicator` controleert mag, maar is niet verplicht — de story vraagt om één test.
3. **Geen gedragsverandering buiten de fix.** De enige waarneembare wijziging voor de gebruiker is dat het podcast-overzicht nu ook tijdens `TRANSLATING`/`TTS_GENERATING` elke 4 seconden ververst, waardoor de spinner vanzelf verdwijnt zodra de vertaling klaar is. Statuswaarden, labelteksten, polling-interval (4s) en de rest van de UI blijven identiek.
4. **Backend-contract.** De zes statuswaarden komen ongewijzigd uit de backend; `specs/openapi.yaml` hoeft niet aangepast te worden en er is geen contract- of databasewijziging.
5. **Verificatie.** `flutter analyze` en `flutter test` worden in `frontend/` gedraaid. Let op de bekende valkuil: `flutter pub get` kan `pubspec.lock` muteren — die is getracked, dus een ongewijzigde lockfile committen (of het verschil bewust melden).

## Eindsamenvatting

Alles gelezen: `.task.md`, het worklog (`docs/stories/worklog/SF-2066-worklog.md`), het story-log en de volledige diff `main...HEAD`.

## Eindsamenvatting SF-2066 — Gedeelde "podcast is nog bezig"-statuslijst

**Probleem.** De lijst met statussen waarin een podcast nog bezig is, stond op drie plekken los van elkaar in de Flutter-app. De kopie die de auto-refresh (poll-timer) van het podcast-overzicht aanstuurde miste `TRANSLATING` en `TTS_GENERATING`. Gevolg: tijdens vertalen toonde het overzicht wél een spinner met "Vertalen…", maar het scherm ververste zichzelf niet meer — het rondje bleef eindeloos draaien tot de gebruiker handmatig verversde.

**Wat is gebouwd.**
- Eén gedeelde, publieke constante `kPodcastInProgressStatuses` (`Set<String>` met de zes bezig-statussen) in `frontend/lib/models/models.dart`, met een comment die vastlegt dat dit de enige plek voor deze lijst is en waarom.
- `podcast_screen.dart`: zowel de spinner-/labelbeslissing (`_isInProgress`) als de poll-timer (`_maybePoll`) lezen nu die ene set. Dit laatste is de eigenlijke fix; de timer-logica (starten, stoppen, op `null` zetten) is verder ongewijzigd.
- `podcast_detail_screen.dart`: eigen kopie `_inProgressStatuses` verwijderd, verwijst naar de gedeelde set (gedragsneutraal — het waren dezelfde zes statussen).
- Nieuwe test `frontend/test/podcast_in_progress_statuses_test.dart` die de zes statussen vastlegt en faalt zodra spinner en poll-timer weer uit elkaar zouden lopen.

**Keuzes.**
- `const Set<String>` op top-level met `k`-prefix (Dart/Flutter-conventie); een set in plaats van een lijst, zodat `contains` semantisch klopt en volgorde geen betekenis krijgt.
- `Podcast.translationInProgress` houdt bewust zijn eigen, smallere lijst (`PENDING`/`TRANSLATING`/`TTS_GENERATING`): een vertaling doorloopt nooit de generatie-statussen. Dat is geen vierde kopie van dezelfde vraag en is niet vervangen; de comment bij de nieuwe constante legt dit uit.
- De test toetst de gedeelde constante (unittest) in plaats van de private `_isInProgress`/`_statusLabel`-functies, die niet direct testbaar zijn.

**Wat is getest.**
- Alle 8 acceptatiecriteria statisch geverifieerd door reviewer én tester, inclusief de grep-check dat er in `frontend/lib/screens/` geen bezig-lijst meer staat (alleen nog treffers in de label-switches).
- `flutter analyze` in `frontend/`: 7 issues, exact dezelfde pre-existing infos als op `main` — geen nieuwe waarschuwingen. `flutter test`: 27/27 groen (was 25; +2 nieuw). `pubspec.lock` ongewijzigd.
- Backend-vangnet `mvn -B clean verify` groen (backend is niet gewijzigd).
- Live bewijs op de PR-preview: met een gemockte podcast op `TRANSLATING` toont het overzicht de spinner én blijft het elke 4 seconden pollen (3 polls in 14 s). Na omzetten naar `DONE` werkt de kaart zichzelf zonder handmatige refresh bij naar "Klaar" en stopt de timer (0 polls in 14 s). Daarmee is de oorspronkelijke bug in beide richtingen aantoonbaar weg. Het wegwerp-testaccount is na afloop opgeruimd.

**Bewust niet gedaan.** De dubbele `_statusLabel` in overzicht/detail, de afwijkende `_phaseLabel` in `rss_podcast_detail_screen.dart`, de duplicatie tussen `feed_screen.dart` en `rss_screen.dart`, en alles in backend, `specs/openapi.yaml` en database — allemaal buiten scope volgens de story. Er zijn geen wijzigingen buiten de drie frontend-bestanden, het nieuwe testbestand en de story-documentatie.

**Restrisico.** Geen bekende blockers of bugs. De wijziging is klein en gedragsneutraal behalve de bedoelde fix.

<!-- deploy-summary:start -->
Als je een podcast laat vertalen, ververst het podcastoverzicht zichzelf nu automatisch. Het draaiende rondje met "Vertalen…" verdwijnt vanzelf zodra de vertaling klaar is, dus je hoeft de pagina niet meer handmatig te verversen. Verder verandert er niets aan het scherm.
<!-- deploy-summary:end -->
