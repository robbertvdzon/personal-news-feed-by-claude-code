# AI-driven dev pipeline — roadmap

Stories in volgorde van oppakken. Per story max ~4 zinnen — alleen de
intentie, niet het technische "hoe". Pak ze één voor één op.

---

## S-01 · Claude-runner pod (PoC, handmatig getriggerd)
Een pod in de cluster die we starten met één JIRA-ticket-ID als argument.
De pod cloned de repo, draait Claude tegen de story, commit + pusht naar
een feature-branch, en opent een PR met de ticket-ID in de titel.
Doel is bewijzen dat de autonome flow werkt op deze codebase — JIRA, mobile,
preview-deploys volgen later.
**Klaar als**: handmatig commando levert een PR die we als mens kunnen reviewen.

## S-02 · Branch- + commit-msg-conventie
Vaste naamgeving: branch `ai/<JIRA-ID>`, commits altijd `<JIRA-ID>: <bericht>`.
Een pre-commit-check faalt als de conventie niet klopt.
Maakt latere koppeling met JIRA en preview-deploys eenduidig.
**Klaar als**: regels gedocumenteerd + check actief in CI én in de Claude-runner.

## S-03 · JIRA-webhook ontvanger
Kleine service in de cluster die luistert op JIRA-status-changes via een Cloudflare-hostname.
Zodra een ticket in column `ai-ready` komt: enqueue voor de runner.
Geen verdere logica — alleen "ticket X is klaar om opgepakt te worden".
**Klaar als**: status-flip in JIRA → log-regel "queued: PNF-42" in de receiver-pod.

## S-04 · Story-queue + concurrency
De runner pakt stories uit de queue (max N parallel).
Elke story krijgt z'n eigen worktree + container; ze raken elkaars files niet.
Bij overflow: stories wachten netjes in de queue.
**Klaar als**: 3 stories tegelijk in `ai-ready` resulteert in 3 parallelle PR's.

## S-05 · Spec-update als onderdeel van de PR
De Claude-runner moet bij elke PR ook `/specs/` aanpassen waar relevant.
Zonder spec-update faalt de PR-check.
Voorkomt dat code en docs uit elkaar lopen.
**Klaar als**: elke geautomatiseerde PR raakt zowel code als docs als 't ticket dat vraagt.

## S-06 · Per-branch preview-deploys
Elke `ai/<JIRA-ID>`-branch krijgt automatisch een eigen namespace + URL,
b.v. `https://pnf-42.vdzonsoftware.nl`.
Eigen Neon-DB-branch zodat preview-data productie niet raakt.
**Klaar als**: PR opent → ~3 min later staat de preview-URL als comment in de PR én in 't JIRA-ticket.

## S-07 · JIRA-update bij PR-open en bij merge
Webhook-service post comments naar 't JIRA-ticket: "preview op X", "gemerged in Y".
Bij merge: ticket transition naar `Done` (of jouw equivalent).
**Klaar als**: vanuit JIRA volg je elke fase zonder GitHub te openen.

## S-08 · Mobile approval-UI
Een eenvoudige webpagina (live op de cluster) met een lijst openstaande PR's.
Per PR knoppen: **Approve** (= merge + prod-deploy), **Comment** (= tekst sturen).
Auth via Cloudflare Access of een simpele token-link.
**Klaar als**: op je telefoon zie je openstaande PR's en kunt 1-tap approven.

## S-09 · Comment-iteratieloop
Comment vanaf de mobile-UI of in JIRA → Claude-runner pakt 't op,
blijft op dezelfde branch, doet de aanpassing, redeploy't preview.
Loop herhaalt tot je approve't.
**Klaar als**: 3 iteraties op één ticket leiden tot één gemergede PR.

## S-10 · Approve → merge → prod-deploy
Bij approve: CI doet automatisch merge naar main, ArgoCD ziet de update,
prod krijgt de nieuwe versie binnen ~3 minuten.
Branch + preview-namespace worden opgeruimd.
**Klaar als**: één tap op je telefoon eindigt in live productie.

## S-11 · Spec-formaat voor regressietests
De `e2e/scenarios/*.md` worden uitgebreid met een gestandaardiseerd
"verwachte uitkomst"-blok dat een runner kan checken.
Bestaande scenario's worden omgezet naar dit nieuwe formaat.
**Klaar als**: één runner kan alle scenario's automatisch afspelen + pass/fail rapporteren.

## S-12 · Nachtelijke regressietest + e-mail
Cron-job runt elke nacht alle scenario's tegen prod (of een vaste staging).
Resultaat wordt als rapport gemaild met scenario-status + screenshots bij fail.
**Klaar als**: elke ochtend zie je in je mail of er regressies zijn.

## S-13 · Spec-compliance-checker
Een Claude-job vergelijkt de huidige code tegen `/specs/backend-technical-spec.md`
en flagt afwijkingen (bv. "FeedRepository gebruikt nu lokale variabele i.p.v. JdbcTemplate").
**Klaar als**: handmatige run levert een lijst geconstateerde afwijkingen op.

## S-14 · Nachtelijke compliance-check + e-mail
Idem als S-13 maar als cron-job, met e-mail-rapport.
**Klaar als**: elke ochtend zie je een e-mail met afwijkingen tussen code en specs.

## S-15 · Dagelijkse kostenmail
Cron-job aggregeert Anthropic-API-kosten van afgelopen 24u (per story-run, regressie, compliance)
en mailt een sober overzicht.
**Klaar als**: elke ochtend zie je in mail wat je gisteren aan Claude API uitgaf.

---

## Volgorde-rationale

- **S-01** eerst om te bewijzen dat 't überhaupt kan op deze codebase.
- **S-02 t/m S-07** bouwen de JIRA → PR-flow uit zonder mobiel.
- **S-08 t/m S-10** maken de feedback-loop af tot productie.
- **S-11 t/m S-14** zijn kwaliteitsbewaking en kunnen parallel met de rest.
- **S-15** is operationeel en kan op elk moment.

Phases 1-3 (S-01 t/m S-10) = volledige MVP, schatting **3-4 weken** focused werk.
Phases 4-5 (S-11 t/m S-15) = optionele verbeteringen, **1-2 weken** extra.
