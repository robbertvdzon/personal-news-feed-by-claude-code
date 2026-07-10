# SF-910 - Worklog

Story-context bij eerste pickup:
Web-favicon en PWA-iconen vervangen door app_icon.png

Vervang de default blauwe Flutter-template-iconen in `frontend/web/` door varianten van `frontend/assets/app_icon.png`. Voorkeursroute: `flutter_launcher_icons`-config in `frontend/pubspec.yaml` uitbreiden met een `web:`-sectie (image_path `assets/app_icon.png`, achtergrondkleur afgestemd op `#3F51B5`) en `dart run flutter_launcher_icons` draaien om `frontend/web/favicon.png`, `Icon-192.png`, `Icon-512.png`, `Icon-maskable-192.png` en `Icon-maskable-512.png` te genereren op de bestaande afmetingen met correcte maskable-veilige-zone. Terugvaloptie: handmatig exporteren/resizen naar dezelfde bestandsnamen/afmetingen als nu. Optioneel `frontend/web/manifest.json` (`theme_color`/`background_color`) bijwerken van `#0175C2` naar een kleur passend bij het nieuwe icoon, alleen als dit geen visuele regressie geeft. Geen wijzigingen aan Android/iOS-iconen, `frontend-reader/` of backend. Visueel verifiëren dat de gegenereerde iconen geen blauw Flutter-logo meer tonen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Runner heeft geen flutter/dart-SDK (bevestigd, zie agent-tips `environment/flutter-sdk-unavailable`), dus `dart run flutter_launcher_icons` kon niet lokaal draaien. Terugvaloptie toegepast: handmatig regenereren met een klein Python-scriptje (stdlib-only, geen PIL/ImageMagick beschikbaar) dat `frontend/assets/app_icon.png` (1024x1024 RGBA) decodeert en met box-filter-resampling naar de bestaande afmetingen/bestandsnamen herschaalt.
- `frontend/web/favicon.png` (16x16) en `frontend/web/icons/Icon-192.png` / `Icon-512.png` (full-bleed, zelfde crop als het bronicoon) vervangen.
- `frontend/web/icons/Icon-maskable-192.png` / `Icon-maskable-512.png` vervangen: het bronicoon geschaald naar 66% en gecentreerd op een vlak vlak van `#3F51B5` (analoog aan `adaptive_icon_background`/`adaptive_icon_foreground` in `pubspec.yaml`, Android-safe-zone-conventie ~66% diameter), zodat maskers (cirkel/squircle/etc.) geen contentverlies geven.
- `frontend/pubspec.yaml`: `flutter_launcher_icons`-config uitgebreid met een `web:`-sectie (`generate: true`, `image_path: assets/app_icon.png`, `background_color`/`theme_color: #3F51B5`) zodat een toekomstige `dart run flutter_launcher_icons` (met SDK) dezelfde output reproduceert/valideert.
- `frontend/web/manifest.json`: `background_color`/`theme_color` van `#0175C2` (Flutter-default-blauw) naar `#3F51B5` (adaptive-icon-achtergrond) — consistent met het nieuwe icoon, geen andere manifest-velden gewijzigd.
- `frontend/web/index.html` gecontroleerd: verwijst al correct naar `favicon.png`/`manifest.json`, geen losse `theme-color`-meta of andere hardcoded kleurverwijzingen (`grep 0175C2` leverde na de wijziging geen treffers meer op) — geen wijziging nodig.
- Scope-check: `git diff --stat` toont alleen `frontend/pubspec.yaml`, `frontend/web/favicon.png`, `frontend/web/icons/Icon-*.png` en `frontend/web/manifest.json`. Geen wijzigingen aan Android/iOS-iconen, `frontend-reader/` of backend.
- Tests: raakt alleen statische assets + config, geen testcode. `mvn`/backend-tests niet gedraaid (niet relevant, geen backend-wijziging). Flutter-tests konden sowieso niet lokaal draaien (geen SDK in de runner-sandbox, bekende beperking).
- Verificatie: gegenereerde PNG's decoded en visueel geïnspecteerd (via de Read-tool) i.p.v. `flutter test`/browser-reload — bevestigd dat `Icon-192.png`/`Icon-512.png`/`favicon.png` het app-icoon tonen (geen blauw Flutter-logo meer) en dat de maskable-varianten het icoon binnen de veilige zone op de `#3F51B5`-achtergrond centreren.

