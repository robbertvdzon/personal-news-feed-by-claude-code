# SF-809 - Weergave sectie naar onderen in settings pagina

## Story

Weergave sectie naar onderen in settings pagina

<!-- refined-by-factory -->

## Scope
In het instellingenscherm van de Flutter-app (`frontend`) staat de sectie **Weergave** (met de switch **Grote tekst**) nu vrij bovenin, direct onder **Account**. Deze sectie moet als laatste sectie onderaan de instellingenpagina komen te staan.

Puur een frontend-herschikking van de volgorde van secties in `frontend/lib/screens/settings_screen.dart`. Geen wijziging aan gedrag van de switch zelf, aan de `appearanceProvider`, aan andere secties of aan de backend. `frontend-reader` heeft geen instellingenscherm en valt buiten scope.

## Acceptance criteria
- De sectie **Weergave** (kop "Weergave" + de `SwitchListTile` "Grote tekst") staat als laatste sectie onderaan het instellingenscherm, ná alle andere secties (Over deze app, Account, Categorieën, RSS-feeds, Achtergrond-taken, Opruimen, Debug en — voor admins — Beheer).
- De overige secties behouden hun onderlinge volgorde; alleen de Weergave-sectie is verplaatst.
- De "Grote tekst"-switch blijft functioneel identiek: waarde komt uit `appearanceProvider` en aanpassen roept `setLarge` aan, met hetzelfde zichtbare effect als voorheen.
- Er is een correcte visuele scheiding (`Divider`) tussen de Weergave-sectie en de sectie erboven, consistent met de rest van het scherm; er blijft geen overbodige/dubbele divider achter op de oude plek.
- De onderste sectie blijft leesbaar boven de systeem-navigatiebalk (bestaande bottom-inset-padding blijft behouden).

## Aannames
- "Helemaal naar beneden" wordt geïnterpreteerd als: de Weergave-sectie wordt de laatste sectie van het scherm, ook ná de admin-only "Beheer"-sectie (die alleen voor admins zichtbaar is). Voor niet-admins staat Weergave dus onder "Debug".
- Er worden geen nieuwe weergave-opties toegevoegd; het gaat uitsluitend om verplaatsen van de bestaande sectie.

## Eindsamenvatting

Ik heb alle context: `.task.md`, het worklog en de daadwerkelijke diff. Hier is de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-809: Weergave-sectie naar onderen in settings-pagina

### Wat is gebouwd
In het instellingenscherm van de Flutter-app (`frontend/lib/screens/settings_screen.dart`) is de sectie **Weergave** (kop "Weergave" + de switch "Grote tekst") verplaatst van bovenin (direct onder Account) naar **de laatste sectie onderaan het scherm**. Het is een pure herschikking van de sectievolgorde; er is geen functionaliteit toegevoegd of gewijzigd.

Concreet in de code:
- Het Weergave-blok is weggehaald tussen Account en Categorieën, inclusief de bijbehorende extra `Divider()` — daar blijft nu precies één divider over (geen dubbele scheiding meer).
- Het blok is opnieuw geplaatst als laatste sectie, **ná** het admin-only Beheer-blok, voorafgegaan door één `Divider()`. Die divider staat buiten de admin-conditie, dus zowel admins als niet-admins krijgen een correcte scheiding.
- De switch-logica is ongewijzigd: `value: appearance.largeFont`, `onChanged → setLarge`. Providers en backend zijn niet aangeraakt.
- De bestaande bottom-inset-padding op de ListView is behouden, zodat de nu-onderste sectie leesbaar blijft boven de systeem-navigatiebalk.

### Gemaakte keuzes
- **"Helemaal naar beneden"** is geïnterpreteerd als: Weergave wordt echt de laatste sectie, ook ná de admin-only Beheer-sectie. Voor niet-admins staat Weergave dus onder Debug.
- Er zijn bewust **geen nieuwe weergave-opties** toegevoegd; puur verplaatsen.

### Wat is getest
- **Widgettests** toegevoegd in `frontend/test/settings_screen_test.dart` (3 stuks): niet-admin (Weergave onder Debug/Categorieën), admin (Weergave ná Beheer), en de switch die `setLarge(true)` aanroept via een fake notifier.
- **Browser-preview** (`https://pnf-pr-170.vdzonsoftware.nl`, HTTP 200): alle 5 acceptatiecriteria visueel bevestigd — volgorde onderaan Achtergrond-taken → Opruimen → Debug → **Weergave**, overige secties in oude volgorde bovenin, en de "Grote tekst"-toggle die live het grote-tekst-effect toont. Screenshots gemaakt.
- **Resultaat:** alle acceptatiecriteria voldaan, geen bugs gevonden.

### Bewust niet gedaan / aandachtspunten
- **`flutter analyze` / `flutter test` zijn niet lokaal gedraaid**: op de runners ontbreekt de flutter/dart-binary. Validatie van analyze/test loopt via CI op de PR. De wijziging introduceert geen nieuwe imports of ongebruikte variabelen.
- De tester moest terugvallen op een **wegwerp-testaccount** (`tester_sf-809`) omdat de vaste test-credentials niet toegankelijk waren (service-account `claude-agent` had geen leesrechten op de secret in namespace `pnf-pr-170`); het account is na afloop opgeruimd (`DELETE /api/account/me` → 200). Dit staat los van de story-inhoud maar is een terugkerend infra-aandachtspunt.
- `frontend-reader` heeft geen instellingenscherm en viel buiten scope.

---
