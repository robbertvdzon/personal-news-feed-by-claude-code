# SF-712 - nightly: Documentatie in lijn brengen met de code

## Story

nightly: Documentatie in lijn brengen met de code

<!-- refined-by-factory -->

## Scope
Nachtelijke documentatie-alignmentcontrole: zorg dat de gehele documentatie van de repository in lijn is met de huidige broncode. Alle functionaliteit die in de code aanwezig is, moet correct en volledig in de documentatie staan; verouderde, onjuiste of ontbrekende beschrijvingen worden gecorrigeerd.

**Alleen documentatie mag wijzigen — de broncode mag niet worden aangepast.**

In scope (documentatieset die gecontroleerd en zo nodig bijgewerkt wordt):
- `README.md` en `runbook.md`
- `specs/*` — `README.md`, `backend-functional-spec.md`, `backend-technical-spec.md`, `frontend-spec.md`, `e2e.md`, `openapi.yaml`, `branch-commit-convention.md`
- `docs/factory/*` incl. `docs/factory/agents/*`

Read-only input (niet wijzigen, wel gebruiken als context over het waaróm van functionaliteit):
- `docs/stories/*`

Buiten scope:
- Elke wijziging aan broncode (backend Kotlin onder `newsfeedbackend/`, frontend onder `frontend/` en `frontend-reader/`, configuratie, migraties).
- Aanpassen van e2e-scenario's of tests als doel op zich (alleen relevant als documentatie ervan onjuist is).

## Acceptance criteria
- De broncode is aantoonbaar **niet** gewijzigd; de diff bevat uitsluitend bestanden binnen de documentatieset (en eventueel het worklog). Een code-wijziging is reden tot afkeuren.
- Documentatie die de code onjuist beschrijft, is gecorrigeerd zodat ze de feitelijke code weergeeft (code is leidend bij conflicten).
- Functionaliteit die in de code bestaat maar in de documentatie ontbreekt, is toegevoegd aan de juiste documentatieplek.
- `specs/openapi.yaml` blijft het gezaghebbende API-contract en is consistent met de daadwerkelijke endpoints/controllers in de backend.
- Frontend-functionaliteit wordt in de docs alleen als aanwezig beschreven als ze ook echt in de Flutter-screens is geïmplementeerd (backend-endpoint ≠ frontend geïmplementeerd).
- Een lege of kleine documentatie-diff is een geldige uitkomst wanneer documentatie en code al in lijn zijn; in dat geval is dit expliciet vastgelegd in het worklog.

## Aannames
- Dit is een herhaling van de eerdere nightly docs-in-lijn-met-code-passes (SF-319/SF-327/SF-406/SF-477/SF-544/SF-628); dezelfde documentatieset en aanpak gelden.
- `docs/factory/` is volledig ingevuld en git-getrackt (SF-220); er is dus géén extra acceptatiecriterium nodig voor het aanvullen van stub-docs.
- Bekende, bewuste afwijkingen tussen spec en code (bijv. Jackson `com.fasterxml.jackson` vs. spec `tools.jackson`, cross-module domain/infrastructure-imports, domeinmodellen direct als HTTP-response) worden als bevinding/melding in het worklog opgenomen, niet door codewijziging "opgelost".
- Het worklog (`docs/stories/worklog/SF-712-worklog.md`) mag worden aangemaakt/bijgewerkt; dit valt niet onder broncode.
- Bij echte twijfel over een afwijking geldt: code is de gezaghebbende bron en de documentatie wordt daarop afgestemd.

## Eindsamenvatting

## Eindsamenvatting — SF-712: Documentatie in lijn brengen met de code (nightly)

**Wat is gebouwd / gewijzigd**
Dit was een nachtelijke documentatie-alignmentcontrole. De volledige documentatieset (`README.md`, `runbook.md`, `specs/*` incl. `openapi.yaml`, en `docs/factory/*`) is gecontroleerd tegen de huidige broncode. **Uitkomst: geen documentatiewijziging nodig** — documentatie en code waren al volledig in lijn. De enige diff t.o.v. `main` is het worklog-bestand (`docs/stories/worklog/SF-712-worklog.md`, +103 regels). Broncode is aantoonbaar niet aangeraakt.

**Belangrijkste keuzes**
- Vastgesteld dat zowel broncode als documentatie **byte-identiek** zijn aan de SF-698-baseline (`git diff` over backend/frontends en over de docsset is leeg). Omdat de docs bij SF-698 al conform waren en de code sindsdien niet veranderde, blijft de alignment geldig.
- Een lege documentatie-diff is conform de acceptatiecriteria een **geldige, expliciet vastgelegde uitkomst**; dit is in het worklog verantwoord.
- Bekende, bewust geaccepteerde afwijkingen zijn als melding vastgelegd i.p.v. "opgelost" met een codewijziging (code mocht niet wijzigen): Jackson op `com.fasterxml.jackson` (Jackson 2) i.p.v. `tools.jackson`; cross-module Spring Modulith-imports; domeinmodellen direct als HTTP-response; `SettingsController` zonder klasse-`@RequestMapping` (bedient meerdere prefixes).

**Wat is geverifieerd**
- OpenAPI-contract ↔ controllers: alle 13 `@RestController`-klassen en hun paden matchen exact de `paths:` in `specs/openapi.yaml` (auth, account, settings/event-preferences/denylist, rss/podcast-feeds, feed, requests, podcasts, events, shared, admin/users en admin/costs-endpoints). Geen ontbrekende of overtollige paden.
- Geplande taken (`@Scheduled`): RssScheduler, EventScheduler, EventVideoScheduler en PodcastTranscriptWorker komen overeen met de spec (cron-expressies en transcript-worker fixedDelay 120000 bevestigd).
- Frontend-claims in docs komen overeen met de daadwerkelijke Flutter-screens; geen nieuwe screens.
- Tester (SF-714) heeft onafhankelijk bevestigd: diff bevat enkel het worklog, code-/docs-delta sinds SF-698 zijn leeg, claims gereproduceerd.

**Wat bewust niet is gedaan**
- Geen broncodewijzigingen (buiten scope; code is leidend bij conflicten).
- De bekende afwijkingen zijn niet "gefixt" — bewust als melding gerapporteerd.
- Geen browser-/preview-test: docs-only story zonder gedragswijziging, dus geen live preview vereist.

```json
```
