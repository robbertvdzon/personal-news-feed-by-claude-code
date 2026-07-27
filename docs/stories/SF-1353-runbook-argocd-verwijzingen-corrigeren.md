# SF-1353 - Documentatie: runbook.md - dode ArgoCD-bestandsverwijzingen en foutieve path-filter-troubleshooting-tip corrigeren

## Story

Corrigeer dode ArgoCD-verwijzingen en foutieve path-filter-tip in runbook.md (subtaak `SF-1374`).

<!-- refined-by-factory -->

## Scope

`runbook.md` bevat twee zelf-tegensprekende/onjuiste verwijzingen die alleen tekstueel gecorrigeerd worden (geen codewijziging):

1. Dode ArgoCD-bestandsverwijzingen in §2 "Architectuur" (regel 52, `deploy/applicationset.yaml`) en §7 "Externe systemen" (regel 225, `deploy/argocd-application.yaml`). Beide paden bestaan niet meer; vervangen door een verwijzing naar `robberts-infrastructure/manifests/root-app/apps/` (met pointer naar `deploy/README.md` voor detail).
2. Foutieve troubleshooting-tip in §8 (regels 279-280) die een niet-bestaande paths-filter in `build-images.yml` als oorzaak van een hangende "Pending"-preview noemt. Herschreven met de geverifieerde oorzaak: de ArgoCD ApplicationSet pollt elke ~3 min GitHub (zie `deploy/README.md` §"Preview-deploys per PR (S-06)").

Alleen `runbook.md` is gewijzigd; `deploy/README.md` bevat dezelfde foutieve path-filter-bewering maar valt expliciet buiten scope.

## Acceptance criteria

- `runbook.md` verwijst nergens meer naar `deploy/applicationset.yaml` of `deploy/argocd-application.yaml`.
- De ArgoCD Application/ApplicationSet-verwijzingen in §2 en §7 wijzen naar `robberts-infrastructure/manifests/root-app/apps/` (met pointer naar `deploy/README.md`).
- De troubleshooting-tip in §8 noemt geen niet-bestaand build-images-path-filter-mechanisme meer als oorzaak.
- Geen enkele andere file dan `runbook.md` is gewijzigd.
- Geen wijzigingen aan code, workflows of deploy-manifesten.

## Aannames

- `deploy/README.md` bevat dezelfde foutieve path-filter-bewering, maar het corrigeren daarvan valt expliciet buiten scope van deze story (bekende, gemelde afwijking voor een eventuele vervolgstory).

### Stappenplan

- [x] Lees issue-context en relevante bronnen (`.github/workflows/build-images.yml`, `deploy/README.md`).
- [x] Corrigeer regel 52 (§2) en regel 225 (§7) in `runbook.md` naar `robberts-infrastructure/manifests/root-app/apps/`.
- [x] Herschrijf de troubleshooting-tip in §8 (voorheen regels 279-280) met een geverifieerde oorzaak (ApplicationSet-pollinterval ~3 min).
- [x] Verifieer dat alleen `runbook.md` is gewijzigd en dat geen dode verwijzingen meer voorkomen.

### Gedaan / rationale

- Beide dode ArgoCD-bestandsverwijzingen (`deploy/applicationset.yaml`, `deploy/argocd-application.yaml`) vervangen door een verwijzing naar de daadwerkelijk actuele bron `robberts-infrastructure/manifests/root-app/apps/`, met pointer naar `deploy/README.md` voor het volledige verhaal — geen nieuw, niet-bestaand pad verzonnen.
- De troubleshooting-tip herschreven (niet verwijderd) met de in `deploy/README.md` geverifieerde oorzaak: de ArgoCD ApplicationSet pollt elke ~3 min GitHub voor nieuwe/gewijzigde PR's. De onjuiste bewering dat `build-images.yml` een paths-filter op de `pull_request`-trigger heeft, is verwijderd (geverifieerd: die trigger heeft bewust geen paths-filter, zie de comment op regels 12-16 van dat workflow-bestand).
- Puur documentatiewijziging, geen code/workflow/manifest gewijzigd; geen builds/tests van toepassing (`development.md`-vangnet is voor backend/frontend-code, niet voor markdown-only wijzigingen).
