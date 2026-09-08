# SF-1690 - [Audit] Documentatie: deploy-documentatie (deploy/README.md + docs/factory/deployment.md) in lijn brengen met deploy/

## Story

[Audit] Documentatie: deploy-documentatie (deploy/README.md + docs/factory/deployment.md) in lijn brengen met deploy/

<!-- refined-by-factory -->

## Samenvatting

De uitleg over hoe deze app wordt uitgerold is op vier punten achtergelopen op de
werkelijkheid. De plaatjes noemen maar twee van de vijf onderdelen die draaien, er
wordt gesproken over twee applicaties terwijl er inmiddels drie gebouwd worden, en er
staat nog dat podcast-audio als losse bestanden op schijf bewaard wordt terwijl dat
allang in de database zit. Ook klopt de bestandenlijst niet meer en staat er een
"beperking" beschreven die niet bestaat.

In deze story wordt alleen documentatie bijgewerkt: geen enkele gedragsverandering
aan de applicatie. Het runbook beschrijft de juiste situatie al en dient als bron.

## Scope

Documentatie-only. Te wijzigen bestanden: `docs/factory/deployment.md`,
`deploy/README.md`, en commentaarregels in `deploy/base/backend-pvc.yaml` en
(optioneel) `.github/workflows/build-images.yml`. Bron van waarheid is
`runbook.md` §2 (regels 33-47) en §8.

1. **Architectuurdiagram compleet maken** in `docs/factory/deployment.md`
   (blok rond regels 26-36) en `deploy/README.md` (blok rond regels 9-27).
   Toevoegen, in dezelfde stijl als `runbook.md:38-42`:
   - reader Pod + Service + Route (extern: `reader.vdzonsoftware.nl`)
   - preview-router (nginx, host-based routing voor PR-previews)
   - cloudflared (tunnel `*.vdzonsoftware.nl` → in-cluster services)

2. **Drie images i.p.v. twee.** De deploy-flow-tekst in
   `docs/factory/deployment.md` (regel 119) noemt alleen backend en frontend;
   `build-images.yml` heeft een `build-reader`-job en
   `deploy/base/kustomization.yaml:44-46` pint `personal-news-feed-reader`.
   Werk de tekst bij naar backend/frontend/reader.

