# Factory Docs — Personal News Feed

Een zelf-gehoste persoonlijke nieuwsfeed met AI-curation, podcastgeneratie en multi-user ondersteuning.

**Stack:** Spring Boot 4 / Kotlin 2 backend (Maven) · Flutter frontend · PostgreSQL (Neon) · Flyway · Spring Modulith · OpenShift + ArgoCD.

## Index

- `development.md`: lokaal bouwen, testen en ontwikkelconventies.
- `functional-spec.md`: functionele afspraken, gebruikersflows en acceptatiecriteria.
- `technical-spec.md`: technische keuzes, frameworks, codeconventies en modulestructuur.
- `deployment.md`: deploy-flow, preview-omgevingen en factory-config.
- `secrets-local.md`: lokale secrets en omgevingsvariabelen.
- `agents/`: rol-specifieke instructies voor factory-agents.

## Storyverwijzingen in levende documentatie

De levende documentatie (`docs/factory/`, `specs/`, `README.md`, `runbook.md`,
`e2e/scenarios/`) tagt een afweging met het storynummer waar die afweging is
gemaakt — "sinds SF-2207", "(SF-2130)". Dat nummer moet het **storynummer** zijn:
het nummer waar een `docs/stories/SF-<nr>-*.md` en een
`docs/stories/worklog/SF-<nr>-worklog.md` bij horen. De subtaaknummers binnen een
story (developer, tester, documenter, …) zijn factory-intern: daar is geen
storybestand, geen worklog en geen commit, dus een lezer die het nummer opzoekt
komt op niets uit. SF-2221 heeft er twee zo gecorrigeerd — SF-2208 → SF-2207
(12 plekken) en SF-2187 → SF-2186 (7 plekken).

Verifieer een nummer vóór je het opschrijft:

```bash
ls docs/stories/SF-<nr>-*.md docs/stories/worklog/SF-<nr>-worklog.md
```

De storyverslagen in `docs/stories/` zelf zijn hierop de uitzondering: die zijn
historisch verslag en verwijzen terecht naar hun eigen subtaaknummers. Corrigeer
zo'n nummer dus per bestand en nooit met een repo-brede `sed`.

## Claims over patronen zijn meetbaar, niet beredeneerd

Schrijft een doc op wát een regex, allowlist of filter uitsluit, dan is dat een
**meetbare claim**: controleer elk item van de opsomming afzonderlijk tegen het
patroon zelf, nooit tegen je intuïtie of tegen een andere doc. SF-2228 vond vijf
passages (twee specs, twee KDoc-blokken en een testnaam) die beweerden dat
`^[A-Za-z0-9._-]{3,64}$` `..` uitsluit — de punt zit gewoon in de tekenset, en
`a..b` wordt geaccepteerd:

```bash
echo 'a..b' | grep -E '^[A-Za-z0-9._-]{3,64}$'   # matcht
```

Twee dingen die dit patroon herkenbaar maken. (1) Zo'n fout **vermenigvuldigt
zich**: de eerste formulering is overgeschreven naar elke volgende doc, dus zoek
bij een correctie op de geciteerde *tekst* door de hele repo en reken erop dat er
meer treffers zijn dan de story noemt. (2) Corrigeer niet door alleen te
schrappen — zet de werkelijke reden ernaast waarom het geen probleem is
(hier: zonder `/` en `\` kan `..` nooit een eigen padsegment worden, met de
containment-check in `AdminServiceImpl.deleteAudioDir` als laag daaronder).
Anders leest de volgende lezer de weglating als een gat en "repareert" hij de
validatie. Leg dat werkelijke gedrag bovendien in een test vast; zie de
karakteriseringstest-regel in `technical-spec.md` § Codeconventies.

Treffers in `docs/stories/` blijven hierbuiten: dat is historisch verslag van wat
er toen gedacht werd, en dat mag niet met terugwerkende kracht kloppen.

## Eerste leeslijst voor agents

1. `docs/factory/technical-spec.md` — Spring Modulith-moduleregels, lagenstructuur, Maven-root
2. `docs/factory/functional-spec.md` — gedrag van de app
3. `docs/factory/development.md` — build- en testcommando's
4. `specs/backend-technical-spec.md` — uitgebreide backend-architectuur (gezaghebbend)
5. `specs/openapi.yaml` — API-contract (source of truth)
