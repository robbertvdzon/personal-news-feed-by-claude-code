# SF-1690 - Worklog

Story-context bij eerste pickup:
Deploy-documentatie in lijn brengen met deploy/ (5 punten)

Documentatie-only wijziging in docs/factory/deployment.md, deploy/README.md en twee YAML-commentaarregels. Bron van waarheid: runbook.md §2 (33-47) en §8 (~284); runbook.md zelf niet wijzigen.

1) Architectuurdiagram compleet maken in docs/factory/deployment.md (blok ~26-36) en deploy/README.md (blok ~9-27): voeg reader (Pod+Service+Route, reader.vdzonsoftware.nl), cloudflared (tunnel *.vdzonsoftware.nl -> in-cluster services) en preview-router (nginx, host-based routing voor PR-previews) toe, in de stijl van het bestaande diagram per bestand en inhoudelijk consistent met runbook.md:38-42.

2) Drie images i.p.v. twee: deploy-flow-tekst in docs/factory/deployment.md (~119) bijwerken naar backend/frontend/reader (ghcr.io/robbertvdzon/personal-news-feed-{backend,frontend,reader}), conform de build-reader-job in .github/workflows/build-images.yml en het images-blok in deploy/base/kustomization.yaml:37-46. Werk ook het stale header-comment build-images.yml:3 bij (alleen het comment; triggers/jobs ongemoeid laten).

3) PVC/audio corrigeren: overal vervangen door 'audio-bytes staan sinds migratie V5__podcast_audio_bytes.sql als BYTEA in Postgres; het PVC houdt alleen runtime-state / admin-cleanup paden' (zoals runbook.md:45-46). Raakt docs/factory/deployment.md:34 (spreekt :38 tegen), deploy/README.md:23 en :27-28, het header-comment van deploy/base/backend-pvc.yaml (regels 1-3, alleen comment - spec ongewijzigd) en de annotatie 'backend-pvc.yaml <- audio storage' in de bestandenlijst.

4) Bestandenlijst deploy/README.md (~226-253) één op één gelijk maken aan `find deploy -type f`: verwijder bootstrap.sh en base/namespace.yaml; voeg onder base/ toe: reader-deployment.yaml, reader-service.yaml, reader-route.yaml, cloudflared-deployment.yaml, preview-router-deployment.yaml, preview-router-config.yaml; voeg onder overlays/ toe: preview/kustomization.yaml met korte annotatie (sinds SF-1542: ephemeral JWT-secret + emptyDir). secrets-cluster.env (gitignored) en preview-ns-labeller/rbac.yaml (pointer-file, VERHUISD) blijven staan. Laat ook de zin op deploy/README.md:45 over het verouderde deploy/bootstrap.sh vervallen (de GitOps-uitleg eromheen behouden, maar bootstrap.sh niet meer als bestaand bestand presenteren). Het historische comment in deploy/base/kustomization.yaml:13-16 blijft ongewijzigd.

5) Onjuiste 'beperking' verwijderen: de eerste bullet onder Beperkingen in deploy/README.md (~196-202, 'Alleen code-changes triggeren een preview' + paths-filter + trivial-commit-workaround) is feitelijk onjuist (het pull_request-trigger-blok in build-images.yml:26-28 heeft géén paths:-filter; :13-16 legt uit dat dat bewust is). Vervang hem door de werkelijke oorzaak van een trage preview: ArgoCD ApplicationSet pollt elke ~3 min, geformuleerd consistent met runbook.md §8. Andere bullets ongewijzigd laten.

Buiten scope: Kotlin/Dart-broncode (incl. het historische KDoc-comment in PodcastRepository.kt:16), tests, manifest-/workflow-inhoud (alleen commentaarregels), runbook.md.

Sluit af met een zelf-review: diff de bestandenlijst opnieuw tegen `find deploy -type f`, en grep-check dat in docs/factory/deployment.md, deploy/README.md en deploy/base/backend-pvc.yaml geen bewering meer staat dat podcast-audio op het PVC/filesystem leeft, en dat deploy/README.md geen paths-filter-bewering over de pull_request-trigger meer bevat.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-1691 (developer) — uitgevoerd

Documentatie-only; geen enkele gedragsverandering. Bron van waarheid: `runbook.md` §2 en §8.

