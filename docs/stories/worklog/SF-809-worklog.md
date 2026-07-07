# SF-809 - Worklog

Story-context bij eerste pickup:
Verplaats Weergave-sectie naar onderaan settings-scherm

In frontend/lib/screens/settings_screen.dart: verplaats het Weergave-blok (kop Text('Weergave') + SwitchListTile 'Grote tekst' op appearanceProvider/setLarge, nu regels ~61-66) naar het einde van de ListView-children, zodat het de laatste sectie wordt, ook ná het admin-only Beheer-blok. Verwijder de overtollige divider op de oude plek (tussen Account en Categorieën) en plaats een correcte Divider() vóór de verplaatste Weergave-sectie, consistent voor zowel admin- als niet-admin-weergave. Laat de switch-logica ongewijzigd (value: appearance.largeFont, onChanged -> setLarge). Behoud de bestaande bottom-inset-padding op de ListView. Geen wijziging aan providers, andere secties of backend. Voer zelf-review uit en draai flutter analyze; ruim eventuele ongebruikte variabelen/imports op.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-810: In `frontend/lib/screens/settings_screen.dart` het Weergave-blok
  verplaatst naar het einde van de ListView-children:
  - Het oude Weergave-blok (kop `Text('Weergave')` + `SwitchListTile 'Grote
    tekst'`) én de bijbehorende extra `Divider()` tussen Account en
    Categorieën verwijderd. Tussen Account en Categorieën blijft nu precies
    één `Divider()` over (geen dubbele/overbodige divider meer).
  - Het Weergave-blok opnieuw geplaatst als laatste sectie, ná het admin-only
    `Beheer`-blok, voorafgegaan door één `Divider()`. Deze divider staat
    buiten de `if (auth.isAdmin)`-lijst, dus zowel admins als niet-admins
    krijgen een correcte scheiding vóór Weergave.
  - Switch-logica ongewijzigd: `value: appearance.largeFont`,
    `onChanged -> ref.read(appearanceProvider.notifier).setLarge(v)`.
    `appearanceProvider` wordt nog steeds gewatcht (regel 22), dus geen
    ongebruikte variabele; imports ongewijzigd.
  - Bottom-inset-padding op de ListView (`EdgeInsets.fromLTRB(16, 16, 16, 16 +
    bottomInset)`) behouden; de nu-onderste Weergave-sectie blijft leesbaar
    boven de systeem-navigatiebalk.
- Testen: `frontend/test/settings_screen_test.dart` toegevoegd met 3
  widgettests:
  1. niet-admin — Weergave staat visueel ónder Debug én onder Categorieën
     (positie-vergelijking via `getTopLeft`), 'Grote tekst' aanwezig.
  2. admin — Weergave staat ná de admin-only Beheer-sectie.
  3. de 'Grote tekst'-switch roept `setLarge(true)` aan (geverifieerd via
     fake `AppearanceNotifier`).
  Providers worden zonder netwerk overschreven (fake Auth/Appearance/Request
  notifiers), analoog aan het bestaande `categories_screen_test.dart`-patroon.

Test-uitvoering:
- De developer-runner heeft geen flutter/dart-binary (`which flutter dart`
  faalt), dus `flutter analyze` en `flutter test` kunnen niet lokaal draaien.
  CI valideert analyze/test op de PR. De wijziging is puur een herschikking
  van bestaande widgets zonder nieuwe imports of ongebruikte variabelen.
