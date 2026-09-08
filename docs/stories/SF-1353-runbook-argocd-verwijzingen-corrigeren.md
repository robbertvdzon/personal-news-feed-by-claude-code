# SF-1353 - Documentatie: runbook.md - dode ArgoCD-bestandsverwijzingen en foutieve path-filter-troubleshooting-tip corrigeren

## Story

Documentatie: runbook.md - dode ArgoCD-bestandsverwijzingen en foutieve path-filter-troubleshooting-tip corrigeren

<!-- refined-by-factory -->

## Scope

`runbook.md` bevat twee zelf-tegensprekende/onjuiste verwijzingen die alleen tekstueel gecorrigeerd moeten worden (geen codewijziging):

1. **Dode ArgoCD-bestandsverwijzingen** — §2 "Architectuur" (regel 52) verwijst naar `deploy/applicationset.yaml` en §7 "Externe systemen" (regel 225) naar `deploy/argocd-application.yaml`. Beide paden bestaan niet meer in deze repo (geverifieerd: `deploy/` bevat ze niet). De echte, actuele locatie is `robberts-infrastructure/manifests/root-app/apps/` (ArgoCD `Application` + `ApplicationSet` + `github-pr-token`-SealedSecret + preview-ns-labeller Deployment/RBAC zitten daar sinds 2026-07-08, root-Application-consolidatie) — dit staat correct beschreven in `deploy/README.md` (secties "Eenmalige cluster-setup" en "Preview-deploys per PR (S-06)"), niet elders in `runbook.md` zelf zoals de oorspronkelijke issuetekst suggereerde.
   Corrigeer beide regels in `runbook.md` zodat ze naar de juiste bron verwijzen, bijvoorbeeld door `deploy/applicationset.yaml` en `deploy/argocd-application.yaml` te vervangen door een verwijzing naar `robberts-infrastructure/manifests/root-app/apps/` (en/of naar `deploy/README.md` voor het volledige verhaal), zonder een nieuw pad te verzinnen dat niet bestaat.

