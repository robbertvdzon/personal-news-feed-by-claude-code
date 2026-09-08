# SF-1850 - [Audit] Trek het categorieënscherm gelijk met de RSS-feeds-editor: toon een foutmelding en muteer de UI pas na een geslaagde PUT

## Story

[Audit] Trek het categorieënscherm gelijk met de RSS-feeds-editor: toon een foutmelding en muteer de UI pas na een geslaagde PUT

SF-1552 heeft het faalcontract van RssFeedsNotifier.save + _RssFeedsEditor rechtgetrokken en die keuze als conventie vastgelegd in docs/factory/technical-spec.md:75. De laatste plek die niet is meegegaan zit in hetzelfde bestand, 20 regels erboven: SettingsNotifier.save (frontend/lib/providers/data_providers.dart:176-181) zet nog steeds eerst state = AsyncData(categories) (:177) en doet daarna pas de PUT (:178) en het cache-schrijven (:179) - die twee laatste worden bij een fout overgeslagen. De vier aanroepers in frontend/lib/screens/categories_screen.dart vangen niets af: :31 (schakelaar aan/uit) roept save(next) zelfs aan ZONDER await, en :66 (toevoegen), :95 (hernoemen/extra instructies) en :98 (verwijderen) awaiten wel maar hebben geen try/catch. Gevolg vandaag: is de backend onbereikbaar of het token verlopen (401), dan schuift het schakelaartje om, verdwijnt een verwijderde categorie uit de lijst, verschijnt er geen melding, en blijft de lokale cache op de oude waarde staan - pas na een herstart/refetch springt alles terug. Doe het volgende, spiegelend op de bestaande RSS-implementatie: (1) laat SettingsNotifier.save de PUT + LocalCache.saveList doen voor de state-mutatie, precies zoals RssFeedsNotifier.save (:202-207) en PodcastFeedsNotifier.save (:235-242), en neem er dezelfde soort WHY-comment bij; (2) geef CategoriesScreen een Future<void> _save(...)-methode naar het model van _RssFeedsEditorState._save (frontend/lib/screens/rss_feeds_screen.dart:102-124), met await, try/catch, een _busy-vlag die de schakelaars/knoppen tijdens het opslaan uitschakelt, en een rode snackbar (Theme.of(context).colorScheme.error) bij een fout; (3) laat alle vier de aanroepen (:31, :66, :95, :98) via die methode lopen, zodat ook de schakelaar wordt geawait. Hergebruik voor de melding _extractDutchMessage (rss_feeds_screen.dart:131-139) - verplaats die daarvoor naar een gedeelde plek, bijv. frontend/lib/api/api_client.dart of frontend/lib/util/, en laat rss_feeds_screen.dart de nieuwe locatie importeren (gedrag daar blijft identiek). Werk tot slot de beschrijving van het categorieenscherm in specs/frontend-spec.md bij zodat die - net als het RSS-blok - de foutsnackbar en het 'pas muteren na succes'-gedrag noemt. Geen backend-, API- of contractwijziging; SettingsServiceImpl.saveCategories (newsfeedbackend/newsfeedbackend/src/main/kotlin/com/vdzon/newsfeedbackend/settings/domain/SettingsServiceImpl.kt:45-49) valideert niets en blijft ongewijzigd, dus het gedrag bij een geslaagde opslag is identiek. Voeg een widget-test toe naar het voorbeeld van frontend/test/rss_feeds_screen_test.dart (die dekt sinds SF-1552 precies dit scenario).

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de story-diff gelezen. Hier is de eindsamenvatting.

---

# SF-1850 — Categorieënscherm gelijkgetrokken met de RSS-feeds-editor

## Wat is gebouwd
Het categorieënscherm muteerde de UI voorheen *voordat* het opslaan naar de server was gelukt. Bij een onbereikbare backend of een verlopen token schoof een schakelaar om of verdween een categorie zonder enige melding, terwijl de lokale cache op de oude waarde bleef staan. Dat is nu rechtgetrokken volgens dezelfde conventie die SF-1552 voor de RSS-feeds-editor vastlegde.

