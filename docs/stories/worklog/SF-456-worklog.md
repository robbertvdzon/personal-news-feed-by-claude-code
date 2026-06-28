# SF-456 - Worklog

Story-context bij eerste pickup:
Consistentie-uitlijningen doorvoeren (gedrag-neutraal)

Voer een gedrag-neutrale consistentiepass uit over backend (newsfeedbackend/newsfeedbackend/) en frontends (frontend/, frontend-reader/). Lijn afwijkende patronen uit naar de norm in docs/factory/technical-spec.md, specs/backend-technical-spec.md en de dominante codebase-conventie: naamgeving & lagenstructuur (ModuleService -> api/(+dto/) -> domain/ -> infrastructure/), error-handling (één manier per fout), SLF4J-logniveaus (INFO start/eind, DEBUG externe calls, WARN herstelbaar, ERROR niet-herstelbaar), API-conventies (controllers consistent met handmatige specs/openapi.yaml, uniforme @RequestMapping/@GetMapping + DTO-gebruik), Kotlin-idioom (@param:-use-site-target op @Value-constructorproperties mét genoemde uitzonderingen) en duplicate-oplossingen. Randvoorwaarden: functioneel gedrag blijft exact gelijk; integratietests/feature-bestanden/openapi.yaml NIET wijzigen (zou dat nodig zijn om groen te krijgen -> ga in ERROR met concrete onderbouwing); bij twijfel of gedrag verandert -> wijziging niet doen (overslaan of error). Structurele/architecturale afwijkingen met onzeker gedragseffect (cross-module imports domain/infrastructure, domeinmodellen direct als HTTP-response) melden in het worklog, niet stilzwijgend aanpassen. Houd docs/stories/worklog/SF-456-worklog.md bij met per uitlijning wat+waarom (incl. norm-bron) en bewust níet-aangepakte afwijkingen. Houd mvn test (backend) en bestaande Flutter-tests groen. Voer een eigen reviewstap uit. Een kleine of lege diff is een geldige uitkomst als de codebase al consistent is.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-457 (developer) — consistentie-pass

### Uitkomst
Bewust **lege code-diff** (alleen story-log + dit worklog). De codebase is — mede door eerdere
nightly-passes (SF-392, SF-437) — al consistent op alle in-scope categorieën; er resteert geen
aantoonbaar gedrag-neutrale uitlijning die nog veilig door te voeren is. Conform AC #7 (lege diff
geldig als al consistent), AC #1 (geen gedragswijziging) en AC #4 (bij twijfel niet doen).

### Doorgevoerde uitlijningen
- Geen. Er zijn geen veilige, gedrag-neutrale afwijkingen t.o.v. de dominante norm gevonden.

### Geverifieerd al-conform (per categorie, met norm-bron)
- **@Value use-site-target** — alle constructor-property `@Value` zijn `@param:Value`; 2 resterende
  zonder target zijn de gedocumenteerde uitzonderingen (`@Bean`-methodeparam `PodcastAsyncConfig`,
  plain param zonder val/var `PodcastTranscriptWorker`). Norm: technical-spec.md §Codeconventies.
- **Lagenstructuur modulith** — Controller→api/, ServiceImpl/model→domain/, Repository→
  infrastructure/, request-DTO's→api/dto/. Norm: technical-spec.md §Lagenstructuur.
- **Logger-declaratie** — 39/39 `private val log = LoggerFactory.getLogger(javaClass)`. Norm: dominant patroon.
- **KDoc-positie** — overal vóór de Spring-stereotype-annotatie. Norm: dominant patroon.
- **Controller-mappingstijl** — overal @GetMapping/@PostMapping/… (nooit @RequestMapping(method=)).
  Norm: technical-spec.md §API-contract.
- **Error-handling external_call-fallback** — alle 10 clients loggen `could not log external_call: {}`
  (WARN). Norm: dominant patroon (genormeerd in SF-392).
- **Log-niveaus / Jackson groupId** — geen blatante mismatch; com.fasterxml.jackson overal.

### Bewust níet aangepakt (gemeld, niet stilzwijgend gewijzigd)
1. **SettingsController mist klasse-niveau @RequestMapping** (12 andere controllers hebben dat wél).
   Niet gefixt: deze controller bedient méérdere prefixes (/api/settings, /api/rss-feeds,
   /api/podcast-feeds, /api/settings/event-*). Eén base-path toevoegen zou endpoint-URLs wijzigen →
   gedragswijziging. Structurele afwijking, geen mechanische fix.
2. **frontend-reader/lib/main.dart `_Tab`-class** (positionele ctor / generieke naam) wijkt af van
   `_FeedTab`/`_RssTab` in frontend/. Niet gefixt: frontend-reader is een aparte app, geen dominante
   norm bínnen die app; cross-app hernoemen van een privé-class = stijlvoorkeur, geen consistentie-eis.
3. **Bestaande modulith-violations** (cross-module imports van interne domain/infrastructure-klassen;
   domeinmodellen direct als HTTP-response) — buiten scope, gedragsrisico; ongewijzigd.

### Tests
- `mvn test` (newsfeedbackend/newsfeedbackend/): BUILD SUCCESS — 25 tests, 0 failures/errors.
- Geen compiler-warnings (incl. @Value-targets).
- Geen integratietests of openapi.yaml aangeraakt. Geen eigen unit-tests nodig (geen codewijziging).
- Flutter: geen wijziging; lokaal geen flutter-binary, CI valideert — n.v.t.