1. **Architectuurdiagrammen compleet** — `docs/factory/deployment.md` (blok §Architectuur)
   en `deploy/README.md` (§Architectuur) noemen nu ook reader (Pod+Service+Route,
   `reader.vdzonsoftware.nl`), cloudflared (tunnel `*.vdzonsoftware.nl` → in-cluster
   services) en preview-router (nginx, host-based routing voor PR-previews), in de stijl
   van het bestaande diagram per bestand en consistent met `runbook.md:38-42`. Het
   backend-PVC is bij de backend-regel gezet en beschreven als runtime-state.
2. **Drie images** — `docs/factory/deployment.md` §Deploy-flow noemt nu backend/frontend/
   reader (`…-{backend,frontend,reader}:sha-…`), conform de `build-reader`-job en het
   `images:`-blok in `deploy/base/kustomization.yaml`. Ook het stale header-comment
   `.github/workflows/build-images.yml:3` bijgewerkt (alleen het comment; triggers/jobs
   ongemoeid). Extra, buiten de letterlijke opsomming maar dezelfde drift in hetzelfde
   documentatie-bestand: `deploy/README.md` §Code-wijziging noemde óók nog maar twee
   images en is meegenomen — anders zou de README zichzelf tegenspreken.
3. **PVC/audio gecorrigeerd** — overal beschreven als "podcast-audio staat sinds migratie
   `V5__podcast_audio_bytes.sql` als BYTEA in Postgres; het PVC houdt alleen
   runtime-state / admin-cleanup paden": `docs/factory/deployment.md` (diagram + tekst),
   `deploy/README.md` (diagram, tekst eronder, annotatie in de bestandenlijst) en het
   header-comment van `deploy/base/backend-pvc.yaml` (alleen comment; de spec is byte-voor-
   byte ongewijzigd).
4. **Bestandenlijst kloppend** — `deploy/README.md` §Bestanden in deze map is 1-op-1
   gelijkgetrokken met `find deploy -type f`: `bootstrap.sh` en `base/namespace.yaml`
   verwijderd; `reader-{deployment,service,route}.yaml`, `cloudflared-deployment.yaml`,
   `preview-router-{deployment,config}.yaml` toegevoegd onder `base/`; `overlays/preview/
   kustomization.yaml` toegevoegd met korte annotatie (geen Routes/PVC/cloudflared,
   emptyDir, ephemeral JWT-sleutel — SF-1542). `secrets-cluster.env` (gitignored) en
   `preview-ns-labeller/rbac.yaml` (pointer-file, VERHUISD) blijven staan. De losse zin
   over het verouderde `deploy/bootstrap.sh` is vervangen door een formulering die de
   GitOps-uitleg behoudt zonder het bestand als bestaand te presenteren. Het historische
   comment in `deploy/base/kustomization.yaml:13-16` is ongewijzigd gelaten.
5. **Onjuiste "beperking" vervangen** — de eerste bullet onder **Beperkingen** in
   `deploy/README.md` beweerde een paths-filter op de `pull_request`-trigger; die bestaat
   niet. Vervangen door de werkelijke oorzaak van een trage preview (ArgoCD ApplicationSet
   pollt ~3 min, consistent met `runbook.md` §8) met expliciete vermelding dat het
   trigger-blok bewust géén `paths:`-filter heeft. Overige bullets ongewijzigd.

### Zelf-review

- Bestandenlijst gediffed tegen `find deploy -type f`: alle 23 bestanden gedekt, geen
  niet-bestaand bestand meer in de lijst (enige extra is het gitignorede
  `secrets-cluster.env`, conform de aanname in de story).
- Grep op `docs/factory/deployment.md`, `deploy/README.md`, `deploy/base/backend-pvc.yaml`:
  geen bewering meer dat podcast-audio op het PVC/filesystem leeft; de enige
  `paths:`-vermelding in `deploy/README.md` stelt nu juist dat die filter er níet is.
- `kubectl kustomize deploy/overlays/openshift` rendert ongewijzigd (exit 0, 18 resources);
  alleen de bekende pre-existing `commonLabels is deprecated`-warning.

### Vangnet

- `mvn -B clean verify` (newsfeedbackend/newsfeedbackend): **BUILD SUCCESS** — 80 unit +
  65 e2e tests, 0 failures, 0 errors.
- `flutter test` (frontend): 19 tests groen. `flutter test` (frontend-reader): 2 tests groen.
- Geen nieuwe tests toegevoegd: de wijziging raakt uitsluitend Markdown en
  YAML-commentaarregels, er is geen runtime-gedrag om te dekken.
- `frontend-reader/pubspec.lock`-drift van `flutter test` teruggezet (`git checkout --`),
  conform de lockfile-discipline.