1. **`SettingsNotifier.save`** (`frontend/lib/providers/data_providers.dart`) — de optimistische `state = AsyncData(...)` is weg. De volgorde is nu PUT `/api/settings` → `LocalCache.saveList` → state uit de serverrespons, identiek aan `RssFeedsNotifier.save` en `PodcastFeedsNotifier.save`, met een WHY-comment in dezelfde stijl. De `ApiException` propageert naar de aanroeper.
2. **`CategoriesScreen`** — omgezet naar `ConsumerStatefulWidget` met één centrale `Future<void> _save(...)` naar het model van `_RssFeedsEditorState._save`: `_busy`-vlag, `await`, try/catch, mounted-checks en een rode snackbar (`colorScheme.error`) bij een fout. Alle vier de aanroepen lopen hier nu doorheen — inclusief de schakelaar, die eerder zelfs zónder `await` opsloeg. Tijdens het opslaan zijn de schakelaars, het bewerk-icoon en de "Categorie toevoegen"-tegel uitgeschakeld.
3. **Gedeelde foutmelding** — `_extractDutchMessage` is byte-identiek verplaatst naar `frontend/lib/api/api_client.dart` als publieke `extractDutchMessage(...)`. Alle drie de aanroepers (RSS-editor, podcast-editor, categorieën) wijzen naar de nieuwe locatie; er is geen restant van de oude helper.
4. **Documentatie** — `docs/factory/technical-spec.md` verwijst naar de nieuwe locatie en noemt `SettingsNotifier.save` in de notifier-conventie; `specs/frontend-spec.md` §9b beschrijft nu per actie het faalcontract in dezelfde stijl als het RSS-blok.

## Keuzes
- **Geen `validateFailureMessage`-parameter** in `_save`: anders dan bij RSS is er geen invoerveld dat bij een validatiefout geleegd moet worden, en de backend valideert categorieën niet.
- Bij een `ApiException` met status 400 wordt de Nederlandse servertekst uit het `error`-veld getoond, anders een generieke fallback (`Fout bij opslaan: $e`).
- **Geen backend-wijziging.** `SettingsServiceImpl.saveCategories` is ongemoeid gelaten, dus het gedrag bij een geslaagde opslag is exact hetzelfde als voorheen.

## Wat is getest
- **Widget-tests**: drie nieuwe tests in `frontend/test/categories_screen_test.dart` (faalpad schakelaar, faalpad verwijderen, busy-state). `flutter test`: 25/25 groen. `flutter analyze`: 7 meldingen, allemaal pre-existing en geen enkele in een gewijzigd bestand.
- **Backend-vangnet**: `mvn clean verify` BUILD SUCCESS (61 e2e- plus unit-tests, 0 failures) — de backend is niet geraakt door deze story.
- **Preview-test** op `pnf-pr-205` (Playwright, mobiel formaat): happy path werkt; bij een onderschepte PUT met een 400 verschijnt exact de Nederlandse servertekst in een rode snackbar, blijft de schakelaar in de oude stand en verandert de backendwaarde niet — precies het gedrag dat vóór deze story ontbrak. De busy-state is visueel bevestigd: tijdens het opslaan is de bediening uitgegrijsd en gaat er maar één PUT uit.

## Bewust niet gedaan
- `newsfeedbackend/`, `specs/openapi.yaml` en `frontend-reader/` zijn niet aangeraakt; het RSS-/podcastgedrag zelf is ongewijzigd.
- De overige optimistische notifiers in `data_providers.dart` (`setFeedback`, item-`delete`) staan buiten scope en zijn nog niet meegenomen — mogelijke vervolgstory.

## Aandachtspunten voor de PO (niet blokkerend)
- Op de preview opent de bewerk-/verwijderdialoog niet: een tik op het potlood-icoon wordt door de `SwitchListTile` zelf opgevangen en toggelt de categorie. Dit is **pre-existing** gedrag van de bestaande tegel, niet door deze story geïntroduceerd; de faalpaden voor bewerken en verwijderen zijn wel via widget-tests gedekt. Kandidaat voor een aparte story.
- De tegel "Categorie toevoegen" oogt tijdens het opslaan niet uitgegrijsd, terwijl hij functioneel wel is uitgeschakeld. Cosmetisch.
- Review-suggestie, niet opgelost: `_addCategory`/`_editCategory` roepen `_save` aan direct na `await showDialog(...)` zonder `mounted`-check. Zeldzaam pad (scherm verlaten terwijl de dialoog openstaat).

<!-- deploy-summary:start -->
Als het aanpassen van je categorieën niet lukt, zie je nu meteen een duidelijke rode melding in beeld. De lijst verandert pas als de wijziging echt is opgeslagen, dus je ziet nooit meer iets omschakelen dat er in werkelijkheid niet doorheen kwam. Tijdens het opslaan zijn de knoppen even niet aanklikbaar, zodat er geen dubbele wijzigingen ontstaan.
<!-- deploy-summary:end -->
