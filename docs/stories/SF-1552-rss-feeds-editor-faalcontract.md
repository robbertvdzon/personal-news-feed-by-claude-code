# SF-1552 - [Audit] Trek de RSS-feeds-editor gelijk met de podcast-bronnen-editor: toon serverfouten en muteer de UI pas na een geslaagde PUT

## Story

[Audit] Trek de RSS-feeds-editor gelijk met de podcast-bronnen-editor: toon serverfouten en muteer de UI pas na een geslaagde PUT

<!-- refined-by-factory -->

## Samenvatting

Op het scherm met RSS-feeds en podcast-bronnen staan twee lijstjes die hetzelfde doen, maar zich anders gedragen als er iets misgaat. Voeg je bij de podcast-bronnen een URL toe die de server weigert, dan zie je een rode melding en verdwijnt de URL niet in de lijst. Bij de RSS-feeds zie je niets: het invoerveld wordt leeggemaakt en de URL blijft in de lijst staan, terwijl de server hem heeft geweigerd. Pas na een herstart of verversen merk je dat hij er nooit is geweest.

Deze story trekt de RSS-feeds gelijk met de podcast-bronnen: de lijst verandert pas als de server het opslaan heeft bevestigd, en gaat er iets mis, dan krijg je dezelfde rode melding te zien met de uitleg van de server. En passant wordt een fout hersteld waardoor die melding nu ook bij podcasts onleesbare technische tekst toont in plaats van de Nederlandse foutmelding.

## Scope

Alleen frontend (`frontend/`) plus twee documentatiebestanden. Geen backend-, API- of contractwijziging.

