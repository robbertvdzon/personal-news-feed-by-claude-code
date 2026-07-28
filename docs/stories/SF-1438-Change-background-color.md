# SF-1438 - Change background color

## Story

Change background color

<!-- refined-by-factory -->

## Samenvatting
De achtergrondkleur van de hoofd-app (frontend) wordt aangepast, zodat deze dezelfde kleur gebruikt als de reader-app. Het gaat om een kleurwijziging van rood naar een teal/groene tint.

## Scope
- Wijzig in `frontend/lib/main.dart` de `seedColor` van `ColorScheme.fromSeed` (regel 132) van `Colors.red` naar `Color(0xFF00897B)`, analoog aan hoe `frontend-reader/lib/main.dart` dit al doet.
- `frontend-reader` blijft ongewijzigd: die app gebruikt deze kleur (`0xFF00897B`) al als seedColor.
- Geen `scaffoldBackgroundColor`-override toevoegen; de achtergrondkleur blijft impliciet via Material 3's `ColorScheme.fromSeed` bepaald, consistent met de bestaande aanpak in beide apps.
- Incidentele losse `backgroundColor`-overrides in schermen (status-highlights zoals foutmeldingen, geselecteerde items) blijven ongewijzigd — dit zijn geen algemene achtergrondkleuren.

## Acceptance criteria
- In `frontend/lib/main.dart` is de `seedColor` van de `ColorScheme.fromSeed` gewijzigd naar `Color(0xFF00897B)`.
- De app (frontend) compileert en start met een merkbaar ander kleurenschema (teal/groen in plaats van rood) op basis van Material 3's seed-kleur-mechanisme.
- `frontend-reader/lib/main.dart` blijft ongewijzigd.
- Overige losse `backgroundColor`-overrides in schermen (bijv. foutstatussen, geselecteerde rijen) blijven functioneel ongewijzigd.

## Aannames
- "De achtergrondkleur" verwijst naar het algemene Material 3-kleurenschema (via `seedColor`), niet naar een expliciete `scaffoldBackgroundColor`-override, conform het bestaande patroon in beide apps.
- Met "de app" wordt `frontend` (de hoofd-app) bedoeld, omdat de opgegeven hex-kleur al exact overeenkomt met de bestaande kleur van `frontend-reader` — die toepassen zou daar een no-op zijn.

## Eindsamenvatting

Geen bestaand samenvattingsbestand voor SF-1438; de factory zal dit zelf wegschrijven. Hier is de eindsamenvatting.

## Eindsamenvatting SF-1438: Achtergrondkleur hoofd-app aangepast naar teal

**Gebouwd**
De hoofd-app (`frontend`) gebruikt nu hetzelfde Material 3-kleurenschema als de reader-app. In `frontend/lib/main.dart` is de `seedColor` van `ColorScheme.fromSeed` gewijzigd van `Colors.red` naar `const Color(0xFF00897B)` (teal/groen). Dit is de enige functionele wijziging.

**Keuzes**
- Geen expliciete `scaffoldBackgroundColor`-override toegevoegd; de kleur wordt bewust impliciet via Material 3's seed-mechanisme bepaald, consistent met het bestaande patroon in beide apps.
- `frontend-reader/lib/main.dart` is niet aangeraakt — die app gebruikte de teal-kleur al.
- Losse `backgroundColor`-overrides in schermen (bijv. foutstatussen, geselecteerde rijen) zijn bewust ongewijzigd gelaten, omdat dit statusindicatoren zijn en geen algemene achtergrondkleur.

**Getest**
- Developer: `flutter analyze` en volledige `flutter test`-suite (17 tests) groen; nieuwe unit test toegevoegd die verifieert dat `MaterialApp.theme.colorScheme.primary` overeenkomt met de teal seed-kleur.
- Tester: diff-review bevestigde dat alleen `main.dart` en de nieuwe test zijn gewijzigd, geen lockfile-drift. Live browser-verificatie op de preview-omgeving toonde het teal/groene kleurenschema duidelijk op zowel het Feed- als het Instellingen-scherm; build-hash in "Over deze app" kwam overeen met de geteste commit.

**Bewust niet gedaan**
- Geen wijzigingen aan `frontend-reader` (die had de kleur al correct).
- Geen aanpassing van incidentele status-`backgroundColor`-overrides in losse schermen.
