# SF-1077 - rss feed

## Story

rss feed

<!-- refined-by-factory -->

## Scope
In `frontend/lib/screens/settings_screen.dart` staat de term "RSS-feeds" (met koppelteken) op vier plekken binnen het Instellingen-scherm zelf:
- regel 72: sectiekop `Text('RSS-feeds', ...)`
- regel 75: ListTile-titel `Text('RSS-feeds')`
- regel 76: ListTile-subtitel `Text('RSS-feeds en podcast-bronnen beheren')`
- regel 369: knoplabel `title: 'RSS-feeds nu vernieuwen'` in de sectie Achtergrond-taken

Al deze vier teksten moeten van "RSS-feeds" naar "RSS feeds" (spatie i.p.v. koppelteken).

Buiten scope: de losse subpagina `frontend/lib/screens/rss_feeds_screen.dart` (AppBar-titel en sectiekop, geopend via de ListTile hierboven) en de bijbehorende test `frontend/test/rss_feeds_screen_test.dart:64`. Die subpagina is een eigen scherm, niet "de settings" zelf, en de story-tekst noemt expliciet alleen "in de settings". Overige RSS-gerelateerde teksten met een andere formulering (bijv. "RSS-items", backend-logging `[RSS]`, specs/documentatie) vallen niet onder deze wijziging.

## Acceptance criteria
- In `frontend/lib/screens/settings_screen.dart` is elke letterlijke tekst "RSS-feeds" vervangen door "RSS feeds", op de vier genoemde plekken (regels 72, 75, 76, 369).
- De subpagina `rss_feeds_screen.dart` en test `rss_feeds_screen_test.dart` blijven ongewijzigd (buiten scope).
- Er is geen bestaande test die op de oude tekst "RSS-feeds" binnen `settings_screen_test.dart` matcht (geverifieerd: geen match), dus geen testaanpassing nodig als gevolg van deze wijziging.
- `flutter analyze`/bestaande widget-tests voor het Instellingen-scherm blijven slagen.
- Geen backend-, API- of database-wijzigingen.

## Aannames
- "in de settings" verwijst naar het Instellingen-hoofdscherm (`settings_screen.dart`), niet naar de RSS-feeds-subpagina die daarvandaan geopend wordt.
- Het knoplabel "RSS-feeds nu vernieuwen" (regel 369) valt onder dezelfde term-correctie, omdat het dezelfde term "RSS-feeds" bevat en zichtbaar is op het Instellingen-scherm.
- Overige teksten die "RSS" bevatten maar niet letterlijk "RSS-feeds" zijn (zoals "RSS-items", logregels `[RSS]`, of documentatie in `specs/`) blijven ongewijzigd; de story betreft uitsluitend de exacte term "RSS-feeds" → "RSS feeds".

## Eindsamenvatting

De eindsamenvatting voor SF-1077 is geschreven naar `docs/stories/SF-1077-rss-feeds-terminologie-settings-scherm.md`: de terminologiecorrectie "RSS-feeds" → "RSS feeds" is doorgevoerd op de vier genoemde plekken in `settings_screen.dart`, de RSS-subpagina bleef bewust buiten scope, en alle relevante tests (flutter analyze, flutter test, mvn test) zijn groen.
