# SF-1408 - [Audit] Documentatie: moduletabel in specs/backend-technical-spec.md §3 aanvullen met ontbrekende modules media, search en shared

## Story

[Audit] Documentatie: moduletabel in specs/backend-technical-spec.md §3 aanvullen met ontbrekende modules media, search en shared

<!-- refined-by-factory -->

## Samenvatting
De moduletabel in `specs/backend-technical-spec.md` §3 mist drie backend-modules die wel al bestaan in de broncode: `media`, `search` en `shared`. Deze taak vult de tabel aan zodat ze weer een volledig en actueel overzicht geeft van alle backend-modules.

## Scope
- Alleen documentatie wijzigen in `specs/backend-technical-spec.md` §3 (moduletabel, regels 52-68 in de huidige versie). Geen broncode wijzigen.
- Voeg drie rijen toe aan de moduletabel, met dezelfde opmaak (`Module | Package | Verantwoordelijkheid`) als de bestaande 15 rijen:
  - `media` | `com.vdzon.newsfeedbackend.media` | Verantwoordelijkheid gebaseerd op `AudioTranscoder.kt`: comprimeert podcast-audio (mono, lage bitrate MP3) zodat bestanden onder Whisper's 25 MB-limiet blijven.
  - `search` | `com.vdzon.newsfeedbackend.search` | Verantwoordelijkheid gebaseerd op `TavilyClient.kt`: Tavily-websearch-integratie voor ad-hoc/events-discovery.
  - `shared` | `com.vdzon.newsfeedbackend.shared` | Verantwoordelijkheid gebaseerd op `SharedFeedController.kt`: publieke, read-only gedeelde-feed-endpoints (`/api/shared/feed`, `/api/shared/categories`) voor de reader-app, zonder authenticatie.
- Volgorde van de nieuwe rijen: consistent met de rest van de tabel is geen strikte alfabetische/logische volgorde afgedwongen; toevoegen aan het einde van de tabel is voldoende.
- Gecontroleerd: `docs/factory/technical-spec.md` §Modulestructuur bevat bewust een verkorte lijst ("Belangrijkste modules") die expliciet verwijst naar `specs/backend-technical-spec.md` §3 voor de volledige lijst — deze hoeft niet aangepast te worden. `specs/README.md` bevat geen moduletabel. Er is dus geen andere afgeleide documentatie die aangepast moet worden.

## Acceptance criteria
- De moduletabel in `specs/backend-technical-spec.md` §3 bevat 18 rijen — één voor elke top-level package onder `newsfeedbackend/newsfeedbackend/src/main/kotlin/com/vdzon/newsfeedbackend/` (de bestaande 15 plus `media`, `search`, `shared`).
- Elke nieuwe rij volgt exact hetzelfde format (`Module | Package | Verantwoordelijkheid`) als de bestaande rijen.
- De verantwoordelijkheid-omschrijving van elke nieuwe rij is gebaseerd op de daadwerkelijke klassen in die package (zie Scope hierboven).
- Geen enkele broncodewijziging.
- `docs/factory/technical-spec.md` en `specs/README.md` blijven ongewijzigd, omdat ze geen volledige moduletabel bevatten die aangepast moet worden.

## Aannames
- De verkorte moduletabel in `docs/factory/technical-spec.md` is bewust onvolledig ("Belangrijkste modules") en verwijst al naar de volledige lijst in `specs/backend-technical-spec.md` §3; dit wordt niet als "afgeleide documentatie die moet worden bijgewerkt" beschouwd.
- Volgorde van de drie nieuwe rijen in de tabel is niet functioneel van belang; toevoegen aan het eind is toegestaan.

## Eindsamenvatting

Alles bevestigd. Ik schrijf de eindsamenvatting.

## Eindsamenvatting SF-1408

**Wat is gebouwd:** De moduletabel in `specs/backend-technical-spec.md` §3 was onvolledig — drie bestaande backend-modules (`media`, `search`, `shared`) ontbraken. Er zijn drie rijen aan het einde van de tabel toegevoegd, in exact hetzelfde formaat (`Module | Package | Verantwoordelijkheid`) als de bestaande 15:
- `media` — comprimeert podcast-audio (mono, lage bitrate MP3) zodat bestanden onder Whisper's 25 MB-limiet blijven, o.b.v. `AudioTranscoder.kt`.
- `search` — Tavily-websearch-integratie voor ad-hoc/events-discovery, o.b.v. `TavilyClient.kt`.
- `shared` — publieke, read-only gedeelde-feed-endpoints (`/api/shared/feed`, `/api/shared/categories`) voor de reader-app zonder authenticatie, o.b.v. `SharedFeedController.kt`.

De tabel bevat nu 18 rijen, overeenkomend met de 18 top-level packages onder `com.vdzon.newsfeedbackend`.

**Keuzes:** De nieuwe rijen zijn aan het eind van de tabel toegevoegd (geen strikte volgorde vereist). `docs/factory/technical-spec.md` en `specs/README.md` zijn bewust ongewijzigd gelaten: eerstgenoemde verwijst al expliciet naar §3 voor de volledige lijst, laatstgenoemde bevat geen moduletabel.

**Getest:**
- `mvn test`: BUILD SUCCESS, 71 tests, 0 failures (ongewijzigd t.o.v. voor de wijziging).
- Elke nieuwe rij geverifieerd tegen de daadwerkelijke broncode-klasse (`AudioTranscoder.kt`, `TavilyClient.kt`, `SharedFeedController.kt`) — omschrijvingen komen overeen.
- Aantal packages (18) geverifieerd tegen aantal tabelrijen (18).
- Bevestigd dat geen broncode is gewijzigd en dat de twee niet-aan-te-passen docs ongewijzigd zijn gebleven.
- Geen browser/preview-test nodig: pure docs-only wijziging zonder gedragsimpact.

**Bewust niet gedaan:** Geen wijzigingen aan `docs/factory/technical-spec.md` en `specs/README.md`, en geen broncodewijzigingen — beide expliciet buiten scope conform de refined story.


Wacht, dit moet zonder vragen zijn. Corrigeren:
