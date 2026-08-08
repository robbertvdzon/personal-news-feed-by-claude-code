# SF-2015 - Worklog

Story-context bij eerste pickup:
Deploy-docs en overlay-comments in lijn brengen met de manifests

Zuivere documentatie-/commentwijziging in vier bestanden: docs/factory/deployment.md, deploy/README.md, runbook.md en deploy/overlays/preview/kustomization.yaml. Geen code-, manifest- of gedragswijziging.

1) Verwijder de regel 'preview-router (nginx, host-based routing voor PR-previews)' uit de drie architectuurdiagrammen (docs/factory/deployment.md:37, deploy/README.md:25, runbook.md:42). De diagrammen noemen daarna alleen onderdelen die echt in deploy/base/ staan: backend, frontend, reader, cloudflared en het Secret.

2) Verwijder preview-router-deployment.yaml en preview-router-config.yaml uit de base/-bestandenlijst in deploy/README.md:255-256, zodat die lijst 1-op-1 overeenkomt met `ls deploy/base/` (13 bestanden).

3) Beschrijf in deploy/README.md stap 5 van 'Hoe het werkt' (:181-183) en runbook.md:305 de huidige routering in plaats van de nginx-tussenlaag: Cloudflare stuurt de wildcard *.vdzonsoftware.nl naar de OpenShift-ingressrouter, die op de Host-header de juiste Route kiest. Productiehosts staan declaratief in deploy/base/frontend-route.yaml (news.vdzonsoftware.nl) en deploy/base/reader-route.yaml (reader.vdzonsoftware.nl); voor previews zet de overlay de placeholder-host preview-host-must-be-set.invalid, die de ApplicationSet per PR invult. Vermeld in beide documenten dat insecureEdgeTerminationPolicy op de frontend- en reader-Route van Redirect naar Allow is gezet omdat de Cloudflare-connector de router cluster-intern via HTTP bereikt (zelfde motivering als de comments in de manifests). deploy/base/backend-route.yaml houdt bewust Redirect en blijft ongewijzigd.

4) Corrigeer de beschrijving van overlays/preview/ in deploy/README.md:262-265: die zegt nu 'geen Routes', maar de frontend-Route blijft juist behouden met een per-PR host; alleen backend-debug- en reader-Route, de PVC, cloudflared en de SealedSecret vervallen.

5) Werk de voorwaarde voor de per-PR Neon-branch bij in docs/factory/deployment.md:97-101: GITHUB_TOKEN is een derde vereiste sleutel naast NEON_API_KEY en NEON_PROJECT_ID. Beschrijf het fail-closed gedrag exact: ontbreekt het token, faalt de curl of komt er geen HTTP 200, dan is de PR-status 'onbekend' en voert de labeller voor die preview GEEN ENKELE mutatie uit - geen namespace-label, geen Neon-branch, geen secret-patch en geen cleanup. Dit is bewust scherper dan de oorspronkelijke storytekst ('draait zonder eigen branch-DB'), omdat de github_pr_is_open-gate in deploy/preview-ns-labeller/labeller.sh vóór ensure_ns_with_label staat. Werk de wiring-stappen 1-3 zo bij dat de GitHub-PR-statuscheck vóór elke creatiehandeling staat, en pas de zin over opruimen bij PR-close aan: een branch verdwijnt pas nadat de namespace weg is EN GitHub bevestigt dat de PR gesloten is.

6) Trek dezelfde verouderde voorwaarde gelijk op twee plekken buiten de oorspronkelijke storylijst: deploy/README.md:214-216 en runbook.md:180-182 stellen allebei nog dat alleen een ontbrekende NEON_API_KEY tot labeling-only degradeert.

7) Comment-restanten in deploy/overlays/preview/kustomization.yaml: regel 11 ('Geen preview-router') vervalt; regel 2 moet niet meer naar het niet-bestaande deploy/applicationset.yaml verwijzen maar naar robberts-infrastructure/manifests/root-app/apps/; regel 16 noemt naast namespace en image-tag ook de Route-host als door de ApplicationSet ingevulde waarde. In dit bestand mogen UITSLUITEND commentregels wijzigen.

