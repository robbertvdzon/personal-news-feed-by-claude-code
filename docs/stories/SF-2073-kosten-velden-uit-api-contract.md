# SF-2073 — Audit: haal de niet-bestaande kosten-velden uit het API-contract

Subtaak SF-2074 (development). Documentatie-only: geen Kotlin/Dart/SQL/config,
geen tests toegevoegd of gewijzigd.

## Stappenplan

- [x] `.task.md`, `docs/factory/development.md` en de betrokken specs gelezen
- [x] `costUsd` verwijderd uit `NewsRequest`, `CategoryResult` en `Podcast` in `specs/openapi.yaml`
- [x] Schema-description bij `NewsRequest` en `Podcast` over waar de kosten wél leven
- [x] `isTranslation` toegevoegd aan het `Podcast`-schema
- [x] `TranslationStart.status` van `$ref PodcastStatus` naar `type: string`
- [x] Kosten-claims weg uit `specs/frontend-spec.md` (PodcastCard, PodcastDetailScreen)
- [x] Kosten-per-verzoek weg uit `specs/backend-functional-spec.md` (2× JSON-voorbeeld + daily-summary-stap)
- [x] `costUsd`-clausule weg uit `e2e/scenarios/samenvatting-scenario.md`
- [x] Geverifieerd: greps, YAML-parse, schema-velden 1-op-1 met de data classes
- [x] Vangnet `mvn -B clean verify` gedraaid

## Gedaan / waarom

- **`specs/openapi.yaml`** — de drie `costUsd`-velden beloofden data die de backend
  nooit serialiseert (`data class NewsRequest`/`CategoryResult` in
  `request/RequestService.kt`, `data class Podcast` in `podcast/PodcastService.kt`
  kennen het veld niet). Bij `NewsRequest` en `Podcast` staat nu een schema-description
  die verwijst naar de tabel `external_calls` en `/api/admin/costs/**`, geformuleerd
  conform `specs/backend-technical-spec.md:251-252`, zodat lezers niet denken dat de
  kosteninformatie verdwenen is.
- **`isTranslation`** staat direct achter de `translatedFrom*`-velden en is als
  description gedocumenteerd (berekende, alleen-in-responses property), niet met
  `readOnly: true` — dat mechanisme komt nergens in het bestand voor.
- **`TranslationStart.status`** is `type: string` met een description dat de waarde een
  `PodcastStatus`-naam is; de impl vult `status.name`
  (`PodcastTranslationServiceImpl.kt:76` en `:105`), dus het contract beloofde ten
  onrechte een enum-gevalideerde waarde. Het `PodcastStatus`-schema zelf is ongewijzigd
  en houdt zijn overige `$ref`-gebruikers.
- **Markdown-specs** — alleen de kosten-claims zijn geschrapt; de omliggende opsommingen
  en de (wél bestaande) client-side kostenschatting vóór een podcast-vertaling blijven staan.

## Verificatie

- `grep -rn costUsd specs/ e2e/` → alleen nog `specs/openapi.yaml` (admin-costs-schema)
  en `e2e/runner.js` (code, expliciet buiten scope).
- YAML-parse via SnakeYAML: geldig, **0 dangling `$ref`s**, top-level keys ongewijzigd.
  Veldtelling: `NewsRequest` 17, `CategoryResult` 5, `Podcast` 21 — exact 1-op-1 met de
  data classes (20 constructor-velden + de berekende `isTranslation`). De story noemt
  hier 20; dat is een telfout in het acceptatiecriterium (het telt 19 constructor-velden),
  de 1-op-1-correspondentie met de code klopt.
- `git diff --name-only` toont exact de vier genoemde documenten; geen `.kt`, `.dart`,
  `.sql`, `.yml` of `pom.xml`.
- Vangnet `mvn -B --no-transfer-progress clean verify` (`.factory/verification.yaml`,
  `backend-maven-verify`): **BUILD SUCCESS, exit 0** — 116 unit-tests + 66 e2e-tests,
  0 failures, 0 errors, 0 `[WARNING]`-regels.
