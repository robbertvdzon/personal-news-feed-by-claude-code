# SF-628 - Worklog

Story-context bij eerste pickup:
Documentatie in lijn brengen met de code

Vergelijk de volledige documentatieset (README.md, runbook.md, specs/* incl. openapi.yaml, docs/factory/* incl. agents/*) met de actuele broncode en breng uitsluitend documentatie in lijn. specs/openapi.yaml is gezaghebbend voor het API-contract: vergelijk met daadwerkelijke backend-endpoints en vul/corrigeer. Verifieer frontend-claims via grep in de Flutter-screens (backend-endpoint aanwezig != frontend geimplementeerd). Controleer persistence (PostgreSQL/Neon + Flyway), AI-providers/modeldefaults, configuratie, repostructuur en build/test-commando's. Wijzig GEEN broncode en GEEN docs/stories/. Meld niet-veilig-eenzijdig-te-herstellen drift (bijv. Jackson com.fasterxml vs spec, cross-module imports, domeinmodellen direct als HTTP-response) concreet in docs/stories/worklog/SF-628-worklog.md i.p.v. weg te schrijven. Een kleine/lege docs-diff is een geldige uitkomst. Sluit af met een zelf-review dat de diff alleen documentatie (+ worklog) raakt.

## Stappenplan
- [x] read issue and target docs
- [x] vergelijk docs met code (openapi, persistence, AI-modellen, config, frontend-claims, repostructuur)
- [x] implement requested changes (geen nodig — al in lijn)
- [x] run relevant checks (grep-verificatie; geen code-wijziging dus geen build/test nodig)
- [x] update story-log/worklog met results + gemelde drift

## Done / rationale

### Conclusie: docs en code zijn al volledig in lijn → worklog-only diff (geen doc-wijziging)
Dit is een herhaling van eerdere nightly docs-alignment-passes (SF-319/327/406/477/544/586).
Sinds de vorige convergentie (SF-586) is er **geen broncode gewijzigd**: de enige commit
erna (SF-608 / #157) raakte uitsluitend `docs/stories/` (story-log + worklog). De laatste
backend-src-wijziging blijft SF-579 (VideoAudioDownloader yt-dlp-hardening), die al
gedocumenteerd/conform is. De docs waren bij SF-586 al geconvergeerd; er is dus geen nieuwe
drift ontstaan.

### Geverifieerd in lijn (code is leidend)
- **API-contract (openapi.yaml is gezaghebbend):** alle `@RestController`-paden zijn in
  `specs/openapi.yaml` aanwezig. Vergeleken: settings (`/api/settings`, `/api/rss-feeds`,
  `/api/podcast-feeds`, `/api/settings/event-preferences`(GET/PUT/POST) + `/remove`,
  `/api/settings/event-denylist`(GET) + `/{normalizedId}`(DELETE)), auth (`/api/auth/register`,
  `/login`), account (`/api/account/password`, `/me`), rss (incl. `/{id}/transcript`,
  `/{id}/feedback`, `/markAllRead`, `/cleanup`), feed, shared (`/api/shared/feed`,
  `/categories`), requests (incl. `/rerun`, `/cancel`), podcasts (incl. `/{id}/audio`),
  podcast-source (`/by-rss-item/{rssItemId}`, `/{episodeGuid}/translate`), events
  (incl. `/discover`, `/videos/discover`, `/{id}/videos`, `/{id}/videos/summarize`),
  admin users (+`/password`, `/role`, DELETE), admin costs (`/totals`, `/daily`, `/by-user`,
  `/calls`), version. Geen ontbrekende of overtollige paden.
- **Persistence:** PostgreSQL (Neon) + Flyway; migraties V1..V15 aanwezig
  (gat op V4 is bestaand/historisch, niet nieuw). Geen JSON-op-schijf-restanten
  (`topic_history.json`/`rss_items.json`) meer in de specs.
- **AI-providers/modeldefaults:** `application.properties` gebruikt OpenAI met defaults
  gpt-5.4-mini / gpt-5.4 / gpt-5.4-nano / gpt-4o-mini-transcribe (per `PNF_AI_MODEL_*`
  overschrijfbaar); pricing via `app.ai.pricing` (AiPricingProperties, SF-117). Geen
  Anthropic-referenties meer in app-docs (alleen software-factory `claude-runner/tester`
  e.d. blijven, die zijn correct).
- **Frontend-claims:** `event_preferences`/`event_denylist` hebben **geen** Settings-UI —
  `docs/factory/functional-spec.md` (regel 38) en `specs/backend-functional-spec.md`
  documenteren dit expliciet als backend-only. Grep op `event-preferences|denylist` in
  `frontend/lib` + `frontend-reader/lib` geeft 0 treffers → docs kloppen.
- **Frontend base-URL:** code-default `http://localhost:8080`
  (`String.fromEnvironment('API_BASE_URL', ...)` in `frontend/lib/api/api_client.dart` en
  `frontend-reader/lib/api_client.dart`), prod-builds `https://news.vdzonsoftware.nl`
  (Makefiles `PROD_API`). `specs/frontend-spec.md` en `docs/factory/secrets-local.md`
  beschrijven dit correct.
- **Jackson:** code gebruikt overal `com.fasterxml.jackson`; `specs/backend-technical-spec.md`
  §1 en `docs/factory/technical-spec.md` stellen nu expliciet groupId `com.fasterxml.jackson`.
  Geen drift meer.
- **Spring Modulith verify-test:** `ModuleStructureTest`/`ApplicationModules…verify()` bestaat
  nog steeds **niet** in de repo; docs (`development.md`, `backend-technical-spec.md` §7,
  `agents/developer.md`) beschrijven dit correct als "nog niet aanwezig, toevoegen indien
  modulegrenzen wijzigen". Geen valse claim.

### Bekende, niet-veilig-eenzijdig-te-herstellen drift (gemeld, NIET gewijzigd)
Conform de aannames in `.task.md` — dit zijn architecturale code-eigenschappen, geen
mechanische doc-fixes, en de code is leidend:
1. **Cross-module interne imports** (Spring Modulith-conventie-overtredingen) bestaan nog,
   maar worden niet door een verify-test afgedwongen. Een verify-test toevoegen zou de build
   laten falen → architecturale refactor, buiten scope (code is leidend, mag niet wijzigen).
2. **Domeinmodellen direct als HTTP-response** (RssItem e.d. met `@JsonProperty` rechtstreeks
   geserialiseerd) i.p.v. response-DTO's — bestaande §4-violation, niet mechanisch fixbaar.
3. **`SettingsController` zonder klasse-`@RequestMapping`** omdat het drie prefixes bedient
   (`/api/settings`, `/api/rss-feeds`, `/api/podcast-feeds`); een base-path toevoegen zou
   URLs wijzigen → niet doen.
Geen van deze drift is "weggeschreven" in de docs; de docs beschrijven het werkelijke
codegedrag.

### Tests/build
Geen broncode-wijziging en geen doc-inhoudswijziging → geen mvn/flutter build of tests
nodig. Verificatie is uitgevoerd via grep-vergelijking tussen code en docs (zie hierboven).

## Zelf-review
Werkende diff bevat uitsluitend dit worklog-bestand (`docs/stories/worklog/SF-628-worklog.md`).
Geen broncode-wijzigingen, geen wijzigingen in `docs/stories/` buiten dit worklog.
Acceptance criteria gehaald: docs ↔ code in lijn, openapi consistent, frontend-claims
geverifieerd, `docs/stories/` ongewijzigd, lege docs-diff is geldige uitkomst.

## Review (reviewer)
[info] `git diff main...HEAD --stat` bevestigt: enige wijziging is dit worklog-bestand
(84 regels toegevoegd). Geen broncode-wijziging → AC "geen broncode-wijzigingen" gehaald.
[info] Steekproef-verificatie van de claims uitgevoerd en akkoord:
- 13 `@RestController`-klassen aanwezig; geen openapi-drift gevonden in de steekproef.
- Flyway V1..V15 met historisch gat op V4 (bestaand, niet nieuw) — conform claim.
- AI-defaults in `application.properties` = gpt-5.4-mini/gpt-5.4/gpt-5.4-nano/
  gpt-4o-mini-transcribe, pricing via `app.ai.pricing` — conform claim.
- Modulith verify-test afwezig in `src/test` (0 treffers) — docs beschrijven dit correct.
- Grep `event-preferences|denylist` in `frontend/lib` + `frontend-reader/lib` = 0 treffers —
  backend-only claim klopt.
- Anthropic in backend-src: alleen een comment + een prompt-stringliteral, geen actieve
  provider — consistent met "geen Anthropic in de app".
[info] Gemelde, bewust niet-gewijzigde drift (cross-module imports, domeinmodellen als
HTTP-response, `SettingsController` zonder klasse-`@RequestMapping`) is concreet beschreven
i.p.v. weggeschreven — conform `.task.md`-aannames.
Akkoord: worklog-only uitkomst is een geldige docs-alignment-uitkomst.

## Test (tester, SF-630)
[info] `git diff main...HEAD --name-status` = uitsluitend `docs/stories/worklog/SF-628-worklog.md`
(A). Geen broncode-wijziging, geen `docs/stories/`-wijziging buiten dit worklog → AC "geen
broncode-wijzigingen" en "docs/stories ongewijzigd" gehaald.
[info] Claims onafhankelijk geverifieerd via code-inspectie (geen browser-test nodig: story
raakt geen frontend, docs-diff is leeg):
- 13 `@RestController`-klassen; alle paden aanwezig in `specs/openapi.yaml`. Steekproef op de
  niet-expliciet-opgesomde paden (`/api/rss/reselect` regel 439, `/api/rss/refresh` regel 422,
  `/star`, `/read`, `/unread`) → allemaal gedocumenteerd. Geen ontbrekende/overtollige paden.
- Flyway V1..V15 aanwezig, historisch gat op V4 (bestaand, niet nieuw) — conform claim.
- AI-defaults in `application.properties` = gpt-5.4-mini/gpt-5.4/gpt-5.4-nano/
  gpt-4o-mini-transcribe (per `PNF_AI_MODEL_*`), pricing via `app.ai.pricing` — conform claim.
- `event-preferences|denylist` in `frontend/lib` + `frontend-reader/lib` = 0 treffers →
  backend-only claim klopt.
- Modulith verify-test afwezig in `src/test` (0 treffers) — docs beschrijven dit correct.
- Anthropic in backend-src: alleen een comment + een prompt-stringliteral, geen actieve
  provider — consistent met de docs.
[info] Gemelde, bewust niet-gewijzigde drift (cross-module imports, domeinmodellen als
HTTP-response, `SettingsController` zonder klasse-`@RequestMapping`) is concreet beschreven,
niet weggeschreven — conform `.task.md`.
Resultaat: tested-ok. Lege docs-diff is een geldige docs-alignment-uitkomst.
