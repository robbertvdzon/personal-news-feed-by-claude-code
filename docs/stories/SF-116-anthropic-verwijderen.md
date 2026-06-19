# SF-116 — Anthropic volledig verwijderen (code, config, secrets, dashboard)

Onderdeel van story SF-113 (Andere AI-modellen gebruiken). Voorwaarde: SF-114
(fundament) en SF-115 (migratie alle acties → OpenAI) zijn al review-approved, dus
geen enkele actie draait nog op Anthropic. Deze subtaak ruimt de dode Anthropic-
code, -config en -secrets op en vereenvoudigt het kostendashboard naar één LLM-
provider.

## Stappenplan

- [x] Bevestigen dat geen enkele caller `AnthropicClient` nog gebruikt (alleen
      KDoc-verwijzingen restten).
- [x] `ai/AnthropicClient.kt` (incl. `AiResponse`) en
      `ai/infrastructure/AnthropicHttpClient.kt` verwijderd.
- [x] `Pricing.anthropicCost(...)` verwijderd.
- [x] `ExternalCall.PROVIDER_ANTHROPIC` verwijderd; KDoc "(anthropic)" → "(openai)".
- [x] `AdminCostsServiceImpl.providerBreakdown` — Anthropic uit de providerlijst.
- [x] `app.anthropic.*`-config uit `application.properties` verwijderd.
- [x] KDoc in `OpenAiChatClient.kt` ontdaan van `[AnthropicClient]`-verwijzingen.
- [x] `RssRefreshPipeline` waarschuwingstekst `PNF_ANTHROPIC_API_KEY` → `PNF_OPENAI_API_KEY`.
- [x] Dashboard (`frontend/lib/screens/admin_costs_screen.dart`): Anthropic-kolommen,
      filteroptie en provider-initiaal verwijderd.
- [x] `PNF_ANTHROPIC_API_KEY` uit `deploy/base/backend-deployment.yaml` (de app
      leest 'm niet meer).
- [x] **Review-fix:** `PNF_ANTHROPIC_API_KEY` teruggezet in
      `deploy/base/sealed-secret-api-keys.yaml` + `deploy/secrets-cluster.env.example`
      (dual-use met de Software Factory claude-runner — zie hieronder).
- [x] `docs/factory/secrets-local.md` bijgewerkt naar één OpenAI-sleutel.
- [x] Build + bestaande tests (`mvn test`) groen.

## Wat & waarom

Na SF-115 was alle Anthropic-code dood; alleen verwijzingen in KDoc/comments en de
secret/config bleven nog. Dit is een schrap-PR: clients, prijslogica, provider-
constante en config-keys verdwijnen, het kostendashboard houdt alleen nog
OpenAI/ElevenLabs/Tavily over.

Bewust **niet** aangepast (geen Anthropic-provider, maar legitieme merknamen):
- `PodcastTranslator.kt` — Anthropic/Claude in een vertaal-prompt als "laat
  bedrijfs-/modelnamen ongewijzigd"-voorbeeld.
- `deploy/claude-runner/*`, `deploy/claude-interactive/*`, `frontend-dashboard/.../claude_tab.dart`
  — dit is de **Software Factory** zelf (Claude Code-runner/agents), niet de
  AI-tekstgeneratie van de news-feed-app. Buiten scope van deze story.

### Sealed secret (review-blocker gefixt)
Een eerdere versie verwijderde `PNF_ANTHROPIC_API_KEY` óók uit de gedeelde
`newsfeed-api-keys` sealed secret. Dat brak de **Software Factory** zelf: de
claude-runner (`deploy/claude-runner/job-template.yaml:53-57`) mapt die key hard
(`secretKeyRef` zonder `optional: true`) naar `ANTHROPIC_API_KEY`. Na ArgoCD-sync
zou de runtime-`Secret` de key verliezen → runner-pods falen met
`CreateContainerConfigError`.

Daarom is de key teruggezet in `sealed-secret-api-keys.yaml` en
`secrets-cluster.env.example` (met comment dat 't om de claude-runner gaat, niet
de app). De **app-side** verwijdering blijft volledig: backend-deployment.yaml,
`app.anthropic.*`, `AnthropicHttpClient`, dashboard etc. zijn weg. Geen reseal
nodig: de key stond al in de sealed secret en is ongewijzigd hersteld.

### Tests
`mvn test` is lokaal gedraaid en slaagt (RC=0). Er zijn geen nieuwe unittests
toegevoegd: deze story verwijdert alleen dode code/config; er is geen nieuw gedrag
om te testen. De bestaande suite + Spring-context-load dekken dat er geen dode
referenties overblijven.