2. **Foutieve troubleshooting-tip over path-filter** — §8 "Veelvoorkomende taken / troubleshooting" (regels 279-280) beweert dat een preview op "Pending" blijft hangen door een paths-filter in `build-images.yml`. Geverifieerd: de `pull_request`-trigger in `.github/workflows/build-images.yml` heeft bewust **geen** paths-filter (zie de comment op regels 12-16: expliciet om te voorkomen dat docs-only PR's een ImagePullBackOff geven doordat de preview een niet-bestaande image probeert te pullen). Alleen de `push`-trigger (naar `main`) heeft een paths-filter; die is irrelevant voor PR-previews.
   Verwijder deze tip of herschrijf hem zodat hij niet langer het (niet-bestaande) build-images-path-filter-mechanisme als oorzaak noemt.
   Let op: `deploy/README.md` (§"Preview-deploys per PR (S-06)" → "Beperkingen") bevat dezelfde onjuiste bewering, maar die valt buiten scope van deze story (alleen `runbook.md` mag wijzigen).

## Acceptance criteria

- `runbook.md` verwijst nergens meer naar `deploy/applicationset.yaml` of `deploy/argocd-application.yaml`.
- De ArgoCD Application/ApplicationSet-verwijzingen in `runbook.md` (§2 en §7) wijzen naar de daadwerkelijk actuele bron (`robberts-infrastructure/manifests/root-app/apps/`, evt. met pointer naar `deploy/README.md` voor detail), zodat `runbook.md` zichzelf niet meer tegenspreekt.
- De troubleshooting-tip in §8 over "Preview hangt op Pending" bevat geen bewering meer die het daadwerkelijke `pull_request`-triggergedrag van `build-images.yml` (géén paths-filter) tegenspreekt. Dit mag opgelost worden door: (a) de tip te verwijderen, of (b) te herschrijven met een geverifieerde, actueel bestaande oorzaak (bijv. verwijzend naar de ApplicationSet-pollinterval of namespace-labelling-afhankelijkheid beschreven in `deploy/README.md`'s "Beperkingen"-sectie) — zonder een niet-bestaand mechanisme als oorzaak te noemen.
- Geen enkele andere file dan `runbook.md` wordt gewijzigd (met name niet `deploy/README.md`, ook al bevat dat bestand dezelfde foutieve path-filter-bewering).
- Geen wijzigingen aan code, workflows of deploy-manifesten.

## Aannames

- De "correcte beschrijving verderop in hetzelfde bestand, regels 35-58" uit de oorspronkelijke issuetekst is niet letterlijk juist — die beschrijving (met SealedSecret-detail en `robberts-infrastructure`-verwijzing) staat in `deploy/README.md`, niet in `runbook.md` zelf. De developer mag daarnaar verwijzen in plaats van te zoeken naar een niet-bestaande sectie binnen `runbook.md`.
- Voor de troubleshooting-tip is zowel "verwijderen" als "vervangen door een geverifieerde actuele oorzaak" een geldige oplossing; bij twijfel over de juistheid van een vervangende oorzaak heeft verwijderen de voorkeur boven een nieuwe onjuiste bewering introduceren.
- `deploy/README.md` bevat dezelfde foutieve path-filter-bewering als `runbook.md`, maar het corrigeren daarvan is expliciet buiten scope van deze story (acceptatiecriterium: "geen codewijziging, alleen runbook.md"). Dit is een bekende, gemelde afwijking voor een eventuele vervolgstory.

## Eindsamenvatting

## Eindsamenvatting SF-1353 — Runbook.md: dode ArgoCD-verwijzingen en foutieve path-filter-tip gecorrigeerd

**Wat is gebouwd:**
`runbook.md` bevatte twee onjuiste/verouderde verwijzingen die zichzelf tegenspraken met de daadwerkelijke repo-inhoud. Beide zijn tekstueel gecorrigeerd, zonder enige codewijziging:

1. **Dode ArgoCD-bestandsverwijzingen** (§2 Architectuur, regel 52 en §7 Externe systemen, regel 225): de verwijzingen naar de niet meer bestaande paden `deploy/applicationset.yaml` en `deploy/argocd-application.yaml` zijn vervangen door een verwijzing naar de daadwerkelijk actuele locatie `robberts-infrastructure/manifests/root-app/apps/`, met een pointer naar `deploy/README.md` voor het volledige verhaal.
2. **Foutieve troubleshooting-tip** (§8, "Preview hangt op Pending"): de bewering dat dit komt door een paths-filter in `build-images.yml` is verwijderd — die trigger heeft bewust géén paths-filter (bevestigd via de comment op regels 12-16 in dat workflow-bestand). Hiervoor in de plaats staat nu de geverifieerde, werkelijke oorzaak: de ArgoCD ApplicationSet pollt elke ~3 minuten naar nieuwe/gewijzigde PR's (zie `deploy/README.md` §"Preview-deploys per PR (S-06)").

**Gemaakte keuzes:**
- Voor de troubleshooting-tip is gekozen voor herschrijven met een geverifieerde oorzaak in plaats van schrappen, omdat de ApplicationSet-pollinterval expliciet als geldig alternatief was aangedragen en concreet te onderbouwen was vanuit `deploy/README.md`.
- Bewust géén wijziging aangebracht in `deploy/README.md`, ondanks dat dit bestand dezelfde foutieve path-filter-bewering bevat — expliciet buiten scope van deze story (bekende, gemelde afwijking voor eventuele vervolgstory).

**Getest:**
- Er zijn geen build/test-commando's van toepassing op een pure markdown-wijziging.
- Gecontroleerd met `grep` dat de dode paden (`deploy/applicationset.yaml`, `deploy/argocd-application.yaml`) nergens meer voorkomen in `runbook.md`.
- Bevestigd met `git status --porcelain` dat uitsluitend `runbook.md` is gewijzigd — geen wijzigingen aan `deploy/README.md`, code, workflows of manifesten.
- Story-brede test (SF-1375) is afgerond en goedgekeurd (fase test-approved).

**Bewust niet gedaan:**
- De identieke foutieve path-filter-bewering in `deploy/README.md` is niet gecorrigeerd — buiten scope van deze story.
