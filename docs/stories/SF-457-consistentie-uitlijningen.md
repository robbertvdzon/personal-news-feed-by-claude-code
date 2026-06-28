# SF-457 — Consistentie-uitlijningen doorvoeren (gedrag-neutraal)

Story: SF-456 — nightly: Consistentie: afwijkende patronen gladstrijken
Subtaak: SF-457 (development)

## Stappenplan

- [x] Issue + factory-docs lezen (`.task.md`, `technical-spec.md`, `development.md`, `developer.md`)
- [x] Norm bepalen per consistentie-categorie (docs + dominant codebase-patroon)
- [x] Backend scannen op gedrag-neutrale afwijkingen (alle modules)
- [x] Frontends (`frontend/`, `frontend-reader/`) scannen
- [x] Veilige uitlijningen doorvoeren (geen gevonden → bewust lege code-diff)
- [x] `mvn test` draaien (groen houden)
- [x] Worklog `SF-456-worklog.md` bijwerken met bevindingen + bewust niet-aangepakte afwijkingen

## Wat is gedaan en waarom

Dit is een gedrag-neutrale consistentiepass. Per categorie is eerst de dominante/gedocumenteerde
norm vastgesteld en daarna gericht naar afwijkingen gezocht. De codebase blijkt door eerdere
nightly-passes (o.a. SF-392, SF-437) al sterk geconsolideerd; er resteert geen enkele
aantoonbaar gedrag-neutrale uitlijning die nog veilig door te voeren is.

Uitkomst: **een bewust lege code-diff** (alleen story-log + worklog). Dit is conform AC #7
("Een kleine of zelfs lege code-diff is een geldige uitkomst wanneer de codebase al consistent
is") en AC #1/#4 (geen gedragswijziging; bij twijfel niet doen).

### Geverifieerd al-conforme categorieën (geen actie nodig)

| Categorie | Bevinding | Norm-bron |
|-----------|-----------|-----------|
| `@Value` use-site-target | Alle constructor-*property* `@Value` zijn al `@param:Value`; de 2 resterende `@Value` zonder target zijn exact de gedocumenteerde uitzonderingen (`@Bean`-methodeparameter in `PodcastAsyncConfig`, plain param zonder `val/var` in `PodcastTranscriptWorker`). | `technical-spec.md` §Codeconventies |
| Lagenstructuur modulith | Controller→`api/`, ServiceImpl/domeinmodel→`domain/`, Repository→`infrastructure/`, request-DTO's→`api/dto/` (verplaatst in SF-437). | `technical-spec.md` §Lagenstructuur |
| Logger-declaratie | 39/39 klassen gebruiken `private val log = LoggerFactory.getLogger(javaClass)`; 0 afwijkingen. | dominant patroon |
| KDoc-positie | KDoc-blok staat overal vóór de Spring-stereotype-annotatie. | dominant patroon |
| Controller-annotatiestijl | Methods gebruiken overal `@GetMapping`/`@PostMapping`/… (nooit `@RequestMapping(method=)`). | `technical-spec.md` §API-contract |
| Error-handling `external_call` | Alle 10 fallback-WARN-logs gebruiken de genormeerde Engelse vorm `could not log external_call: {}` (SF-392). | dominant patroon |
| Jackson groupId | Overal `com.fasterxml.jackson` (pom + imports), conform spec. | `technical-spec.md` §Codeconventies |

### Bewust níet aangepakt (gemeld i.p.v. stilzwijgend gewijzigd)

1. **`settings/api/SettingsController.kt` mist een klasse-niveau `@RequestMapping`** terwijl
   12 andere controllers `@RestController` + `@RequestMapping("/api/<module>")` hebben.
   *Niet gefixt:* deze controller bedient méérdere top-level prefixes
   (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`, `/api/settings/event-*`). Eén
   klasse-niveau `@RequestMapping("/api/settings")` toevoegen zónder de method-paden aan te
   passen zou de URLs van de `rss-feeds`- en `podcast-feeds`-endpoints wijzigen → gedragswijziging
   (AC #1/#4). Er bestaat geen single base-path die de norm volgt én alle endpoints behoudt; dit
   is een structurele afwijking, geen mechanische fix.

2. **`frontend-reader/lib/main.dart` private `_Tab`-class** gebruikt een positionele constructor
   en generieke naam, waar `frontend/` screen-specifieke namen met named constructor gebruikt
   (`_FeedTab`/`_RssTab` met `{required …}`). *Niet gefixt:* `frontend-reader` is een aparte,
   kleinere app; de "norm" leeft enkel in de andere app en de class is privé binnen één bestand.
   Geen aantoonbare dominante norm bínnen `frontend-reader` → cross-app hernoemen is stijlvoorkeur,
   geen consistentie-eis.

3. **Bestaande architecturale modulith-afwijkingen** (cross-module imports van interne
   `domain`/`infrastructure`-klassen; domeinmodellen die direct als HTTP-response worden
   geserialiseerd) blijven gemeld en niet aangeraakt — buiten scope, gedragsrisico.

## Tests

- `mvn test` (backend, root `newsfeedbackend/newsfeedbackend/`): **BUILD SUCCESS — 25 tests, 0 failures/errors**.
- Geen compiler-warnings over `@Value`-targets.
- Geen eigen unit-tests toegevoegd: er is geen code gewijzigd, dus er valt niets te testen.
- Flutter: geen code gewijzigd; `flutter analyze/test` draait niet lokaal (geen flutter-binary in runner) en wordt door CI gevalideerd — niet van toepassing want geen frontend-wijziging.
