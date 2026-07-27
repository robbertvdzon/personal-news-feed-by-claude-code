# SF-1353 - Worklog

Story-context bij eerste pickup:
Corrigeer dode ArgoCD-verwijzingen en foutieve path-filter-tip in runbook.md

In runbook.md: (1) vervang regel 52's verwijzing naar deploy/applicationset.yaml en regel 225's verwijzing naar deploy/argocd-application.yaml door een pointer naar de daadwerkelijk actuele bron robberts-infrastructure/manifests/root-app/apps/ (evt. met verwijzing naar deploy/README.md voor detail), zonder een niet-bestaand pad te verzinnen; (2) herschrijf of verwijder de troubleshooting-tip op regels 279-280 die een niet-bestaande paths-filter in build-images.yml als oorzaak van een hangende 'Pending'-preview noemt (build-images.yml's pull_request-trigger heeft bewust geen paths-filter, zie comment regels 12-16). Als vervangende oorzaak kan de geverifieerde ApplicationSet-pollinterval (~3 min, zie deploy/README.md §Preview-deploys per PR (S-06)) gebruikt worden; bij twijfel over een vervangende oorzaak heeft schrappen van de tip de voorkeur. Wijzig uitsluitend runbook.md, geen andere bestanden (met name niet deploy/README.md), geen code/workflow/manifest-wijzigingen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Regel 52 (§2) en regel 225 (§7) in `runbook.md` gecorrigeerd: `deploy/applicationset.yaml`
  resp. `deploy/argocd-application.yaml` (bestaan niet meer) vervangen door een verwijzing naar
  `robberts-infrastructure/manifests/root-app/apps/` (+ pointer naar `deploy/README.md`).
- Troubleshooting-tip "Preview hangt op Pending" (voorheen regels 279-280) herschreven: de
  onjuiste bewering over een paths-filter in `build-images.yml` (die trigger heeft bewust géén
  paths-filter, zie comment regels 12-16 aldaar) is vervangen door de geverifieerde oorzaak
  (ArgoCD ApplicationSet pollt elke ~3 min, zie `deploy/README.md` §"Preview-deploys per PR
  (S-06)").
- Alleen `runbook.md` gewijzigd (`git status --porcelain` bevestigt: geen wijzigingen aan
  `deploy/README.md`, code, workflows of manifesten). Geen build/testcommando's van toepassing
  op een pure markdown-wijziging; `grep` bevestigt dat de dode paden nergens meer voorkomen.
