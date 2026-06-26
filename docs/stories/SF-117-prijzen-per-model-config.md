# SF-117 — Prijzen per model naar config + per-call cost_usd

Haal de OpenAI-tarieven uit `Pricing.kt` naar config (`app.ai.pricing.*`), per
model bij te werken, met ondersteuning voor de drie afrekeneenheden. Per call
wordt `cost_usd` uit deze config berekend en blijft in `external_calls` gelogd.

## Stappenplan

- [x] `AiPricingProperties` (`@ConfigurationProperties("app.ai.pricing")`) met
      per-model `ModelPricing` (tokens in/out per 1M, per-minute, per-million-chars)
      + `source`/`updated` (bron-URL + datum).
- [x] Tarieven naar `application.properties` onder `app.ai.pricing.models[...]`
      (bracket-notatie i.v.m. punten in model-id's). Bron-URL + datum erbij.
- [x] `OpenAiChatHttpClient`: token-cost via `pricing.tokenCost(model, in, out)`
      i.p.v. `Pricing.openaiChatCost` / `openaiGpt4oMiniCost`.
- [x] `WhisperClient`: transcriptie-cost via `pricing.transcriptionCost(model, sec)`.
- [x] `TtsClient`: TTS-cost via `pricing.characterCost(model, chars)`; TTS-model
      ook config-driven (`app.openai.tts-model`, default `tts-1`).
- [x] `Pricing.kt` opgeschoond: OpenAI-functies verwijderd; alleen ElevenLabs +
      Tavily blijven (niet-OpenAI, buiten scope van deze story).
- [x] Unittest `AiPricingPropertiesTest` voor de drie afrekeneenheden +
      onbekend-model-fallback (0.0, geen exception).
- [x] `mvn test` (pricing-test) + `mvn test-compile` slagen.

## Wat & waarom

- **Config-structuur**: per model één `ModelPricing` met alle drie de
  eenheid-velden; de caller kiest welke methode (`tokenCost` /
  `transcriptionCost` / `characterCost`) bij zijn afrekeneenheid past. Zo hoeft
  een tarief bijwerken nooit code te raken — env-override werkt via Spring
  relaxed binding (`PNF_*` of `APP_AI_PRICING_*`).
- **Onbekend model → 0.0 + WARN** i.p.v. een exception, zodat een ontbrekend
  tarief nooit een pipeline-call laat falen; de log maakt het zichtbaar.
- **TTS-model naar config** meegenomen omdat de prijs-key het model-id is; het
  model stond nog hardcoded op `"tts-1"`.
- **`Pricing.kt`** behoudt ElevenLabs/Tavily: die vallen buiten "alleen
  OpenAI-modellen" en blijven flat/hardcoded.

## Niet gedaan / overwegingen

- Bron-URL/`updated`-velden worden nog niet in een UI getoond; ze documenteren
  de config voor de handmatige beheerder (conform aanname in de story).
- `ModuleStructureTest` bestaat niet (meer) in de repo; kon dus niet gedraaid
  worden. Volledige `mvn test-compile` slaagt wel.