3. **PVC/audio-opslag corrigeren.** Podcast-audio staat sinds migratie
   `V5__podcast_audio_bytes.sql` als BYTEA in Postgres. Beschrijf het PVC zoals
   `runbook.md:45-46` ("houdt alleen runtime-state / admin-cleanup paden") op:
   - `docs/factory/deployment.md:34` ("PVC (audio-MP3, 5 Gi)"), dat regel 38
     tegenspreekt;
   - `deploy/README.md:23` ("PVC (audio files, 5 Gi)") en `:27-28` ("alleen
     audio-MP3's en de runtime-state staan in het cluster");
   - het comment bovenaan `deploy/base/backend-pvc.yaml` (regels 1-3), dat nog
     stelt dat audio op disk blijft omdat MP3's te groot zijn voor DB-blobs;
   - de annotatie `backend-pvc.yaml ← audio storage` in de bestandenlijst.

4. **Bestandenlijst `deploy/README.md:226-253` kloppend maken**, één op één met
   `find deploy -type f`:
   - verwijderen: `bootstrap.sh` en `base/namespace.yaml` (bestaan niet meer);
   - toevoegen onder `base/`: `reader-deployment.yaml`, `reader-service.yaml`,
     `reader-route.yaml`, `cloudflared-deployment.yaml`,
     `preview-router-deployment.yaml`, `preview-router-config.yaml`;
   - toevoegen onder `overlays/`: `preview/kustomization.yaml` (sinds SF-1542
     inhoudelijk relevant: ephemeral JWT-secret + emptyDir);
   - ook de losse zin `deploy/README.md:45` ("`deploy/bootstrap.sh` hier is
     verouderd en doet niets meer …") vervalt, want het bestand bestaat niet meer.

5. **Onjuiste "beperking" verwijderen.** De eerste bullet onder **Beperkingen**
   in `deploy/README.md:196-202` ("Alleen code-changes triggeren een preview",
   paths-filter, workaround met trivial commit) is feitelijk onjuist: het
   `pull_request`-trigger-blok in `.github/workflows/build-images.yml` (regels
   26-28) heeft géén `paths:`-filter, en regels 13-16 leggen uit dat dat bewust
   zo is. Verwijder de bullet, of vervang hem door de actuele oorzaak van een
   trage preview (ArgoCD ApplicationSet-pollinterval ~3 min, zoals `runbook.md`
   §8 regel 284 die beschrijft). Optioneel: het stale header-comment
   `build-images.yml:3` ("Bouwt backend- en frontend-images") bijwerken naar
   backend/frontend/reader.

Buiten scope: Kotlin/Dart-broncode, tests, manifest-inhoud (alleen
commentaarregels), `runbook.md` (is al correct), en het historische comment in
`deploy/base/kustomization.yaml:13-16` dat `bootstrap.sh` als verleden tijd
noemt.

## Acceptance criteria

- Beide architectuurdiagrammen (`docs/factory/deployment.md`, `deploy/README.md`)
  noemen reader, preview-router en cloudflared, en spreken `runbook.md` §2 niet tegen.
- `docs/factory/deployment.md` beschrijft de deploy-flow met drie images
  (backend, frontend, reader).
- Nergens in `docs/factory/deployment.md`, `deploy/README.md` of
  `deploy/base/backend-pvc.yaml` staat nog dat podcast-audio op het
  PVC/filesystem leeft; het PVC wordt beschreven als runtime-state /
  admin-cleanup.
- De bestandenlijst in `deploy/README.md` komt exact overeen met de inhoud van
  `deploy/`: geen niet-bestaande bestanden (`bootstrap.sh`, `base/namespace.yaml`),
  geen ontbrekende bestanden, inclusief `overlays/preview/`.
- `deploy/README.md` bevat geen enkele verwijzing meer naar `bootstrap.sh` als
  bestaand bestand, en geen bewering meer over een paths-filter op de
  `pull_request`-trigger van `build-images.yml`.
- Geen wijziging aan Kotlin/Dart-broncode, tests of manifest-inhoud; alleen
  documentatie en YAML-commentaarregels.

## Aannames

- `runbook.md` §2 en §8 zijn leidend als bron; `runbook.md` zelf wordt niet gewijzigd.
- `secrets-cluster.env` blijft als gitignored bestand in de bestandenlijst staan met
  zijn huidige annotatie — de lijst beschrijft de map inclusief lokaal aangemaakte
  bestanden, niet alleen wat in git zit.
- `preview-ns-labeller/rbac.yaml` bestaat nog als pointer-file en blijft met de
  huidige "VERHUISD"-annotatie in de lijst staan.
- Voor punt 5 heeft vervangen door de ArgoCD-pollinterval-oorzaak de voorkeur boven
  het kaal schrappen van de bullet, zodat de "trage preview"-vraag beantwoord blijft;
  formuleer die consistent met `runbook.md` §8.
- Het optionele header-comment in `build-images.yml:3` wordt meegenomen; dat is de
  enige toegestane wijziging buiten `docs/`, `deploy/README.md` en
  `backend-pvc.yaml`, en raakt geen workflow-gedrag.
- `PodcastRepository.kt:16` noemt `app.data-dir` in een KDoc-comment, maar in
  historische/verklarende vorm; broncode blijft ongewijzigd.

## Eindsamenvatting

## Eindsamenvatting SF-1690 — Deploy-documentatie in lijn brengen met `deploy/`

### Wat is gebouwd
Een documentatie-only correctie van vier stukken drift tussen de deploy-documentatie en de werkelijke inhoud van `deploy/`. Er is **geen enkele gedragsverandering**: alleen Markdown en YAML-commentaarregels zijn aangepast. Bron van waarheid was `runbook.md` §2 en §8 (zelf ongewijzigd gelaten).

Gewijzigd (5 bestanden, +137/-29 excl. worklog):

1. **Architectuurdiagrammen compleet** — zowel `docs/factory/deployment.md` als `deploy/README.md` noemen nu alle vijf de draaiende onderdelen: backend, frontend, **reader** (Pod+Service+Route, `reader.vdzonsoftware.nl`), **cloudflared** (tunnel `*.vdzonsoftware.nl`) en **preview-router** (nginx, host-based routing voor PR-previews).
2. **Drie images i.p.v. twee** — de deploy-flow beschrijft nu `…-{backend,frontend,reader}:sha-…`, conform de `build-reader`-job en `deploy/base/kustomization.yaml`. Ook het stale header-comment in `.github/workflows/build-images.yml:3` is bijgewerkt.
3. **PVC/audio gecorrigeerd** — overal staat nu dat podcast-audio sinds migratie `V5__podcast_audio_bytes.sql` als BYTEA in Postgres leeft en dat het PVC alleen runtime-state / admin-cleanup paden houdt. Raakt beide docs én het header-comment van `deploy/base/backend-pvc.yaml` (de PVC-spec zelf, incl. 5 Gi, is byte-voor-byte ongewijzigd).
4. **Bestandenlijst kloppend** — `deploy/README.md` is 1-op-1 gelijkgetrokken met `find deploy -type f` (23 bestanden): `bootstrap.sh` en `base/namespace.yaml` verwijderd, reader-/cloudflared-/preview-router-manifests en `overlays/preview/kustomization.yaml` toegevoegd. Alle verwijzingen naar `bootstrap.sh` als bestaand bestand zijn weg.
5. **Onjuiste "beperking" vervangen** — de bewering dat een paths-filter previews blokkeert (die filter bestaat niet) is vervangen door de echte oorzaak van een trage preview: de ArgoCD ApplicationSet pollt ~3 min, consistent met `runbook.md` §8.

### Gemaakte keuzes
- Punt 5 is **vervangen** i.p.v. geschrapt, zodat de "waarom is mijn preview traag?"-vraag beantwoord blijft (conform de story-aanname).
- Eén bewuste uitbreiding buiten de letterlijke opsomming: §Code-wijziging in `deploy/README.md` noemde óók nog maar twee images en is meegenomen — anders zou de README zichzelf tegenspreken.
- `secrets-cluster.env` (gitignored) en `preview-ns-labeller/rbac.yaml` (pointer-file, "VERHUISD") blijven in de lijst staan, conform de aannames.

### Wat is getest
- **Gedragsneutraliteit hard aangetoond:** `kubectl kustomize` op *beide* overlays (`openshift`, `preview`) rendert byte-identiek aan `main`; `build-images.yml` zonder commentaarregels is identiek aan `main` (geen trigger-/job-wijziging).
- **Volledig vangnet groen:** `mvn -B clean verify` BUILD SUCCESS (80 unit + 65 e2e), `flutter test` 19 (frontend) + 2 (frontend-reader).
- **Inhoudelijke verificatie per acceptatiecriterium** door reviewer én tester: bestandenlijst gediffed tegen `find deploy -type f`, greps op audio/PVC- en paths-filter-beweringen (0 resterende hits), diagram-claims gecontroleerd tegen de manifests (`reader-route.yaml`, `cloudflared-deployment.yaml`, `preview-router-deployment.yaml`).
- Preview `pnf-pr-198` gezond bevonden (`/` 200, `/actuator/health` 200, `/api/feed` zonder token 403).
- Geen blockers, geen bugs gevonden.

### Bewust niet gedaan
- **Geen nieuwe tests toegevoegd** — de wijziging heeft geen runtime-oppervlak; geen browser-/screenshotbewijs, want 0 regels Dart/Kotlin gewijzigd.
- `runbook.md` niet aangepast (was al correct); Kotlin/Dart-broncode, tests en manifest-*inhoud* ongemoeid, incl. het historische KDoc-comment in `PodcastRepository.kt:16` en het historische `bootstrap.sh`-comment in `deploy/base/kustomization.yaml:13-16`.

### Aandachtspunt voor de PO (kandidaat vervolg-story)
Dezelfde audio-op-PVC-drift staat nog in commentaarregels **buiten** deze story-scope: `deploy/overlays/preview/kustomization.yaml:7` en `:104` ("PVC voor audio", "audio is throwaway") en `deploy/base/backend-deployment.yaml:10` ("Audio-PVC is RWO"). Door zowel reviewer als tester gesignaleerd, bewust laten staan.