**In scope**
1. `frontend/lib/providers/data_providers.dart` — `RssFeedsNotifier.save`: volgorde gelijktrekken met `PodcastFeedsNotifier.save` (:227-236): eerst `PUT /api/rss-feeds`, dan `LocalCache.saveObject`, en pas daarna `state = AsyncData(feeds)`. Neem een WHY-comment op in dezelfde geest als bij de podcast-variant.
2. `frontend/lib/screens/rss_feeds_screen.dart` — `_RssFeedsEditorState`: een `_busy`-vlag en een `Future<void> _save(...)`-methode naar het model van `_PodcastFeedsEditorState._save` (:181-202), met `await`, `try/catch`, rode snackbar bij fout, en `finally`/`mounted`-afhandeling van `_busy`. Zowel de verwijder-knop (:62-64) als `_add()` (:81-87) lopen via die methode. Invoerveld leegmaken alleen bij succes van een toevoeg-actie.
3. Dezelfde busy-affordances als bij podcast: invoerveld `enabled: !_busy`, `onSubmitted`/verwijder-knop uitgeschakeld tijdens `_busy`, en de +-knop vervangen door een kleine `CircularProgressIndicator` tijdens `_busy`.
4. Message-extractie: één gedeelde implementatie die beide editors gebruiken, met de bug gecorrigeerd — zoek op het JSON-veld `"error"` in plaats van `"message"` (`GlobalExceptionHandler` in `common/Exceptions.kt` geeft overal `{"error": "..."}`). Bestaande raw-body-fallback en de "lege body"-fallback blijven behouden. Werk de nu-onjuiste doc-comment (die naar Spring's `message`-veld verwijst) bij.
5. `specs/frontend-spec.md` — het RSS-blok (regel ~385) beschrijft dezelfde server-side validatie/snackbar-verwachting als het podcast-blok (regel ~393).
6. `e2e/scenarios/settings-scenario.md` §3a — verwachting aanvullen naar analogie van de podcast-regel in §3b (ongeldige URL → rode snackbar met Nederlandse foutmelding, URL wordt níét aan de lijst toegevoegd). Ook de regel in "Verwacht resultaat" over de RSS-editor meenemen.
7. Widget-tests in `frontend/test/rss_feeds_screen_test.dart` uitbreiden met een faal-pad (fake-notifier die een `ApiException(400, '{"error":"…"}')` gooit): snackbar met de servertekst, lijst ongewijzigd, invoerveld niet geleegd.

**Buiten scope**
- Elke wijziging in `newsfeedbackend/` (validatie, statuscodes en responsvorm blijven exact zoals ze zijn).
- Het samenvoegen/generaliseren van de twee editors tot één widget — ze blijven gescheiden, alleen hun faalcontract wordt gelijk.
- `frontend-reader/` (heeft geen instellingen-scherm).
- Redirect-gebaseerde SSRF en andere validatie-uitbreidingen.

## Acceptance criteria

1. Bij het toevoegen van een RSS-feed-URL die de backend met HTTP 400 weigert: er verschijnt een rode snackbar met de Nederlandse foutmelding uit het `error`-veld van de responsbody (bv. "Ongeldige RSS-feed-URL '…': …"), de URL wordt **niet** aan de lijst toegevoegd, en het invoerveld behoudt de ingetypte tekst.
2. Bij het toevoegen van een geldige RSS-feed-URL blijft het gedrag identiek aan vandaag: de URL komt in de lijst en het invoerveld wordt geleegd.
3. Bij het verwijderen van een RSS-feed die de backend weigert blijft de feed zichtbaar in de lijst en verschijnt dezelfde rode snackbar; bij succes verdwijnt hij zoals nu.
4. `RssFeedsNotifier.save` muteert `state` pas nadat `PUT /api/rss-feeds` én `LocalCache.saveObject` geslaagd zijn; bij een fout blijft zowel de state als de lokale cache ongewijzigd en propageert de exception naar de caller.
5. Tijdens een lopende opslag-actie in de RSS-editor zijn het invoerveld, de +-knop en de verwijder-knoppen uitgeschakeld en is er een spinner zichtbaar op de plek van de +-knop — analoog aan de podcast-editor.
6. Beide editors gebruiken dezelfde message-extractie; een 400-body `{"error":"Ongeldige RSS-feed-URL 'x': reden"}` levert in de snackbar de tekst `Ongeldige RSS-feed-URL 'x': reden` op, niet de rauwe JSON. Een niet-JSON of niet-matchende body valt terug op de rauwe body, een lege body op de bestaande standaardtekst.
7. `flutter test` in `frontend/` slaagt, inclusief de nieuwe faal-pad-tests; bestaande tests in `rss_feeds_screen_test.dart` blijven groen.
8. `specs/frontend-spec.md` §9a beschrijft voor het RSS-blok dezelfde validatie/snackbar-verwachting als voor het podcast-blok, en `e2e/scenarios/settings-scenario.md` §3a bevat de aangevulde verwachting.
9. Geen enkel bestand onder `newsfeedbackend/` is gewijzigd.

## Aannames

- De gedeelde message-extractie wordt een private top-level functie in `rss_feeds_screen.dart` (beide editors zitten in dat bestand); een aparte helper-file is niet nodig.
- De RSS-`_save` krijgt dezelfde vorm als de podcast-variant, waarbij de optionele fallback-melding tevens bepaalt of het invoerveld na succes geleegd wordt (`null` voor verwijder-acties, een tekst voor toevoeg-acties). Als generieke fallback bij een niet-400-fout wordt "Fout bij opslaan: …" gebruikt, net als nu bij podcast.
- Het e2e-scenario is een handmatig/AI-gedreven markdown-scenario; er wordt geen geautomatiseerde e2e-code toegevoegd. Voor een ongeldige URL in §3a volstaat een voorbeeld dat `SsrfUrlValidator` weigert (bv. een loopback- of niet-http(s)-URL).
- Het feit dat de snackbar bij podcast-fouten vandaag rauwe JSON toont, is een bestaande bug die met deze story meelift; er is geen aparte story voor nodig.

## Eindsamenvatting

Alles gelezen: `.task.md`, worklog, story-doc en de volledige diff t.o.v. `main`.

## Eindsamenvatting SF-1552 — RSS-feeds-editor gelijkgetrokken met de podcast-bronnen-editor

**Wat is gebouwd**

De RSS-feeds-editor op het instellingen-scherm gedraagt zich nu bij fouten precies zoals de podcast-bronnen-editor:

- **Lijst verandert pas na bevestiging van de server.** `RssFeedsNotifier.save` doet eerst de `PUT /api/rss-feeds`, dan de lokale cache-write, en pas daarna de UI-update. Weigert de server, dan blijven zowel de lijst als de lokale cache ongewijzigd (voorheen bleef een geweigerde URL zichtbaar tot een herstart).
- **Rode foutmelding.** Bij een afwijzing verschijnt een rode snackbar met de Nederlandse tekst van de server ("Ongeldige RSS-feed-URL '…': …"); het invoerveld behoudt de ingetypte tekst zodat de gebruiker hem kan corrigeren. Bij succes wordt het veld wél geleegd. Hetzelfde geldt voor verwijderen.
- **Zichtbare bezig-status.** Tijdens het opslaan zijn het invoerveld en de verwijderknoppen uitgeschakeld en staat er een spinner op de plek van de +-knop.
- **Meeliftende bugfix.** De foutmelding-extractie las het verkeerde veld uit de serverrespons (`message` i.p.v. `error`), waardoor de podcast-snackbar tot nu toe rauwe JSON toonde. Dat is gecorrigeerd en de extractie is nu één gedeelde functie die beide editors gebruiken — dus voortaan leesbaar Nederlands in beide lijstjes.
- **Documentatie bijgewerkt:** `specs/frontend-spec.md` §9a en het e2e-scenario `settings-scenario.md` §3a beschrijven de nieuwe verwachting.

**Gemaakte keuzes**

- De twee editors blijven aparte widgets; alleen het faalgedrag en de melding-extractie zijn gelijkgetrokken. Samenvoegen tot één widget was expliciet buiten scope.
- Gedrag bij een geldige URL is exact hetzelfde gebleven.
- De extractie gebruikt een simpele patroon-match i.p.v. een volledige JSON-parser, in lijn met de bestaande code. Bekende, niet-blokkerende beperking: een aanhalingsteken midden in een servertekst zou de melding afkappen.
- Bij een fout die géén HTTP 400 is toont een toevoeg-actie alleen "Kon feed niet opslaan" zonder technisch detail — bewust gespiegeld aan de podcast-editor.

**Wat is getest**

- Live op de preview-omgeving (pr-195, geverifieerd op de juiste build): een geweigerde URL (`http://127.0.0.1/rss`) geeft de rode snackbar met de Nederlandse servertekst, komt niet in de lijst en laat het invoerveld intact; een geldige BBC-feed wordt normaal toegevoegd en het veld leeggemaakt; verwijderen werkt zoals voorheen; de spinner/disabled-toestand is met een kunstmatig vertraagde aanroep zichtbaar gemaakt. Screenshots zijn vastgelegd.
- Geautomatiseerd: `flutter test` → 19/19 groen (was 15) met vier nieuwe tests (toevoeg-faalpad, verwijder-faalpad, bezig-status, podcast-faalpad). `flutter analyze` zonder nieuwe meldingen. Als vangnet draaide de volledige backend-build/testsuite: BUILD SUCCESS, 145 tests, 0 fouten.
- Gecontroleerd dat er geen enkel backend-bestand is gewijzigd.

**Bewust niet gedaan**

- Geen backend-wijzigingen: validatie, statuscodes en responsvorm blijven exact zoals ze waren.
- Geen samenvoeging van de twee editors tot één component.
- Geen uitbreiding van de URL-validatie (bijv. redirect-gebaseerde SSRF).
- `frontend-reader/` is niet geraakt (heeft geen instellingen-scherm).
- Het faalpad bij *verwijderen* is niet live uit te lokken (de server weigert een kleinere geldige lijst nooit) en is daarom alleen met een widgettest afgedekt.

*Opmerking over het contract:* de rol-instructies in `docs/factory/agents/summarizer.md` noemen `{"phase":"summary-finished"}`, terwijl het factory-contract `{"phase":"summarized"}` voorschrijft. Ik volg het factory-contract; de repo-doc is verouderd en zou bijgewerkt mogen worden.