Buiten scope: docs/stories/** (historische verslagen, bewuste momentopname - die noemen de preview-router nog en blijven ongemoeid), deploy/base/backend-route.yaml, en elke functionele wijziging aan manifests of labeller.sh.

Let op: regelnummers gelden voor main na 954d5f3 en verschuiven zodra de eerste edit landt - werk op tekst, niet op regelnummer.

Sluit af met een eigen review-stap: loop de 11 acceptatiecriteria puntsgewijs na, controleer dat `grep -rn "preview-router" deploy/ docs/factory/ runbook.md` nul treffers geeft, dat `git diff --stat` alleen de vier genoemde bestanden raakt, en dat de gerenderde output van `kubectl kustomize deploy/overlays/preview` byte-identiek is aan die van vóór de wijziging. Is kubectl niet beschikbaar, meld dat dan expliciet en toon in plaats daarvan dat elke gewijzigde regel in dat manifest een commentregel is. Werk docs/stories/worklog/SF-2015-worklog.md bij met wat je hebt gedaan.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-2016 (development) — 2026-08-08

Uitgevoerd, uitsluitend docs + comments; geen code-, manifest- of gedragswijziging.

**docs/factory/deployment.md**
- `preview-router`-regel uit het architectuurdiagram gehaald (§Architectuur).
- Per-PR Neon-branch: `GITHUB_TOKEN` toegevoegd als derde vereiste sleutel naast
  `NEON_API_KEY` en `NEON_PROJECT_ID`, met het fail-closed gedrag expliciet
  beschreven (ontbrekend token / gefaalde curl / geen HTTP 200 ⇒ PR-status
  "onbekend" ⇒ géén namespace-label, Neon-branch, secret-patch of cleanup).
  Dit volgt de `github_pr_is_open`-gate die in `labeller.sh` vóór
  `ensure_ns_with_label` staat — dus scherper dan "draait zonder eigen branch-DB".
- Wiring-stappen: nieuwe stap 1 = de GitHub-PR-statuscheck vóór élke
  creatiehandeling; oude 1-3 doorgenummerd naar 2-4. Opruim-zin bijgesteld:
  een branch verdwijnt pas nadat de namespace weg is én GitHub bevestigt dat de
  PR gesloten is.

**deploy/README.md**
- `preview-router` uit het architectuurdiagram.
- `preview-router-deployment.yaml` + `preview-router-config.yaml` uit de
  `base/`-bestandenlijst; die lijst telt nu 13 entries, gelijk aan `ls deploy/base/`.
- Stap 5 van "Hoe het werkt" herschreven: Cloudflare stuurt de wildcard naar de
  OpenShift-ingressrouter, die op de Host-header de Route kiest; hosts
  declaratief in `frontend-route.yaml`/`reader-route.yaml`, per-PR host via de
  placeholder die de ApplicationSet invult; `insecureEdgeTerminationPolicy: Allow`
  met reden (Cloudflare-connector bereikt de router cluster-intern via HTTP),
  `backend-route.yaml` houdt bewust `Redirect`.
- Beperking (b) uitgebreid: `NEON_PROJECT_ID` erbij en `GITHUB_TOKEN` als
  aparte, fail-closed voorwaarde (c).
- Beschrijving van `overlays/preview/` gecorrigeerd: de frontend-Route blijft
  (met per-PR host); backend-debug- en reader-Route, PVC, cloudflared en
  SealedSecret vervallen.

**runbook.md**
- `preview-router` uit het §2-diagram.
- §6 Database: dezelfde bijgewerkte, fail-closed voorwaarde als in
  `docs/factory/deployment.md`.
- §7 Cloudflare Tunnel: routering via de ingressrouter op Host-header i.p.v. de
  nginx-tussenlaag, incl. de `Allow`-motivering.

**deploy/overlays/preview/kustomization.yaml** (alleen commentregels)
- Verwijzing naar het niet-bestaande `deploy/applicationset.yaml` vervangen door
  `robberts-infrastructure/manifests/root-app/apps/`.
- Bullet "Geen preview-router" vervallen; frontend-Route-bullet noemt nu de
  per-PR host + ingressrouter.
- Slotregel noemt namespace, image-tag én Route-host als door de ApplicationSet
  ingevulde waarden.

**Verificatie**
- `grep -rn "preview-router" deploy/ docs/factory/ runbook.md` → 0 treffers.
- `grep -rn "applicationset.yaml" deploy/` → alleen nog de verwijzing naar
  `robberts-infrastructure/manifests/root-app/apps/personal-news-feed-applicationset.yaml`.
- `ls deploy/base | wc -l` → 13, gelijk aan de lijst in `deploy/README.md`.
- `git diff --stat` raakt exact de vier genoemde bestanden (+ deze worklog).
- `kubectl kustomize deploy/overlays/preview` vóór en ná: **byte-identiek**
  (`diff` leeg). De enige wijzigingen in dat bestand zijn commentregels.
- Vangnet `mvn -B clean verify` in `newsfeedbackend/newsfeedbackend`: BUILD
  SUCCESS, exit 0, 106 unit + 61 e2e groen, 0 failures/errors, 0 `[WARNING]`.
- `flutter test`: `frontend/` 25 groen, `frontend-reader/` 2 groen. Beide
  `pubspec.lock`-bestanden daarna teruggezet met `git checkout --` (kale
  lockfile-drift hoort niet in deze docs-only PR).

Bewust niet gewijzigd: `docs/stories/**` (historische momentopnames noemen de
preview-router nog), `deploy/base/backend-route.yaml` (houdt `Redirect`), en
`labeller.sh`/manifests (geen gedragswijziging in scope).

## Review SF-2016 — 2026-08-08

Volledige story-diff (`git diff main...HEAD`) gereviewd: 4 doc/comment-bestanden
+ deze worklog. Geen bevindingen; alle 11 acceptatiecriteria zelfstandig
geverifieerd.

- `grep -rn "preview-router" deploy/ docs/factory/ runbook.md` → 0 treffers (AC1);
  de drie diagrammen noemen alleen backend/frontend/reader/cloudflared/Secret (AC2).
- `base/`-lijst in `deploy/README.md` = 13 entries, 1-op-1 met `ls deploy/base/` (AC3).
- Routeringstekst (README stap 5, `runbook.md` §7) klopt met de manifests:
  `frontend-route.yaml` host `news.` + `insecureEdgeTerminationPolicy: Allow`,
  `reader-route.yaml` host `reader.` + `Allow`, `backend-route.yaml` `Redirect`
  (AC4, AC5). Enige resterende `nginx`-treffers zijn de expliciete "geen
  nginx-tussenlaag"-zinnen en de frontend-proxy in `backend-route.yaml`.
- Overlay-beschrijving klopt met de patches: frontend-Route `replace` naar
  `preview-host-must-be-set.invalid`; `$patch: delete` op backend-debug-Route,
  reader-Route, PVC, cloudflared en SealedSecret (AC6).
- Fail-closed tekst nagelopen tegen `labeller.sh`: `github_pr_is_open` geeft 2 bij
  ontbrekend token / gefaalde curl / non-200 en staat vóór `ensure_ns_with_label`,
  en de cleanup-lus slaat over bij status ≠ gesloten of bestaande namespace — de
  docs beschrijven dat correct, ook de "namespace weg **én** PR gesloten"-conditie
  (AC7, AC8, AC9). `GITHUB_TOKEN` staat inderdaad in
  `deploy/base/sealed-secret-api-keys.yaml`.
- `grep -rn "applicationset.yaml" deploy/` → alleen het robberts-infrastructure-pad;
  overlay-comment noemt namespace, image-tag én Route-host (AC10).
- AC11 zelf herdraaid via `git worktree add /tmp/base main`:
  `kubectl kustomize` vóór/ná → `diff` leeg (277 regels, byte-identiek). Diff van
  het overlay-bestand bevat na filtering op comment-regels nul non-comment-regels.

## SF-2017 — tester (story-brede test)

Docs-only story: geen code, tests of manifest-gedrag gewijzigd. Onafhankelijk
alle 11 acceptatiecriteria nagelopen op de branch `ai/SF-2015`.

| AC | Check | Resultaat |
|----|-------|-----------|
| 1 | `grep -rn "preview-router" deploy/ docs/factory/ runbook.md` | 0 treffers ✅ |
| 2 | 3 diagrammen noemen alleen backend/frontend/reader/cloudflared/Secret | ✅ |
| 3 | `base/`-lijst README = `ls deploy/base/` (13 bestanden) | 13 = 13, 1-op-1 ✅ |
| 4 | README stap 5 + `runbook.md` §7 beschrijven ingressrouter op Host-header | ✅ |
| 5 | `insecureEdgeTerminationPolicy: Allow` + reden, consistent met manifests | ✅ |
| 6 | Overlay-beschrijving klopt met de patches | ✅ |
| 7 | `GITHUB_TOKEN` als derde sleutel + fail-closed gedrag | ✅ |
| 8 | Wiring begint met PR-statuscheck; opruimen = ns weg **én** PR gesloten | ✅ |
| 9 | README:224-232 en `runbook.md`:180-186 gelijkgetrokken | ✅ |
| 10 | Geen verwijzing meer naar `deploy/applicationset.yaml` | ✅ |
| 11 | Diff-scope + byte-identieke kustomize-render | ✅ (zie hieronder) |

Verificatiedetails:

- **AC11 (render-identiteit).** Zelfstandig herdraaid via een schone worktree op
  `main` (`git worktree add /tmp/mainwt main`): `kubectl kustomize` op beide
  revisies gaf identieke md5 `bb7ae3f2b1fb0afa72d203126662825e` (6701 bytes),
  `cmp` leeg. Er is dus aantoonbaar géén gedragsverandering in de gerenderde
  manifests. Alle gewijzigde regels in `deploy/overlays/preview/kustomization.yaml`
  beginnen met `#`.
- **AC7/AC8 (fail-closed) tegen `labeller.sh` gecontroleerd, niet alleen tegen de
  tekst.** `github_pr_is_open` geeft returncode 2 bij ontbrekend token, gefaalde
  curl of non-200 (regels 72-89), en de aanroep in de main-loop doet `continue`
  vóór `ensure_ns_with_label` — dus inderdaad géén namespace-label, geen
  Neon-branch, geen secret-patch. De cleanup-lus (regels 375-415) verwijdert een
  branch alleen als de namespace weg is (`ns_exists` false) én GitHub returncode 1
  (gesloten) geeft; bij status onbekend blijft de branch staan. De docs beschrijven
  dit correct.
- **AC5** getoetst aan de manifests zelf: `frontend-route.yaml:21` en
  `reader-route.yaml:21` staan op `Allow`, `backend-route.yaml:22` op `Redirect` —
  precies zoals de docs nu stellen.
- **Diff-scope.** `git diff --name-status main...HEAD` raakt `deploy/README.md`,
  `deploy/overlays/preview/kustomization.yaml`, `docs/factory/deployment.md`,
  `runbook.md` en (nieuw) `docs/stories/worklog/SF-2015-worklog.md`. Bestaande
  bestanden onder `docs/stories/**` zijn ongemoeid — alleen het worklog is
  toegevoegd, wat factory-bookkeeping is en geen historisch storyverslag wijzigt.
- **Resterende `preview-router`-treffers** in de repo zitten uitsluitend in
  `docs/stories/**` (SF-1542, SF-1690 en hun worklogs) en in `.task.md` zelf —
  expliciet buiten scope als historische momentopname.
- **Geen preview/E2E-test uitgevoerd.** `gh` heeft in deze container geen auth
  (`gh auth login`/`GH_TOKEN` ontbreekt), dus er was geen PR-nummer en dus geen
  preview-URL op te halen. Dat is hier niet blokkerend: de story wijzigt geen
  code, geen tests en geen gerenderde manifests (byte-identiek bewezen), zodat er
  geen waarneembaar applicatiegedrag kan zijn veranderd.
- **Geen flakes waargenomen.** Het volledige vangnet draait de harness na deze run.