## SF-912 — Testresultaten (tester)

- Scope-check: `git diff main...HEAD --name-only` toont uitsluitend `frontend/pubspec.yaml`, `frontend/web/favicon.png`, `frontend/web/icons/Icon-{192,512,maskable-192,maskable-512}.png`, `frontend/web/manifest.json` en dit worklog-bestand. Geen wijzigingen aan Android/iOS-iconen, `frontend-reader/` of backend — conform de story-restrictie.
- PNG-inspectie (zelfgeschreven Python-script, stdlib zlib/struct, geen PIL nodig): alle bestanden hebben de exacte bestaande afmetingen (favicon 16x16, Icon-192 192x192, Icon-512 512x512, maskable-varianten idem). Pixelsampling bevestigt dat alle vijf bestanden het `app_icon.png`-kleurenpalet bevatten (`#3F51B5`-blauw, wit glyph, oranje accent-stip) — geen spoor meer van het Flutter-template-blauw.
- Maskable-varianten: alpha is overal 255 (volledig opaak, correct voor PWA-maskable-icons — geen doorzichtige hoeken). Content-bounding-box t.o.v. canvas is ~65.6-65.8% breed/hoog voor beide formaten → binnen de gedocumenteerde ~66%-veilige-zone-conventie, consistent met `adaptive_icon_background`/`adaptive_icon_foreground` in `pubspec.yaml`.
- Preview-omgeving (`SF_PREVIEW_URL=https://pnf-pr-175.vdzonsoftware.nl`, `SF_PREVIEW_NAMESPACE=pnf-pr-175`) is live (HTTP 200). Live-gehaalde `favicon.png`, `icons/Icon-192.png` en `icons/Icon-maskable-512.png` zijn md5-identiek aan de branch-bestanden → de wijziging is daadwerkelijk gedeployed op de preview, niet alleen lokaal aanwezig.
- `manifest.json` op preview bevestigd: `background_color`/`theme_color` = `#3F51B5` (was `#0175C2`), geen andere velden gewijzigd — geen sign of PWA-regressie in de manifest-structuur.
- Browser-screenshots (Playwright, viewport 420x900) gemaakt in `/work/screenshots/`: `sf912-app-loaded.png` (login-scherm, wegwerp-/geen-login-flow nodig — alleen statische assets getest, geen authenticated feature), `sf912-favicon-rendered.png` en `sf912-icon192-rendered.png` (direct gerenderde icoonbestanden van de preview-URL) tonen visueel het nieuwe blauw/wit/oranje app-icoon, geen blauw Flutter-logo.
- Geen browser-login nodig: deze story raakt alleen statische web-assets (favicon/PWA-manifest-iconen), geen authenticated in-app-functionaliteit, dus geen test-user-flow vereist volgens de scope van de AC's.
- `frontend/pubspec.yaml` `web:`-sectie (flutter_launcher_icons-config) gecontroleerd: correct toegevoegd met `image_path: assets/app_icon.png`, `background_color`/`theme_color: #3F51B5`, consistent met de bestaande adaptive-icon-config.
- Tests: geen testcode gewijzigd (alleen assets/config); `mvn`/Flutter-tests dus niet geraakt. Flutter/dart-SDK niet beschikbaar op de tester-runner (bevestigd via `which flutter/dart` → not found), consistent met bekende omgevingsbeperking — geen blocker aangezien er geen testcode is gewijzigd en gedrag via directe preview-vergelijking (md5 + pixelinspectie) is bevestigd i.p.v. `flutter test`.
- Conclusie: alle acceptatiecriteria van SF-910/SF-912 zijn geverifieerd en voldaan. Geen bugs gevonden.
