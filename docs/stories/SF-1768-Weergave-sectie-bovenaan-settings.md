# SF-1768 - grote text bovenaan

## Story

grote text bovenaan

<!-- refined-by-factory -->

## Samenvatting

In het instellingenscherm van de app staat de instelling voor grote tekst helemaal onderaan de pagina. Je moet dus eerst door alle andere instellingen heen scrollen voordat je hem ziet.

Deze instelling moet weer bovenaan komen te staan, zodat je hem meteen ziet als je het instellingenscherm opent.

De schakelaar zelf werkt precies zoals nu; alleen de plek op de pagina verandert. De volgorde van alle andere onderdelen blijft ongewijzigd.

## Scope

- Alleen `frontend/lib/screens/settings_screen.dart`: het blok met de sectiekop `Weergave` + de `SwitchListTile` "Grote tekst" (nu regels ~123-129, als laatste sectie ná de admin-only `Beheer`-sectie) verplaatsen naar de bovenkant van de `ListView`.
- Bijwerken van de bestaande widgettests in `frontend/test/settings_screen_test.dart` die op de huidige onderste positie asserteren (regels ~64-106: "Weergave-sectie staat onderaan, ná Debug" en "Weergave-sectie staat ook ná de admin-only Beheer-sectie").
- Buiten scope: `frontend-reader` (heeft geen instellingenscherm), de backend, de `appearanceProvider`/`AppearanceState`-logica in `frontend/lib/providers/data_providers.dart` en de toepassing van `largeFont` in `frontend/lib/main.dart:139`.
- Buiten scope: het terugdraaien of aanpassen van de story-documentatie van SF-809 in `docs/stories/`.

## Acceptance criteria

- De sectie **Weergave** (kop "Weergave" + `SwitchListTile` "Grote tekst") is de eerste sectie van het instellingenscherm en staat boven **Over deze app**.
- De onderlinge volgorde van alle overige secties blijft ongewijzigd: Over deze app → Account → Categorieën → RSS feeds → Achtergrond-taken → Opruimen → Debug → (admin-only) Beheer.
- Onderaan het scherm blijft geen restant van de Weergave-sectie achter: na de admin-only `Beheer`-sectie eindigt de lijst, zonder overbodige of dubbele `Divider`.
- De dividers tussen secties zijn consistent met de rest van het scherm: precies één scheiding tussen Weergave en Over deze app, en geen dubbele divider tussen Debug en Beheer.
- De "Grote tekst"-schakelaar is functioneel identiek: de waarde komt uit `appearanceProvider` (`largeFont`) en omzetten roept `setLarge(v)` aan, met hetzelfde zichtbare tekstvergrotings-effect (`TextScaler.linear(1.38)`).
- De widgettests in `frontend/test/settings_screen_test.dart` asserteren de nieuwe volgorde: Weergave staat vóór (kleinere `dy` dan) "Over deze app", "Account", "Categorieën", "Debug" en — in de admin-variant — "Beheer".
- De test die verifieert dat de switch `setLarge(true)` aanroept blijft bestaan en slaagt.
- `flutter test` in `frontend/` is groen en `flutter analyze` levert geen nieuwe warnings op.

## Aannames

- "Bovenaan" wordt geïnterpreteerd als: Weergave wordt de allereerste sectie van het scherm, dus ook boven "Over deze app" — niet de oude positie van vóór SF-809 (direct onder Account).
- "De settings" verwijst naar het instellingenscherm van de hoofd-app (`frontend`); `frontend-reader` bevat geen instellingenscherm en is daarmee niet betrokken.
- Dit is een pure herschikking van de sectievolgorde in één bestand; er wordt geen functionaliteit toegevoegd, verwijderd of gewijzigd, en er is geen backend- of API-impact.
- Deze wijziging draait de sectievolgorde-keuze van SF-809 bewust terug; dat is de expliciete wens van deze story en geen regressie.

## Eindsamenvatting

Alle context gelezen (`.task.md`, worklog SF-1768, story-diff `main...HEAD`). Hieronder de eindsamenvatting.

---

## Eindsamenvatting SF-1768 — "Grote tekst" bovenaan de instellingen

### Wat is gebouwd
De instelling **Grote tekst** staat niet langer helemaal onderaan het instellingenscherm, maar is nu de **eerste sectie** die je ziet als je de instellingen opent. Je hoeft er dus niet meer naartoe te scrollen.

De nieuwe volgorde van het instellingenscherm:
**Weergave** → Over deze app → Account → Categorieën → RSS feeds → Achtergrond-taken → Opruimen → Debug → (alleen voor beheerders) Beheer.

De onderlinge volgorde van alle overige secties is ongewijzigd gebleven.

### Gemaakte keuzes
- **"Bovenaan" = echt de allereerste sectie**, dus ook boven "Over deze app" — niet de oude positie van vóór SF-809 (direct onder Account). Dit draait de volgordekeuze van SF-809 bewust terug; dat is de expliciete wens van deze story en geen regressie.
- De **scheidingslijn** die eerst vóór het Weergave-blok stond, staat nu eráchter (tussen Weergave en Over deze app). Zo staat er geen streep bovenaan het scherm en blijft er onderaan geen restant of dubbele lijn achter — in zowel de gewone als de beheerdersvariant.
- De schakelaar zelf is **functioneel exact hetzelfde**: dezelfde bron van de waarde en hetzelfde vergrotingseffect op de tekst. Er is geen functionaliteit toegevoegd of verwijderd.
- Wijziging beperkt tot **één schermbestand plus de bijbehorende tests**; geen backend-, API- of datawijziging.

### Wat is getest
- **Automatische tests (frontend):** 22 tests groen. De twee bestaande positietests zijn omgedraaid naar de nieuwe volgorde, en er zijn **twee nieuwe tests** toegevoegd die de scheidingslijnen borgen (7 in de gewone variant, 8 voor beheerders). De test die controleert dat de schakelaar de instelling daadwerkelijk omzet, is ongewijzigd blijven bestaan en slaagt.
- **Codekwaliteitscheck:** geen nieuwe waarschuwingen; de 7 resterende meldingen zijn pre-existing en zitten in andere schermen.
- **Live getest op de preview-omgeving** (mobiel formaat 420x900) op de juiste revisie (buildhash gecontroleerd via "Over deze app"): Weergave staat als eerste sectie bovenaan, de rest van de volgorde klopt, onderaan de lijst is geen restant of losse streep te zien, en de schakelaar vergroot/verkleint alle teksten zichtbaar en herstelt netjes. Screenshots zijn vastgelegd.
- **Backend-vangnet** (volledige build + tests) draaide succesvol; bevestigt dat er geen zij-effecten zijn.
- Reviewer heeft de volledige story-diff beoordeeld: **akkoord, geen blockers**.

### Bewust niet gedaan
- `frontend-reader` is niet aangeraakt (die app heeft geen instellingenscherm).
- Backend, de onderliggende weergave-instellingenlogica en de toepassing van de tekstvergroting zijn ongemoeid gelaten.
- De story-documentatie van SF-809 is niet teruggedraaid of aangepast.
- De beheerder-only **Beheer**-sectie is niet live op de preview getest (daarvoor was geen beheerdersaccount beschikbaar); die variant is afgedekt met widgettests.

### Aandachtspunt voor de PO
Voor de live test is een wegwerp-testaccount aangemaakt omdat de reguliere testcredentials niet beschikbaar waren; dat account is na afloop weer verwijderd.
