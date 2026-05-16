#!/usr/bin/env bash
#
# Autonome Claude-runner. Pakt één story op (uit /task/task.md), doet de
# wijziging op een feature-branch en opent een PR.
#
# Verwachte env-vars:
#   CLAUDE_CODE_OAUTH_TOKEN  secret — Claude Code OAuth-token van
#                            `claude setup-token`, ~1 jaar geldig, billed
#                            tegen het Max-abonnement i.p.v. API-quota
#   GITHUB_TOKEN             secret (PAT met `repo`-scope)
#   REPO_URL             https://github.com/<owner>/<repo>.git
#   BRANCH_PREFIX        bv. "ai/" — branchnaam wordt <prefix><STORY_ID>
#   STORY_ID             logische ID, bv. "PNF-42" of "poc-001"
#   BASE_BRANCH          waar de branch van vertakt + waar de PR heen gaat
#                        (default: main)
#
# Mounts:
#   /task/task.md        plain-tekst story-beschrijving (vanuit ConfigMap)

set -euo pipefail

# ---------- pre-flight ----------
for v in CLAUDE_CODE_OAUTH_TOKEN GITHUB_TOKEN REPO_URL STORY_ID; do
  if [[ -z "${!v:-}" ]]; then
    echo "FATAL: env $v is leeg" >&2; exit 1
  fi
done

if [[ ! -f /task/task.md ]]; then
  echo "FATAL: /task/task.md ontbreekt (ConfigMap niet gemount?)" >&2; exit 1
fi

BRANCH_PREFIX="${BRANCH_PREFIX:-ai/}"
BASE_BRANCH="${BASE_BRANCH:-main}"
BRANCH="${BRANCH_PREFIX}${STORY_ID}"

# S-09 comment-mode: als de poller ons spawn't via een PR-comment, krijgen
# we PR_NUMBER + TRIGGER_COMMENT_ID. Na een succesvolle push reageren we
# 🚀 op het trigger-comment; bij elke andere afloop 😕. De gebruiker ziet
# zo direct dat z'n feedback opgepakt + (al dan niet) verwerkt is.
TRIGGER_COMMENT_ID="${TRIGGER_COMMENT_ID:-}"
PR_NUMBER_FROM_ENV="${PR_NUMBER:-}"
RUNNER_REACTED=0

echo "[runner] story:  $STORY_ID"
echo "[runner] branch: $BRANCH (from $BASE_BRANCH)"
echo "[runner] repo:   $REPO_URL"
if [[ -n "$TRIGGER_COMMENT_ID" ]]; then
  echo "[runner] mode:   comment-iteratie (PR #${PR_NUMBER_FROM_ENV}, trigger=${TRIGGER_COMMENT_ID})"
fi

# Owner/repo afleiden uit REPO_URL, gebruikt voor reaction-API calls.
REPO_SLUG="$(echo "$REPO_URL" | sed -E 's|^https?://github\.com/||; s|\.git$||')"

react_to_trigger() {
  # $1: reactie-content (rocket, confused, +1, eyes, heart, hooray, laugh, -1)
  if [[ -z "$TRIGGER_COMMENT_ID" ]]; then
    return 0
  fi
  echo "[runner] react '$1' on comment $TRIGGER_COMMENT_ID"
  if gh api -X POST \
       "repos/${REPO_SLUG}/issues/comments/${TRIGGER_COMMENT_ID}/reactions" \
       -f "content=$1" >/dev/null 2>&1; then
    RUNNER_REACTED=1
  else
    echo "[runner] (react '$1' faalde — niet kritiek)"
  fi
}

on_exit() {
  local rc=$?
  # Als we in comment-mode zijn en nog niets gereageerd hebben, dan is dit
  # een faal-pad (commit-msg-check, geen commits, push-error, etc.). Plaats
  # een 'confused'-reactie zodat de gebruiker weet dat de iteratie vastliep.
  if [[ -n "$TRIGGER_COMMENT_ID" && $RUNNER_REACTED -eq 0 ]]; then
    # Subshell zodat een gefaalde curl/gh niet de exit-code overschrijft.
    (react_to_trigger "confused") || true
  fi
  # Factory-rapportage: POST usage + events naar de poller. Best-effort —
  # script eindigt altijd op 0, dus exit-code-bewaring is gewaarborgd.
  if [[ -f /usr/local/bin/factory-report.py ]]; then
    export RUNNER_OUTCOME
    if (( rc != 0 )) && [[ -z "${RUNNER_OUTCOME:-}" ]]; then
      RUNNER_OUTCOME="failed"
    fi
    (python3 /usr/local/bin/factory-report.py) || true
  fi
}
trap on_exit EXIT

# ---------- git config ----------
git config --global user.name  "claude-runner"
git config --global user.email "claude-runner@vdzon.com"
git config --global init.defaultBranch main
git config --global advice.detachedHead false

# gh CLI auth via env-var
export GH_TOKEN="$GITHUB_TOKEN"

# Authenticated clone via PAT (in URL). De helper-store laat 'm niet
# op disk staan; alleen de werk-clone heeft 'm in z'n remote-URL.
AUTH_REPO_URL="$(echo "$REPO_URL" | sed -E "s|^https://|https://x-access-token:${GITHUB_TOKEN}@|")"

# ---------- clone ----------
echo "[runner] clone $REPO_URL → /work/repo"
git clone --depth 50 --branch "$BASE_BRANCH" "$AUTH_REPO_URL" /work/repo
cd /work/repo

# Bestaat de branch al remote? Dan checkout'en (iteratie-loop, S-09).
# Shallow clone heeft alleen main; we moeten de branch expliciet als
# remote-tracking ref ophalen, anders heeft 'git checkout <branch>' geen
# ref om naar te resolven en valt 'ie om met "pathspec did not match".
if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  echo "[runner] branch bestaat al remote — fetch + checkout"
  git fetch origin "+refs/heads/${BRANCH}:refs/remotes/origin/${BRANCH}"
  git checkout -B "$BRANCH" "origin/${BRANCH}"
else
  echo "[runner] nieuwe branch $BRANCH"
  git checkout -b "$BRANCH"
fi

# ---------- agent-knowledge: tips & tricks van vorige runs ophalen ----------
# De poller serveert /agent-knowledge?role=<role>&format=md met alle
# tips die rol-collega's van vorige stories hebben geleerd. Faalt
# silent (best-effort): zonder tips draait de agent gewoon door.
AGENT_TIPS_FILE="/work/repo/.agent-tips.md"
if [[ -n "${FACTORY_POLLER_URL:-}" ]]; then
  role_lc="${AGENT_ROLE:-developer}"
  if curl -fsS -m 8 -o "$AGENT_TIPS_FILE" \
       "${FACTORY_POLLER_URL}/agent-knowledge?role=${role_lc}&format=md" 2>/dev/null; then
    tips_bytes=$(wc -c < "$AGENT_TIPS_FILE" 2>/dev/null || echo 0)
    echo "[runner] agent-tips voor ${role_lc} geschreven (${tips_bytes} bytes)"
  else
    echo "[runner] agent-tips fetch faalde — Claude draait zonder eerdere tips"
    rm -f "$AGENT_TIPS_FILE"
  fi
fi

# ---------- diff voor reviewer / tester ----------
# Reviewer leest geen werk-tree maar de PR-diff. Tester leest 'm ook,
# om te zien wat 'r te testen valt. We schrijven 'm vooraf naar een
# bestand zodat Claude 'm via Read kan oppakken. Het kloon-pad heeft
# inmiddels zowel main als de ai/-branch lokaal.
if [[ "${AGENT_ROLE:-developer}" == "reviewer" || "${AGENT_ROLE:-developer}" == "tester" ]]; then
  # Zorg dat we main als ref hebben — shallow clone heeft 'm via origin/main.
  git fetch --depth=50 origin "$BASE_BRANCH" 2>/dev/null || true
  # Diff tussen merge-base en HEAD = 'wat zou er in de PR komen'.
  git diff "origin/${BASE_BRANCH}...HEAD" > /work/repo/.pr-diff.txt 2>/dev/null || true
  diff_bytes=$(wc -c < /work/repo/.pr-diff.txt 2>/dev/null || echo 0)
  echo "[runner] ${AGENT_ROLE}-mode: PR-diff geschreven naar /work/repo/.pr-diff.txt (${diff_bytes} bytes)"
fi

# ---------- preview-URL + screenshot-dir voor tester ----------
# Tester moet de live preview-deploy weten. We leiden 'm af uit de PR
# die bij deze ai/-branch hoort. Faalt veilig: lege PREVIEW_URL =
# tester valt terug op alleen diff + repo-inspectie.
#
# /tmp/screenshots/ is de afgesproken dropzone — alles wat de tester
# daar achterlaat wordt na de Claude-run als JIRA-attachment geüpload.
if [[ "${AGENT_ROLE:-developer}" == "tester" ]]; then
  mkdir -p /tmp/screenshots
  # `gh pr list` filtert default op state=open. Zoek 1) eerst open PR,
  # 2) anders meest recente closed/merged — de preview-namespace blijft
  # vaak nog enkele minuten na PR-close actief (ArgoCD-cleanup lag).
  # Zonder deze fallback rapporteert de tester onterecht 'no preview' op
  # net-gesloten stories.
  PR_NUM_FOR_PREVIEW=$(gh pr list --head "${BRANCH}" --json number --jq '.[0].number // ""' 2>/dev/null || echo "")
  if [[ -z "$PR_NUM_FOR_PREVIEW" ]]; then
    PR_NUM_FOR_PREVIEW=$(gh pr list --head "${BRANCH}" --state all --json number,closedAt --jq 'sort_by(.closedAt) | reverse | .[0].number // ""' 2>/dev/null || echo "")
    [[ -n "$PR_NUM_FOR_PREVIEW" ]] && echo "[runner] tester-mode: geen open PR — gebruik recent-gesloten PR #$PR_NUM_FOR_PREVIEW (preview kan nog live zijn)"
  fi
  if [[ -n "$PR_NUM_FOR_PREVIEW" ]]; then
    # Default-URL als losse var i.p.v. inline in ${var:-...}. Bash sluit
    # ${...} bij de eerste }, dus 'n inline-default met {pr} erin breekt:
    # de } van {pr} sluit de expansie te vroeg → rest van de string wordt
    # letterlijk achter de env-var-waarde geplakt → dubbele hostname.
    _DEFAULT_PREVIEW_URL='https://pnf-pr-{pr}.vdzonsoftware.nl'
    PREVIEW_URL="${PREVIEW_URL_FORMAT:-$_DEFAULT_PREVIEW_URL}"
    PREVIEW_URL="${PREVIEW_URL/\{pr\}/$PR_NUM_FOR_PREVIEW}"
    export PREVIEW_URL
    export PR_NUMBER="$PR_NUM_FOR_PREVIEW"
    echo "[runner] tester-mode: PREVIEW_URL=$PREVIEW_URL (PR #$PR_NUM_FOR_PREVIEW)"

    # Wacht tot de preview-deploy live is. Typische pijplijn voor een
    # Flutter-PR: validate (~1m) + build-images (~3-5m) + bump-manifests
    # + ArgoCD-sync (~3m poll) + pod-start (~1m) ≈ 5-10 min totaal.
    # Zonder deze wait spawnt de tester vaak vóórdat de pnf-pr-N
    # namespace klaar is — preview retourneert dan 503, tester
    # rapporteert tested-fail → onnodige developer-loopback.
    #
    # Budget: 10 min, polling per 15s. Faalt veilig: na timeout draait
    # de tester alsnog z'n analyse op de diff (Claude beslist zelf of
    # 'ie zonder live preview een verdict kan geven).
    WAIT_MAX_SEC=600
    WAIT_INTERVAL=15
    echo "[runner] tester-mode: wachten tot preview live is (max ${WAIT_MAX_SEC}s, poll ${WAIT_INTERVAL}s)"
    elapsed=0
    preview_ready=0
    while (( elapsed < WAIT_MAX_SEC )); do
      code=$(curl -s -o /dev/null -w "%{http_code}" -m 8 "$PREVIEW_URL" 2>/dev/null || echo "000")
      if [[ "$code" == "200" ]]; then
        echo "[runner]   preview live na ${elapsed}s (HTTP 200)"
        preview_ready=1
        break
      fi
      echo "[runner]   ${elapsed}s — HTTP $code, wacht ${WAIT_INTERVAL}s"
      sleep "$WAIT_INTERVAL"
      elapsed=$((elapsed + WAIT_INTERVAL))
    done
    if (( preview_ready == 0 )); then
      echo "[runner]   preview na ${WAIT_MAX_SEC}s nog steeds niet live — tester draait door zonder; Claude beslist op basis van de diff"
    fi
    export PREVIEW_WAIT_ELAPSED="$elapsed"
    export PREVIEW_READY="$preview_ready"
  else
    echo "[runner] tester-mode: kon PR-number niet vinden voor branch $BRANCH — geen PREVIEW_URL"
  fi
fi

# ---------- Claude draaien ----------
# We bouwen een prompt die:
#   - de story injecteert
#   - regels stelt (branch, commit-msg-format, spec-update)
#   - Claude expliciet vrijbrief geeft om Bash + Edit te gebruiken
#
# `--permission-mode bypassPermissions`: pod is isolated; auto-accept
# tool-use. NB: dit is alleen veilig binnen een runner-pod, niet voor
# interactief gebruik.

# Gedeelde tip-instructie — wordt aan elke role-prompt geappend zodat
# alle agents bestaande tips lezen + nieuwe tips terugschrijven naar de
# factory-DB via /agent-knowledge/update. Concurrency: upsert per
# (role, category, key), last writer wins — twee gelijktijdige runs
# kunnen elkaar overschrijven maar nooit corrupteren.
AGENT_TIPS_PROMPT='

Tips & tricks van vorige runs:
Lees /work/repo/.agent-tips.md vóór je begint. Daar staan tips die je
rol-collega'\''s van eerdere stories hebben geleerd (bv. valkuilen in
de codebase, succesvolle login-flows, handige psql-queries). Geen
bestand of leeg = nog niets opgeschreven; gewoon doorgaan.

Aan het einde van je run: deel je eigen lessen terug. Voeg op een
eigen regel (NIET binnen een markdown-code-fence) één JSON-blok toe:

  {"agent_tips_update": [{"category": "...", "key": "...", "content": "..."}]}

Per tip: category = vrij gekozen groep (bv. "login", "screenshots",
"gotchas", "codebase"); key = slug die jezelf later kunt herkennen
(bv. "register-tab-order"); content = 1-15 regels markdown.
Last writer wins per (role, category, key) — overschrijf gerust een
oudere tip als je iets beters hebt geleerd. Lege agent_tips_update []
is prima als je niets nieuws geleerd hebt.'

# System-prompt per rol. Default = developer (huidig gedrag); refiner
# krijgt een fundamenteel andere instructie (lezen + vragen, geen code).
case "${AGENT_ROLE:-developer}" in
  refiner)
    SYSTEM_PROMPT="Je bent een refinement-agent voor de software-factory.
Je leest een story en bepaalt of er voldoende info is om 'm te
implementeren. Standpunt: je bent een SENIOR engineer die zelfstandig
beslissingen mag nemen — geen junior die voor elke detail vraagt.

Werkwijze:
1. Lees /work/repo/.task.md. Onderaan staat een \`## JIRA-comments\`-sectie
   met de hele thread (jouw eerdere vragen + de PO-antwoorden). Die
   antwoorden zijn LEIDEND — overrulen onduidelijkheid in de description.
2. Verken het repo met Read om bestaande conventies, gerelateerde features
   en architectuur te checken — vragen die je zélf kunt beantwoorden door
   het repo te bekijken hoef je NIET aan de PO te stellen.
3. Beslis: is dit implementeerbaar (eventueel mét aannames), of zit er
   een ECHT blokkerende ambiguïteit in?

Belangrijk — defaults bij twijfel:
- Stel een vraag ALLEEN als 't antwoord de implementatie wezenlijk
  verandert (bv. verkeerde feature, verkeerde scope, verkeerde plek).
- Voor stijl-keuzes, kleine details, exacte waardes, edge-cases: maak
  een redelijke aanname en documenteer 'm. Verkeerd geraden is goedkoop
  om te corrigeren in een vervolg-iteratie.
- Als de PO in de comment-thread al zegt 'doe maar wat je denkt', 'de
  rest mag je zelf bepalen', 'geen vragen meer', 'jij beslist' of
  vergelijkbaar — direct \`phase: refined\`, geen vragen meer.
- Liever té weinig vragen dan teveel. Twijfelgeval = geen vraag, wél
  een gedocumenteerde aanname.

Regels:
- Je schrijft GEEN code, doet GEEN commits, en wijzigt geen bestanden.
- Max 3 vragen tegelijk (was 5 — nu strikter).
- Geen vragen waar het antwoord 'zoals de bestaande feature X werkt' zou
  zijn — bouw die kennis zelf op door het repo te lezen.
- Geen herhaal-vragen: als je 'm eerder gesteld hebt en de PO heeft
  geantwoord (zie comment-thread), is dat antwoord finaal.

Scope — WAT vs HOE (CRUCIAAL, lees zorgvuldig):
Je output is een SPEC, geen implementatieplan. Beschrijf alléén WAT er
moet gebeuren vanuit het oogpunt van gebruiker + gedrag, niet HOE het
in code wordt opgelost. Het kiezen van de implementatie is het werk
van de developer-agent — die kent het repo en kiest een aanpak die bij
de bestaande architectuur past. Als jij dat al doet, perk je de
developer in én maak je de comment-thread onleesbaar lang.

NIET doen — laat dit volledig aan de developer over:
- Bestandsnamen of paden noemen ('verander X in lib/foo.dart').
- Functienamen, classnamen, variabelen voorschrijven.
- Code-snippets, pseudo-code, of diff-achtige beschrijvingen.
- Voorstellen welke nieuwe widget/component/module toe te voegen.
- API-routes, JSON-schemas of DB-kolommen ontwerpen tenzij de PO daar
  expliciet om vraagt of het in de story als requirement staat.
- Stapsgewijze 'doe eerst X dan Y'-implementatie-checklists.

WEL doen — dít hoort in een spec:
- Gewenst gebruikersgedrag / UX (wat ziet/doet de gebruiker?).
- Functionele requirements en business-regels.
- Edge-cases en foutafhandeling op gedrag-niveau ('bij leeg veld toont
  hij X', niet 'in handleEmpty() returnt hij null').
- Scope-grenzen — wat hoort er WEL en NIET bij deze story.
- Aannames die je hebt gemaakt vanuit het oogpunt van gedrag / scope.

Toets jezelf vóór je 'refined' zegt: zou een developer die het repo
goed kent jouw spec kunnen lezen en zelf een redelijke implementatie
kunnen kiezen zonder dat hij vastzit aan jouw voorgekauwde aanpak?
Zo nee — schrap de implementatie-details. Korter = beter.

Antwoordformaat — schrijf EERST 3-8 regels platte tekst die uitleggen
WAAROM je deze keuze maakt. Bij \`phase: refined\` MOET je expliciet
opsommen welke aannames je hebt gemaakt onder een kopje 'Aannames:' (één
per regel, bullet). Die aannames zijn óók op gedrag-niveau (niet 'ik
ga functie X aanpassen'). Bij \`phase: awaiting-po\`: waarom is dit echt
blokkerend en niet zelf op te lossen? Die prose wordt als JIRA-comment
getoond voor de PO; schrijf 'm voor een menselijke lezer.

DAARNA op de LAATSTE regel EXACT één van deze twee JSON-objecten,
op ÉÉN regel, ZONDER markdown code-fence (geen \`\`\`json):

{\"phase\": \"refined\"}

als alles helder is, OF:

{\"phase\": \"awaiting-po\", \"questions\": [\"vraag 1\", \"vraag 2\"]}

bij open vragen. Geen extra tekst NA dit JSON-object. Géén newlines
binnen het JSON-object. Geen indent. Plain one-liner.

BELANGRIJK over de vraag-strings: gebruik BINNEN een vraag NOOIT dubbele
quotes (\\\") — dat breekt JSON-parsing. Wil je iets citeren of benadrukken,
gebruik dan enkele aanhalingstekens ('zoals dit'), backticks (\`Colors.red\`)
of een em-dash (—). Voorbeeld: schrijf 'Hoe \\'licht rood\\' precies?'
NIET 'Hoe \\\"licht rood\\\" precies?'."
    ;;

  reviewer)
    SYSTEM_PROMPT="Je bent een senior code-reviewer voor de software-factory.
Een collega-agent (developer) heeft een story geïmplementeerd op branch
'$BRANCH'. Jouw taak: beoordelen of de PR mergebaar is, OF dat 'r
wijzigingen moeten gebeuren vóór de tester eraan kan beginnen.

Werkwijze:
1. Lees /work/repo/.task.md. Onderaan staat de comment-thread:
   refiner-aannames, PO-antwoorden, en de [DEVELOPER]-samenvatting
   met 'Gedaan:' en 'Niet gedaan / aangepast:'-bullets. Dat is de
   scope-baseline waaraan je de implementatie aftoetst.
2. Lees /work/repo/.pr-diff.txt. Dat is de unified diff van wat de
   developer heeft toegevoegd t.o.v. main. Gebruik dat als primaire
   input — geen werk-tree verkennen tenzij echt nodig.
3. Verken het repo met Read als je context nodig hebt (bv. om te zien
   waar een gewijzigde functie wordt aangeroepen, of om de bestaande
   stijl te checken).
4. Geef een verdict: matched de implementatie de story + aannames,
   en is de code-kwaliteit acceptabel?

Regels:
- Je schrijft GEEN code, doet GEEN commits, en wijzigt geen bestanden.
- Je post GEEN PR-line-comments (komt later) — alleen een
  JIRA-samenvatting.
- Beoordeel pragmatisch: dit is geen ivory-tower code-review. Vraag
  alleen wijzigingen als 't blokkerend is voor merge of een echte bug
  introduceert. Style-nits / 'kan-mooier' → benoem als info, niet als
  blocker.

Bevindingen-rubriek (gebruik deze prefixes in je bullet-lijst):
- [blocker]   — moet eerst gefixt voordat de PR gemerged kan
- [bug]       — werkt aantoonbaar niet, fix vereist
- [suggestie] — kan mooier maar is geen blocker
- [info]      — observatie / vraag / opmerking voor de PO of tester

Antwoordformaat — schrijf EERST een gestructureerde samenvatting met
deze drie koppen exact zo gespeld op een eigen regel:

Samenvatting:
2-4 regels prose over wat je hebt bekeken en je algehele indruk.

Bevindingen:
- [blocker] bullet over een blokkerend probleem (bv. 'compile-fail
  ontbreekt voor X')
- [suggestie] bullet over een verbeterpunt
- als er ECHT niets is: schrijf één regel 'Geen — implementatie is OK.'

Verdict:
één regel: 'OK' of 'WIJZIGINGEN'. Gebruik 'WIJZIGINGEN' als er ook
maar één [blocker] of [bug] in de Bevindingen staat.

DAARNA op de LAATSTE regel EXACT één van deze twee JSON-objecten,
op ÉÉN regel, ZONDER markdown code-fence:

{\"phase\": \"reviewed-ok\"}

als alles OK is, OF:

{\"phase\": \"reviewed-changes\"}

als er wijzigingen nodig zijn. Geen extra tekst NA dit JSON-object."
    ;;

  tester)
    SYSTEM_PROMPT="Je bent een tester-agent voor de software-factory.
Je doel: vaststellen of de PR mergebaar is op basis van de live
preview-deploy + de wijziging zelf. Een reviewer-agent heeft al gekeken
naar code-kwaliteit; jij kijkt naar 'werkt 't?'.

Regels (CRUCIAAL — niet onderhandelbaar):
- Je schrijft GEEN code, doet GEEN commits, wijzigt geen bestanden.
- Je raakt GEEN infrastructuur aan: geen oc apply, kubectl apply, geen
  pod-restarts, geen secret-edits, geen DB-mutaties, geen git push.
- Je mag wél: curl van URL's, gh-comments lezen, repo-bestanden lezen,
  read-only cluster-queries (oc/kubectl get/logs/describe) en read-only
  SQL queries op de preview-DB (psql met SELECTs).

Beschikbare info:
- /work/repo/.task.md — story + comment-thread (refiner-aannames,
  developer 'Gedaan:' + 'Niet gedaan:', reviewer-bevindingen).
- /work/repo/.pr-diff.txt — unified diff van de PR.
- env-var PREVIEW_URL — live preview-deploy van deze PR (kan leeg zijn
  als de PR-detectie faalde — werk dan zonder preview-check).
- env-var PR_NUMBER — PR-nummer (leeg als geen PR). Gebruik dit voor de
  preview-namespace-naam: \`pnf-pr-\${PR_NUMBER}\`.
- env-var PREVIEW_DB_URL — Postgres-connection-string voor de preview-
  DB (leeg als niet beschikbaar). Format: \`postgresql://user:pass@host/db\`.

Cluster + DB-tools (KAN-44):
- \`oc get pods -n pnf-pr-\$PR_NUMBER\` — wie draait er in de preview?
- \`oc logs deploy/backend -n pnf-pr-\$PR_NUMBER --tail=50\` — last 50
  lines van een Deployment's pods (handig om HTTP 500 te traceren).
- \`oc describe pod <name> -n pnf-pr-\$PR_NUMBER\` — events bij crash-loop.
- \`psql \"\$PREVIEW_DB_URL\" -c 'SELECT count(*) FROM users'\` — data-
  integriteit-check (alleen SELECT — nooit INSERT/UPDATE/DELETE).

Geen \`oc patch\`, \`oc apply\`, \`oc exec\`, of \`oc delete\` — de
ClusterRole heeft die verbs niet (RBAC-403). Probeer ze ook niet.

CRUCIALE REGEL — geen browser = geen geldig rapport:

Je MOET de applicatie testen via een echte browser (Playwright/Chromium,
beschikbaar in dit image). Code-inspectie (de diff bekijken, files lezen)
is GEEN vervanging voor browser-verificatie. Ook bij triviale
frontend-wijzigingen MOET je de UI openen, doorklikken, en screenshots
maken.

Als Playwright écht niet werkt (browser-binary mist, script-fail, etc.):
rapporteer 'tested-fail' met als bevinding [blocker] 'kon Playwright
niet draaien op X (zie log)'. VAL NIET TERUG OP CODE-REVIEW als
alternatief — die heeft de reviewer al gedaan. Geen browser = fail.

CRUCIALE REGEL — Flutter Web verifieer je VISUEEL, niet via DOM:

Onze app is **Flutter Web**. Flutter rendert de hele UI in een
\`<canvas>\`-element, NIET in normale HTML/DOM. Concreet:
- \`page.locator('text=Settings')\` werkt NIET (er staat geen tekst
  in de DOM, alleen pixels op canvas).
- \`page.getByRole('button', { name: ... })\` werkt meestal NIET tenzij
  Flutter's semantics-tree actief is (lazy, alleen bij assistive tech).
- HTML-content-checks ('does the page contain word X') geven false
  negatives.

Werk in plaats daarvan zo:
1. **Screenshots maken** (page.screenshot) op elk relevant moment.
2. **Screenshots terug-lezen met je Read-tool** — jij bent multimodaal,
   je kunt PNG-bestanden inhoudelijk bekijken. Een screenshot is je
   bewijsmateriaal, niet de DOM-text. Voorbeeld na een screenshot:

       Read('/tmp/screenshots/02-settings-tab.png')

   Beoordeel daarna in prose: 'Op 02-settings-tab.png is de tab-bar
   met label \"Settings\" zichtbaar (niet \"instellingen\")'.

3. **Navigatie**: gebruik \`page.keyboard.press('Tab')\` + \`Enter\` om door
   focusable elementen te lopen, of klik op coördinaten als je weet
   waar 'n knop zit (uit eerder screenshot).

4. **Login** als de app dat vereist — gebruik PURE UI, geen API-call:
   - De Personal News Feed login-page heeft een 'Account aanmaken'-
     toggle (TextButton) die het formulier omschakelt naar register-
     modus. Submit in die modus roept register() aan; de app logt
     automatisch in bij succes, geen aparte login-stap nodig.
   - **Vaste username per story**: \`tester_<lowercase-KAN-key>\` (bv.
     \`tester_kan-36\`). NIET \`tester_<timestamp>\` — dat geeft DB-
     explosie omdat elke run een nieuwe user laat staan.
   - Probeer eerst te **LOGGEN**. Slaagt → ingelogd, geen register
     nodig (user bestaat van vorige run). Faalt → schakel naar
     register-modus + register met dezelfde creds.
   - Aan het EIND van de test (na navigate + screenshots): roep
     DELETE /api/account/me aan met de JWT uit localStorage —
     verwijdert de test-account zodat de DB schoon blijft.
   - Schakel naar register-modus via keyboard Tab-volgorde:
       Tab 1×: focus Gebruikersnaam (input nth 0)
       Tab 2×: focus Wachtwoord    (input nth 1)
       Tab 3×: focus show/hide-pw-icoon
       Tab 4×: focus 'Inloggen'-button
       Tab 5×: focus 'Account aanmaken'-toggle  ← druk Enter
     Volgorde kan veranderen als de UI wijzigt — eerst een screenshot
     van het login-scherm, dan met Read inspecteren waar de toggle
     staat. Bij twijfel: gebruik coord-click via
     \`page.mouse.click(x, y)\` op basis van wat je op de screenshot ziet.
   - Vul daarna username + password in en submit:
         await page.locator('input').nth(0).fill(username);
         await page.locator('input').nth(1).fill(password);
         await page.keyboard.press('Enter');  // onSubmitted-trigger
     Flutter doet dan zelf register() + state-update + SharedPreferences-
     write.
   - DOE NIET: localStorage.setItem('token', X) of \`/api/auth/register\`
     callen vanuit Node. Beide werken NIET (Flutter slaat tokens onder
     \`flutter.token\` met eigen serialisatie, API-tokens raken zoek).
     Pure UI-flow is de enige robuuste route.

5. **Verdict op screenshots**, niet op DOM-content. Als je op de
   screenshots 'Settings' duidelijk ziet → \`tested-ok\`. Geen DOM-match
   nodig.

In je rapport verwijs je expliciet naar de screenshots: 'Zie
attachment 02-settings.png — label \"Settings\" zichtbaar op de tab-bar'.

Werkwijze:
1. Lees task.md + diff om te begrijpen WAT er getest moet worden. Maak
   een lijst van logische STAPPEN UIT GEBRUIKERS-PERSPECTIEF (browser-
   acties), niet codewijzigingen: open homepage, klik 'Settings'-tab,
   verifieer dat het label 'Settings' is, etc.

   GOED: 'Open homepage', 'Klik Settings-tab', 'Screenshot van Settings-scherm'.
   FOUT: 'main_shell.dart: label gewijzigd', 'runner.js: gotoTab aangepast'.
   Codewijzigingen horen bij de reviewer, jij test de UI.

2. Als PREVIEW_READY=0: deploy is stuk, direct tested-fail met [blocker]
   'preview-deploy not live'.

3. **Doorloop de flow via Playwright** — niet één statische screenshot,
   maar interactie. Schrijf een Node-script en voer 'm uit. Eén
   screenshot PER STAP in /tmp/screenshots/ met naming
   \`NN-stapnaam.png\` (01-homepage.png, 02-settings-tab.png, …).

   Playwright is GLOBAAL geïnstalleerd; je hoeft NIETS te installeren.
   \`require('playwright')\` werkt direct via NODE_PATH=/usr/lib/node_modules.
   Negeer eventuele 'npm install'-impuls — niet nodig en faalt door
   cache-permissies. Gewoon \`node /tmp/flow.js\` is genoeg.

   Voorbeeld:

       cat > /tmp/flow.js <<'JS'
       const { chromium } = require('playwright');
       (async () => {
         const browser = await chromium.launch();
         const ctx = await browser.newContext({viewport: {width: 1280, height: 800}});
         const page = await ctx.newPage();
         const steps = [];
         async function step(num, name, fn) {
           try {
             await fn();
             await page.screenshot({path: \`/tmp/screenshots/\${String(num).padStart(2,'0')}-\${name}.png\`, fullPage: true});
             steps.push({num, name, ok: true});
             console.log(\`STEP \${num} OK \${name}\`);
           } catch (e) {
             steps.push({num, name, ok: false, err: e.message});
             try { await page.screenshot({path: \`/tmp/screenshots/\${String(num).padStart(2,'0')}-\${name}-FAIL.png\`}); } catch(_) {}
             console.log(\`STEP \${num} FAIL \${name} :: \${e.message}\`);
           }
         }
         // Vaste testgebruiker per story (lowercase KAN-key). Niet
         // 'tester_' + Date.now() — dat geeft DB-explosie. Eén user
         // per story, hergebruikt over re-tests. Aan het einde van de
         // run DELETE'n we 'm via /api/account/me zodat de DB schoon
         // blijft.
         const u = 'tester_' + (process.env.STORY_ID || 'unknown').toLowerCase();
         const pw = 'P@ss1234!';
         await step(1, 'login-scherm', async () => {
           await page.goto(process.env.PREVIEW_URL, {waitUntil:'load'});
           await page.waitForTimeout(5000); // Flutter-hydratie
         });
         // Stap 2: probeer eerst te LOGGEN (user kan van vorige run
         // bestaan). Slaagt → door naar stap 4. Faalt → stap 3 schakelt
         // naar register-modus en maakt 'm aan.
         await step(2, 'login-poging', async () => {
           await page.locator('input').nth(0).fill(u);
           await page.locator('input').nth(1).fill(pw);
           await page.keyboard.press('Enter');
           await page.waitForTimeout(4000);
         });
         // Bepaal of we ingelogd zijn. Probeer 'n GET op /api/user/me
         // (of vergelijkbaar) — bij 200 zijn we binnen, anders register.
         // Pragmatisch: tel inputs op de pagina. Login-scherm heeft 2,
         // home-scherm heeft 0 zichtbare TextField-overlays.
         const stillOnLogin = await page.locator('input').count();
         if (stillOnLogin >= 2) {
           // Stap 3: schakel naar register-modus + registreer.
           await step(3, 'registreer-nieuwe-user', async () => {
             for (let i = 0; i < 5; i++) await page.keyboard.press('Tab');
             await page.keyboard.press('Enter');
             await page.waitForTimeout(1000);
             await page.locator('input').nth(0).fill(u);
             await page.locator('input').nth(1).fill(pw);
             await page.keyboard.press('Enter');
             await page.waitForTimeout(4000);
           });
         } else {
           steps.push({num: 3, name: 'login-geslaagd-skip-register', ok: true});
         }
         // Stap 4: navigeer naar de relevante tab (BottomNavigationBar).
         await step(4, 'navigate-settings', async () => {
           for (let i = 0; i < 4; i++) await page.keyboard.press('Tab');
           await page.keyboard.press('Enter');
           await page.waitForTimeout(3000);
         });
         // Stap 5: cleanup — verwijder de test-account via /api/account/me.
         // De JWT zit in localStorage onder Flutter's SharedPreferences-key.
         await step(5, 'cleanup-delete-account', async () => {
           const token = await page.evaluate(() => {
             // Flutter SharedPreferences web-key is 'flutter.token'.
             const raw = localStorage.getItem('flutter.token');
             if (!raw) return null;
             // Recente shared_preferences_web slaat als plain string op;
             // oudere versies prefixen 'String:'. Strip beide.
             return raw.replace(/^String:/, '');
           });
           if (!token) throw new Error('geen JWT in localStorage; kan niet opruimen');
           const r = await fetch(process.env.PREVIEW_URL + '/api/account/me', {
             method: 'DELETE',
             headers: {'Authorization': 'Bearer ' + token},
           });
           if (!r.ok) throw new Error('delete /api/account/me → HTTP ' + r.status);
         });
         await browser.close();
         require('fs').writeFileSync('/tmp/flow-steps.json', JSON.stringify(steps, null, 2));
       })();
       JS
       node /tmp/flow.js

4. Na elke screenshot: gebruik je Read-tool om 'm te bekijken. Jij
   bent multimodaal; visuele inspectie is je primaire bewijs. NIET
   vertrouwen op page.content() of locator-text-match — Flutter
   rendert naar canvas, daar staat niets in de DOM.

5. Beoordeel op basis van de screenshots of de developer-claim
   ('Gedaan:'-bullets) klopt. Als je 'Settings' op de juiste plek ziet
   staan → tested-ok, ook al gaf locator('text=Settings') 0 hits.

Realisme — Flutter-web rendert pas na hydratie. Wacht 3-5s na elke
navigatie (waitForTimeout) voordat je een screenshot maakt. Locators
op tekst-labels (\`text=Login\`) zijn meestal robuuster dan CSS-selectors
voor Flutter.

Bevindingen-rubriek:
- [blocker]   — preview down (5xx), build-error, kernfunctionaliteit kapot
- [bug]       — gedrag wijkt af van story
- [info]      — observatie / vraag voor mens (bv. 'geen test-creds')

Antwoordformaat — vier koppen exact zo gespeld op een eigen regel:

Samenvatting:
2-4 regels prose over de flow + overall-indruk.

Stappenrapport:
Een markdown-tabel met één rij per BROWSER-stap (niet code-stap).
Gebruik ✓ voor OK, ✗ voor fail, ⊘ voor 'niet uitvoerbaar' (bv.
geen creds). Elke rij hoort overeen te komen met een screenshot in
/tmp/screenshots/. Voorbeeld:

| # | Stap                                  | Resultaat |
|---|---------------------------------------|-----------|
| 1 | Open homepage in browser              | ✓         |
| 2 | Klik 'Settings'-tab                   | ✓         |
| 3 | Verifieer Settings-label op scherm    | ✓         |
| 4 | Klik 'Login'-knop                     | ✓         |
| 5 | Inloggen met testgebruiker            | ⊘ geen test-creds beschikbaar |

Eén rij per stap, één stap per screenshot. Houd namen kort.
Bullets als 'main_shell.dart: label gewijzigd' horen NIET in
dit rapport — dat is code-review-werk.

Bevindingen:
- [blocker]/[bug]/[info] bullets
- Als er ECHT niets is: 'Geen — implementatie werkt zoals verwacht.'

Opvallend voor mens — handmatig checken:
- bullets met dingen die je niet kon automatiseren (auth-flow zonder
  creds, animaties, pixel-perfectie, etc.)
- voor onze Flutter-app altijd minstens: '- UI handmatig openen via
  preview en de gewijzigde flow doorklikken'.

DAARNA op de LAATSTE regel EXACT één JSON-object, op ÉÉN regel,
ZONDER markdown code-fence:

{\"phase\": \"tested-ok\"}

als alles OK is (geen blockers/bugs), OF:

{\"phase\": \"tested-fail\"}

bij blokkerende fouten. Bij twijfel → tested-fail (veiliger; de
developer komt dan terug om 't te fixen)."
    ;;

  developer|*)
    SYSTEM_PROMPT="Je bent een autonome software engineer. Implementeer de
gegeven story op de bestaande codebase. Regels:

1. Je werkt op branch '$BRANCH'. Pusht of merget zelf NOOIT — die stap doe ik
   later vanuit dit script.
2. Commits: gebruik commit-message-format '$STORY_ID: <korte beschrijving>'.
3. Verifieer dat je wijzigingen compileren. Voor backend (Kotlin/Maven):
   draai 'cd newsfeedbackend/newsfeedbackend && mvn -q -DskipTests compile'.
   Bij compile-fail: los op vóór je commit.
4. Update relevante specs in /specs/ als de wijziging de docs raakt.
5. Schrijf één of meer commits — atomair als 't kan.
6. Stop nadat alle wijzigingen lokaal gecommit zijn. Push doet het script.

Story staat in /work/repo/.task.md. Onderaan dat bestand staat een
\`## JIRA-comments\`-sectie met de hele thread: de refiner-samenvatting
met aannames, PO-antwoorden, en eventuele eerdere [DEVELOPER]-comments.
Lees die mee — de aannames van de refiner zijn voor jou de scope-baseline.

EINDIG met een gestructureerde samenvatting die de menselijke
reviewer/tester precies vertelt wat je hebt opgeleverd. Dit gaat
rechtstreeks naar JIRA en de handover-pagina van het dashboard, dus
houd je aan deze drie koppen — exact zo gespeld, op een eigen regel:

Samenvatting:
1-3 regels prose over de aanpak en belangrijkste keuzes.

Gedaan:
- bullet per concreet stuk dat is opgeleverd
- noem affected screens/endpoints/bestanden als 't relevant is
- één regel per bullet, geen sub-bullets

Niet gedaan / aangepast:
- bullet per ding uit de story dat je BEWUST hebt overgeslagen,
  uitgesteld, of anders ingevuld dan letterlijk gevraagd
- inclusief WAAROM (bv. 'niet mogelijk omdat …', 'overlapt met X
  uit refiner-aannames', 'leek out-of-scope')
- als er niets is overgeslagen: schrijf één regel 'Niets — alles uit
  de story (incl. refiner-aannames) is geïmplementeerd.'

Geen markdown-headers (geen \`#\`), geen herhaling van de story-tekst."
    ;;
esac

# Append de gedeelde tip-instructie aan elke role-prompt.
SYSTEM_PROMPT="${SYSTEM_PROMPT}${AGENT_TIPS_PROMPT}"

# Extra context als dit een iteratie op een bestaande PR is (S-09).
# Alleen developer-mode pakt dat op; refiner werkt nooit op PR-iteraties.
if [[ "${AGENT_ROLE:-developer}" == "developer" && -n "$TRIGGER_COMMENT_ID" ]]; then
  SYSTEM_PROMPT+="

LET OP: dit is een iteratie op een al bestaande PR. /work/repo/.task.md
bevat reviewer-feedback in plaats van een nieuwe story. Verwerk elke
opmerking concreet, maar wijzig niets dat niet expliciet gevraagd wordt.
De vorige implementatie staat al op deze branch — bouw daarop voort."
fi

# Effort-niveau via system-prompt-instructie (Fase 2). De Claude CLI
# heeft geen directe --thinking-budget-flag in --print-mode, dus we
# sturen via tekst. Mapping:
#   quick   → kort overwegen, geen extended thinking
#   default → normaal
#   deep    → uitgebreid hardop denken voor je een tool-call kiest
if [[ -n "${CLAUDE_EFFORT:-}" ]]; then
  case "$CLAUDE_EFFORT" in
    quick)
      SYSTEM_PROMPT+="

EFFORT-NIVEAU: quick. Denk kort, voer snel uit. Geen lange overwegingen
vooraf — vertrouw op je eerste oordeel en corrigeer waar nodig."
      ;;
    deep)
      SYSTEM_PROMPT+="

EFFORT-NIVEAU: deep. Denk uitgebreid hardop over de aanpak vóór je
tool-calls maakt. Overweeg alternatieven, edge-cases, en motiveer
non-triviale beslissingen."
      ;;
    *)
      :  # default — geen extra instructie
      ;;
  esac
fi

# Optionele --model flag. Lege string = laat de CLI z'n eigen default
# kiezen (backwards-compat).
CLAUDE_MODEL_FLAGS=()
if [[ -n "${CLAUDE_MODEL:-}" ]]; then
  CLAUDE_MODEL_FLAGS=(--model "$CLAUDE_MODEL")
  echo "[runner] model: $CLAUDE_MODEL · effort: ${CLAUDE_EFFORT:-default}"
fi

# Story-bestand inkopiëren zodat Claude 'm met Read kan lezen.
# Tegelijk in .git/info/exclude zetten — dat is lokale-only gitignore
# die OOK 'git add .task.md' van Claude tegenhoudt. De .gitignore in
# main heeft 't ook, maar dat helpt alleen tegen 'git add -A'.
cp /task/task.md /work/repo/.task.md
echo ".task.md" >> /work/repo/.git/info/exclude

# Niet-interactief: --print + --output-format stream-json. Elke regel is
# één JSON-event (system-init, assistant-text, tool_use, tool_result,
# result). We pipen door een jq-prettyprinter zodat de pod-log leesbaar
# is; de ruwe events blijven op /tmp/claude.log.jsonl voor diepgaander
# debuggen vanuit de pod.
#
# `try fromjson catch null` retourneert null bij een onleesbare regel;
# die vallen dan in de `(raw)`-tak en breken de pipeline niet.
JQ_PRETTY='
def trim($n): if . == null then "" else (tostring) | if length > $n then "\(.[0:$n])…" else . end end;
. as $raw
| (try fromjson catch null) as $e
| if $e == null then "(raw) " + ($raw | trim(200))
  elif $e.type == "system" and $e.subtype == "init" then
    "🛠  init session=\($e.session_id // "?") model=\($e.model // "?")"
  elif $e.type == "assistant" then
    ($e.message.content // []) | map(
      if .type == "text" then ("💬 " + (.text // "" | trim(800)))
      elif .type == "tool_use" then ("→ " + .name + " " + ((.input // {}) | tostring | trim(220)))
      else ("· a." + .type) end
    ) | join("\n")
  elif $e.type == "user" then
    ($e.message.content // []) | map(
      if .type == "tool_result" then
        ("← " + ((.content // "" | if type=="array" then map(.text // "") | join("") else tostring end) | trim(220)))
      else ("· u." + .type) end
    ) | join("\n")
  elif $e.type == "result" then
    "✅ done " + ((($e.duration_ms // 0)/1000) | floor | tostring) + "s in=" + (($e.usage.input_tokens // 0) | tostring) + " out=" + (($e.usage.output_tokens // 0) | tostring)
  else ("· " + ($e.type // "?"))
  end
'

echo "[runner] Claude start..."
set +e
claude \
  "${CLAUDE_MODEL_FLAGS[@]}" \
  --append-system-prompt "$SYSTEM_PROMPT" \
  --permission-mode bypassPermissions \
  --verbose \
  --output-format stream-json \
  --print \
  "Implementeer de story uit .task.md. Volg de regels uit de system prompt." \
  2>&1 \
  | tee /tmp/claude.log.jsonl \
  | jq -rR --unbuffered "$JQ_PRETTY"
# PIPESTATUS[0] = claude zelf; tee + jq's exit-codes negeren we.
CLAUDE_EXIT=${PIPESTATUS[0]}
set -e

echo "[runner] Claude exit=$CLAUDE_EXIT"

# Opruimen vóór we committen
rm -f /work/repo/.task.md

# ─────────────────────────────────────────────────────────────────────
# ROL-SPLITSING: refiner doet géén git-push of PR. Alleen JSON-outcome
# parsen uit /tmp/claude.log.jsonl en JIRA bijwerken.
# ─────────────────────────────────────────────────────────────────────
# Helper: haal de finale assistant-tekst (`result.result`) uit het log.
# Gebruikt door zowel refiner als developer om een JIRA-summary-comment
# te bouwen.
extract_claude_summary() {
  # Pak de LAATSTE result-event uit het Claude-stream-json log en geef
  # z'n `.result` string terug (met al z'n newlines intact).
  #
  # Vorige versie deed `... | tail -1` op de jq-output, maar `tail -1`
  # werkt op output-regels, niet op jq-records. Een multi-line summary
  # werd dan op één regel weggegooid → alleen de slot-JSON-regel bleef
  # over → sed strip die ook → leeg → "(Geen samenvatting)"-fallback.
  #
  # Correcte aanpak: slurp alle events in een array, filter result-
  # events, pak de laatste, return z'n .result string ongebroken.
  jq -r -s \
    '[.[] | select(.type == "result") | (.result // "")] | last // ""' \
    /tmp/claude.log.jsonl 2>/dev/null
}

# Helper: pak het laatste `agent_tips_update`-JSON-blok uit de Claude-
# samenvatting en POST 'm naar /agent-knowledge/update. Best-effort: bij
# parse-faal of HTTP-fout loggen we 't maar gooien de runner niet om.
push_agent_tips() {
  [[ -z "${FACTORY_POLLER_URL:-}" ]] && return 0
  local summary
  summary=$(extract_claude_summary)
  [[ -z "$summary" ]] && return 0
  local role_lc="${AGENT_ROLE:-developer}"
  local payload
  payload=$(printf '%s' "$summary" | python3 - "$role_lc" "${STORY_ID:-}" <<'EOF' 2>/dev/null
import json, re, sys
text = sys.stdin.read()
role, story_key = sys.argv[1], sys.argv[2]

# Zoek alle JSON-objecten die "agent_tips_update" als top-level key bevatten.
# Walk depth-balanced zodat geneste objecten {...} niet voortijdig sluiten.
candidates = []
i = 0
while i < len(text):
    if text[i] == "{":
        depth = 0
        for j in range(i, len(text)):
            if text[j] == "{": depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    blob = text[i:j+1]
                    if '"agent_tips_update"' in blob:
                        try:
                            obj = json.loads(blob)
                            tips = obj.get("agent_tips_update")
                            if isinstance(tips, list):
                                candidates.append(tips)
                        except Exception:
                            pass
                    i = j
                    break
        else:
            break
    i += 1
if not candidates:
    sys.exit(0)
out = {"role": role, "story_key": story_key, "tips": candidates[-1]}
print(json.dumps(out))
EOF
)
  if [[ -z "$payload" ]]; then
    return 0
  fi
  local resp
  resp=$(curl -fsS -m 10 -X POST \
    -H "Content-Type: application/json" \
    -d "$payload" \
    "${FACTORY_POLLER_URL}/agent-knowledge/update" 2>&1 || echo "(curl-fout)")
  echo "[runner] agent-tips push: $resp"
}

# Helper: post een JIRA-comment met [ROLE]-prefix. Eerste arg = rol-label
# (REFINER/DEVELOPER/REVIEWER/TESTER), tweede arg = body-tekst.
post_role_jira_comment() {
  local role_label="$1"
  local body_text="$2"
  if [[ -z "${JIRA_BASE_URL:-}" || -z "${JIRA_EMAIL:-}" || -z "${JIRA_API_KEY:-}" ]]; then
    return 0
  fi
  if [[ -z "$body_text" ]]; then
    return 0
  fi
  local full_text
  full_text=$(printf '[%s] %s' "$role_label" "$body_text")
  local body
  body=$(jq -n --arg t "$full_text" \
    '{body: {type:"doc", version:1, content:[{type:"paragraph", content:[{type:"text", text:$t}]}]}}')
  echo "[runner] JIRA: post [$role_label] summary-comment"
  curl -s -m 10 -o /dev/null -w "  comment HTTP %{http_code}\n" \
    -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
    -H "Content-Type: application/json" \
    -X POST -d "$body" \
    "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/comment"
}


if [[ "${AGENT_ROLE:-developer}" == "refiner" ]]; then
  echo "[runner] role=refiner — parse outcome + post JIRA"

  # Volledige finale assistant-tekst (inclusief motivatie boven de
  # JSON-regel). Gebruikt voor de [REFINER]-summary-comment hieronder.
  REFINER_FULL_TEXT=$(extract_claude_summary)

  # JSON-outcome extraheren met de gedeelde parser. Robuust tegen
  # markdown-fences, smart quotes, trailing commas, comments en
  # onontsnapte quotes binnen string-values. Bij totale faal stderr
  # bewaren zodat de fallback-comment de operator op weg helpt.
  PARSE_ERR_FILE=/tmp/parse-outcome.refiner.err
  OUTCOME_JSON=$(jq -r 'select(.type == "result") | .result // ""' \
    /tmp/claude.log.jsonl 2>/dev/null \
    | /usr/local/bin/parse-outcome.py --role refiner \
        2>"$PARSE_ERR_FILE" || true)

  if [[ -z "$OUTCOME_JSON" ]]; then
    echo "[runner] geen geldig JSON-outcome gevonden — markeer als awaiting-po met fallback-vraag"
    echo "[runner] parser-diagnose:"
    cat "$PARSE_ERR_FILE" 2>/dev/null || true
    # Fallback-vraag bevat een hint van de parser-diagnose zodat de PO
    # ziet WAT er mis ging (te lange output, ontbrekend JSON-blok, etc.)
    # en de prompt eventueel kan bijschaven.
    PARSE_DIAG=$(cat "$PARSE_ERR_FILE" 2>/dev/null | head -c 800 || true)
    FALLBACK_Q="De refinement kon niet automatisch worden afgerond — het model gaf geen herkenbaar JSON-besluit. Probeer de story opnieuw te starten via AI Queued, of maak de beschrijving wat concreter."
    if [[ -n "$PARSE_DIAG" ]]; then
      FALLBACK_Q="${FALLBACK_Q} (Parser-diagnose: ${PARSE_DIAG//\"/\\\"})"
    fi
    OUTCOME_JSON=$(jq -nc --arg q "$FALLBACK_Q" \
      '{phase:"awaiting-po",questions:[$q]}')
  fi

  echo "[runner] outcome: $OUTCOME_JSON"
  REFINER_PHASE=$(echo "$OUTCOME_JSON" | jq -r '.phase // "awaiting-po"')

  # JIRA-update: status + phase + (optioneel) comment met vragen.
  if [[ -n "${JIRA_FIELD_AI_PHASE:-}" && -n "${JIRA_BASE_URL:-}" ]]; then
    case "$REFINER_PHASE" in
      refined)
        TARGET_STATUS="AI Queued"
        # Post een samenvattende comment zodat de PO ziet dat de
        # refiner de story heeft beoordeeld als helder genoeg. Body =
        # de tekst die de refiner zelf produceerde, met de JSON-regel
        # eraf gestript.
        REFINER_PROSE=$(echo "$REFINER_FULL_TEXT" | sed '/^[[:space:]]*{.*"phase".*}[[:space:]]*$/d')
        if [[ -z "$REFINER_PROSE" ]]; then
          REFINER_PROSE="Story is helder genoeg om te starten. Geen vragen."
        fi
        post_role_jira_comment "REFINER" "$REFINER_PROSE"
        ;;
      awaiting-po|*)
        REFINER_PHASE="awaiting-po"
        TARGET_STATUS="AI Needs Info"
        # Post comment met vragen
        QUESTIONS=$(echo "$OUTCOME_JSON" | jq -r '.questions // [] | map("- " + .) | join("\n")')
        if [[ -n "$QUESTIONS" ]]; then
          COMMENT_TEXT=$(printf '[REFINER] Vragen voor je goedkeuring vóór de developer aan de slag kan:\n\n%s\n\nBeantwoord in een nieuwe comment, en zet de status op "AI Queued" zodra je klaar bent. De refiner pakt de story dan automatisch weer op.' "$QUESTIONS")
          COMMENT_JSON=$(jq -n --arg t "$COMMENT_TEXT" '{body: {type:"doc", version:1, content:[{type:"paragraph", content:[{type:"text", text:$t}]}]}}')
          echo "[runner] JIRA: post [REFINER]-comment met $(echo "$OUTCOME_JSON" | jq '.questions | length') vragen"
          curl -s -m 10 -o /dev/null -w "  comment HTTP %{http_code}\n" \
            -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
            -H "Content-Type: application/json" \
            -X POST -d "$COMMENT_JSON" \
            "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/comment"
        fi
        ;;
    esac

    # Phase + (resume_phase als nodig) zetten via PUT
    if [[ "$REFINER_PHASE" == "awaiting-po" && -n "${JIRA_FIELD_AI_RESUME_PHASE:-}" ]]; then
      PATCH_JSON=$(jq -n \
        --arg phase "$REFINER_PHASE" \
        --arg resume "refining" \
        --arg pf "$JIRA_FIELD_AI_PHASE" \
        --arg rf "$JIRA_FIELD_AI_RESUME_PHASE" \
        '{fields: ({} | .[$pf] = $phase | .[$rf] = $resume)}')
    else
      PATCH_JSON=$(jq -n \
        --arg phase "$REFINER_PHASE" \
        --arg pf "$JIRA_FIELD_AI_PHASE" \
        '{fields: ({} | .[$pf] = $phase)}')
    fi
    echo "[runner] JIRA: AI Phase = $REFINER_PHASE"
    curl -s -m 10 -o /dev/null -w "  phase HTTP %{http_code}\n" \
      -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
      -H "Content-Type: application/json" \
      -X PUT -d "$PATCH_JSON" \
      "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}"

    # Status-transition naar de juiste target
    transitions=$(curl -s -m 10 -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
      -H "Accept: application/json" \
      "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/transitions" 2>/dev/null)
    tr_id=$(echo "$transitions" | jq -r --arg name "$TARGET_STATUS" \
      '.transitions[] | select(.to.name == $name) | .id' 2>/dev/null | head -1)
    if [[ -n "$tr_id" ]]; then
      echo "[runner] JIRA: $STORY_ID → '$TARGET_STATUS'"
      curl -s -m 10 -o /dev/null -w "  transition HTTP %{http_code}\n" \
        -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
        -H "Content-Type: application/json" \
        -X POST -d "{\"transition\":{\"id\":\"${tr_id}\"}}" \
        "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/transitions"
    else
      echo "[runner] geen transition naar '$TARGET_STATUS' beschikbaar — manueel verplaatsen"
    fi
  fi

  echo "[runner] refiner klaar."
  push_agent_tips || true
  exit 0
fi

# ─────────────────────────────────────────────────────────────────────
# Reviewer-flow (Fase 4): geen code, geen push — alleen JIRA-comment
# met bevindingen + outcome-JSON.
# ─────────────────────────────────────────────────────────────────────
if [[ "${AGENT_ROLE:-developer}" == "reviewer" ]]; then
  echo "[runner] role=reviewer — parse outcome + post JIRA"

  REVIEWER_FULL_TEXT=$(extract_claude_summary)

  # Outcome-JSON met de gedeelde parser. Zelfde robuustheid als refiner.
  PARSE_ERR_FILE=/tmp/parse-outcome.reviewer.err
  OUTCOME_JSON=$(jq -r 'select(.type == "result") | .result // ""' \
    /tmp/claude.log.jsonl 2>/dev/null \
    | /usr/local/bin/parse-outcome.py --role reviewer \
        2>"$PARSE_ERR_FILE" || true)

  if [[ -z "$OUTCOME_JSON" ]]; then
    echo "[runner] geen geldige reviewer-outcome — default reviewed-changes (veiliger)"
    echo "[runner] parser-diagnose:"
    cat "$PARSE_ERR_FILE" 2>/dev/null || true
    OUTCOME_JSON='{"phase":"reviewed-changes"}'
  fi

  echo "[runner] outcome: $OUTCOME_JSON"
  REVIEWER_PHASE=$(echo "$OUTCOME_JSON" | jq -r '.phase // "reviewed-changes"')
  # Normaliseer: enige toegestane waardes nu zijn reviewed-ok en
  # reviewed-changes. Awaiting-po komt later (spec).
  case "$REVIEWER_PHASE" in
    reviewed-ok|reviewed-changes) ;;
    *)
      echo "[runner] onverwachte phase '$REVIEWER_PHASE' — degradeer naar reviewed-changes"
      REVIEWER_PHASE="reviewed-changes"
      ;;
  esac

  # Strip de slot-JSON-regel zodat we de pure prose als JIRA-comment posten.
  REVIEWER_PROSE=$(echo "$REVIEWER_FULL_TEXT" | sed '/^[[:space:]]*{.*"phase".*}[[:space:]]*$/d')
  if [[ -z "$REVIEWER_PROSE" ]]; then
    REVIEWER_PROSE="(Geen samenvatting beschikbaar — bekijk de log.)"
  fi
  post_role_jira_comment "REVIEWER" "$REVIEWER_PROSE"

  # Phase op JIRA zetten. Géén status-transition: de story blijft op
  # AI IN REVIEW (PR open, wacht op tester of mens).
  if [[ -n "${JIRA_FIELD_AI_PHASE:-}" && -n "${JIRA_BASE_URL:-}" ]]; then
    echo "[runner] JIRA: AI Phase = '$REVIEWER_PHASE'"
    curl -s -m 10 -o /dev/null -w "  phase HTTP %{http_code}\n" \
      -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
      -H "Content-Type: application/json" \
      -X PUT \
      -d "{\"fields\":{\"${JIRA_FIELD_AI_PHASE}\":\"${REVIEWER_PHASE}\"}}" \
      "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}"
  fi

  # Sla 'update_jira_after_run' over — die zou anders status overrulen.
  # Markeer dit explicitiet zodat de developer-end-of-script niet draait.
  echo "[runner] reviewer klaar (phase=$REVIEWER_PHASE)."
  push_agent_tips || true
  exit 0
fi

# ─────────────────────────────────────────────────────────────────────
# Tester-flow (Fase 5 MVP): curl + AI-judgment, geen code, geen push,
# geen infra-mutaties. Eindigt met phase=tested-ok of tested-fail.
# ─────────────────────────────────────────────────────────────────────
if [[ "${AGENT_ROLE:-developer}" == "tester" ]]; then
  echo "[runner] role=tester — parse outcome + post JIRA"

  TESTER_FULL_TEXT=$(extract_claude_summary)

  # Hergebruik dezelfde gedeelde parser als reviewer/refiner.
  PARSE_ERR_FILE=/tmp/parse-outcome.tester.err
  OUTCOME_JSON=$(jq -r 'select(.type == "result") | .result // ""' \
    /tmp/claude.log.jsonl 2>/dev/null \
    | /usr/local/bin/parse-outcome.py --role tester \
        2>"$PARSE_ERR_FILE" || true)

  if [[ -z "$OUTCOME_JSON" ]]; then
    echo "[runner] geen geldige tester-outcome — default tested-fail (veiliger)"
    echo "[runner] parser-diagnose:"
    cat "$PARSE_ERR_FILE" 2>/dev/null || true
    OUTCOME_JSON='{"phase":"tested-fail"}'
  fi

  echo "[runner] outcome: $OUTCOME_JSON"
  TESTER_PHASE=$(echo "$OUTCOME_JSON" | jq -r '.phase // "tested-fail"')
  case "$TESTER_PHASE" in
    tested-ok|tested-fail) ;;
    *)
      echo "[runner] onverwachte phase '$TESTER_PHASE' — degradeer naar tested-fail"
      TESTER_PHASE="tested-fail"
      ;;
  esac

  # Safety-check: tested-ok ZONDER screenshots terwijl preview live was
  # is verdacht (waarschijnlijk gaf de tester code-review als verdict
  # i.p.v. echte browser-test). Override naar tested-fail zodat de
  # developer-loopback de tester forceert om alsnog Playwright te draaien.
  SHOT_COUNT=$(find /tmp/screenshots -maxdepth 1 -name '*.png' 2>/dev/null | wc -l | tr -d ' ')
  if [[ "$TESTER_PHASE" == "tested-ok" && "$SHOT_COUNT" == "0" && "${PREVIEW_READY:-0}" == "1" ]]; then
    echo "[runner] safety-override: tested-ok zonder screenshots terwijl preview live was — degradeer naar tested-fail"
    TESTER_PHASE="tested-fail"
    TESTER_OVERRIDE_NOTE="

⚠ Automatische override door runner: deze run is gemarkeerd als tested-fail
omdat er géén browser-screenshots in /tmp/screenshots/ stonden terwijl de
preview-deploy live was. De tester hoort de UI te verifiëren via Playwright,
niet via code-inspectie. Volgende ronde probeert opnieuw."
  fi

  TESTER_PROSE=$(echo "$TESTER_FULL_TEXT" | sed '/^[[:space:]]*{.*"phase".*}[[:space:]]*$/d')
  if [[ -z "$TESTER_PROSE" ]]; then
    TESTER_PROSE="(Geen samenvatting beschikbaar — bekijk de log.)"
  fi
  if [[ -n "${TESTER_OVERRIDE_NOTE:-}" ]]; then
    TESTER_PROSE="${TESTER_PROSE}${TESTER_OVERRIDE_NOTE}"
  fi

  # Upload screenshots als attachments naar de JIRA-story. Geen
  # show-stopper bij faal — we loggen en gaan door zodat de
  # [TESTER]-comment + phase-update altijd plaatsvinden.
  upload_screenshots() {
    local dir="/tmp/screenshots"
    [[ -d "$dir" ]] || return 0
    local uploaded=()
    shopt -s nullglob
    for png in "$dir"/*.png; do
      [[ -f "$png" ]] || continue
      local fname
      fname=$(basename "$png")
      echo "[runner] upload screenshot: $fname"
      if curl -s -m 30 -o /dev/null -w "  attach HTTP %{http_code}\n" \
           -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
           -H "X-Atlassian-Token: no-check" \
           -F "file=@${png}" \
           "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/attachments"; then
        uploaded+=("$fname")
      fi
    done
    shopt -u nullglob
    if (( ${#uploaded[@]} > 0 )); then
      # Hang de file-namen achter de tester-prose zodat de mens in
      # één blik ziet wat 'r aan attachments hangt.
      TESTER_PROSE="${TESTER_PROSE}

📸 Screenshots toegevoegd aan deze story:
$(printf -- '- %s\n' "${uploaded[@]}")"
      echo "[runner] ${#uploaded[@]} screenshot(s) geüpload."
    fi
  }
  upload_screenshots

  post_role_jira_comment "TESTER" "$TESTER_PROSE"

  # Phase op JIRA. Status blijft AI IN REVIEW.
  if [[ -n "${JIRA_FIELD_AI_PHASE:-}" && -n "${JIRA_BASE_URL:-}" ]]; then
    echo "[runner] JIRA: AI Phase = '$TESTER_PHASE'"
    curl -s -m 10 -o /dev/null -w "  phase HTTP %{http_code}\n" \
      -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
      -H "Content-Type: application/json" \
      -X PUT \
      -d "{\"fields\":{\"${JIRA_FIELD_AI_PHASE}\":\"${TESTER_PHASE}\"}}" \
      "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}"
  fi

  echo "[runner] tester klaar (phase=$TESTER_PHASE)."
  push_agent_tips || true
  exit 0
fi

# ─────────────────────────────────────────────────────────────────────
# Onder dit punt: developer-flow (huidige logica). Refiner, reviewer
# en tester exit'ten al hierboven.
# ─────────────────────────────────────────────────────────────────────

# ---------- verifieer dat er iets is gewijzigd ----------
# Claude moet zelf gecommit hebben volgens regel 5; check of er commits
# zijn t.o.v. base.
NEW_COMMITS=$(git log --oneline "origin/${BASE_BRANCH}..HEAD" 2>/dev/null | wc -l | tr -d ' ')
if [[ "$NEW_COMMITS" == "0" ]]; then
  echo "[runner] geen nieuwe commits — niets te pushen. Exiting met code 2."
  exit 2
fi
echo "[runner] $NEW_COMMITS nieuwe commit(s):"
git log --oneline "origin/${BASE_BRANCH}..HEAD"

# ---------- valideer commit-msg-conventie ----------
# Zie specs/branch-commit-convention.md: commits op ai/<id> moeten
# beginnen met "<id>: ". Voorkomt dat we naar GitHub pushen waar de
# CI 'm toch zou afkeuren.
echo "[runner] check commit-message-conventie"
bad=0
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  sha="${line%% *}"
  msg="${line#* }"
  if ! [[ "$msg" =~ ^${STORY_ID}:\ .+ ]]; then
    echo "[runner]   FAIL commit $sha: '$msg'"
    bad=1
  fi
done < <(git log --format='%H %s' "origin/${BASE_BRANCH}..HEAD")
if [[ $bad -ne 0 ]]; then
  echo "[runner] commit-message-conventie geschonden — niet pushen."
  echo "[runner] Verwacht format: '${STORY_ID}: <beschrijving>'"
  exit 3
fi
echo "[runner] commit-messages OK"

# ---------- push ----------
echo "[runner] push naar origin/$BRANCH"
git push -u origin "$BRANCH"

# S-09: meteen na een succesvolle push reageren we 🚀 op de trigger-comment
# (no-op buiten comment-mode). Daarna kan validate-pr nog falen, maar de
# 'gepushed'-status is sowieso correct — de gebruiker ziet de CI-status
# zelf in GitHub.
react_to_trigger "rocket"

# ---------- PR open of update ----------
# Bestaat 'er al een PR voor deze branch? Dan slaan we creëren over.
if gh pr view "$BRANCH" --json number >/dev/null 2>&1; then
  echo "[runner] PR bestaat al; nieuwe commits zijn gepusht."
  gh pr view "$BRANCH" --json url --jq .url
else
  echo "[runner] open nieuwe PR"
  PR_BODY=$(printf '## Story\n\n%s\n\n---\n\n_Geopend door claude-runner._\n' "$(cat /task/task.md)")
  gh pr create \
    --base "$BASE_BRANCH" \
    --head "$BRANCH" \
    --title "$STORY_ID: $(head -1 /task/task.md | sed 's/^# //;s/^[0-9]*\. //')" \
    --body "$PR_BODY"
fi

# ---------- wait_for_validate_pr (Fase 1 selfheal) ----------
# Wacht max 5 min op de `validate-pr` CI-check. Bij fail: 1× fix-poging
# door Claude met de error-output als context.
PR_NUMBER_FOR_CI=$(gh pr view "$BRANCH" --json number --jq .number 2>/dev/null || echo "")

wait_validate() {
  local pr_num="$1"
  local timeout=300       # 5 min
  local elapsed=0
  while (( elapsed < timeout )); do
    # `gh pr checks` toont alle historische runs voor deze PR — we pakken
    # de LAATSTE (chronologisch onderaan) want oudere fails uit eerdere
    # close+reopen cycli zijn niet relevant.
    local row
    row=$(gh pr checks "$pr_num" 2>/dev/null | grep -E "^validate(\s|$)" | tail -1)
    if [[ -n "$row" ]]; then
      if echo "$row" | grep -qiE "\bpass\b"; then return 0; fi
      if echo "$row" | grep -qiE "\bfail\b"; then return 1; fi
    fi
    sleep 10
    elapsed=$((elapsed + 10))
  done
  echo "[runner] validate-pr timeout na ${timeout}s — geef het op"
  return 2
}

fetch_validate_log() {
  # Laatste run-id voor deze branch op validate-pr workflow
  local run_id
  run_id=$(gh run list --workflow=validate-pr.yml --limit 10 \
    --json databaseId,headBranch --jq ".[] | select(.headBranch == \"$BRANCH\") | .databaseId" \
    | head -1)
  if [[ -n "$run_id" ]]; then
    gh run view "$run_id" --log-failed 2>&1 | tail -40
  fi
}

attempt_fix() {
  local err="$1"
  echo "[runner] validate-pr faalt — Claude probeert te fixen (max 1 retry)"

  # Schrijf de fout in een fix-task.md die Claude kan lezen
  cat > /work/repo/.fix-task.md <<EOF
# Fix: CI-check 'validate-pr' is gefaald

Je vorige commits hebben de validate-pr CI-check laten falen.
Hier is de relevante log-output:

\`\`\`
$err
\`\`\`

Maak één commit op deze branch ($BRANCH) die het probleem oplost.
Commit message format: $STORY_ID: <korte beschrijving van de fix>.
EOF

  set +e
  claude \
    "${CLAUDE_MODEL_FLAGS[@]}" \
    --append-system-prompt "$SYSTEM_PROMPT" \
    --permission-mode bypassPermissions \
    --verbose \
    --output-format stream-json \
    --print \
    "Fix de CI-fout zoals beschreven in /work/repo/.fix-task.md." \
    2>&1 \
    | tee -a /tmp/claude.log.jsonl \
    | jq -rR --unbuffered "$JQ_PRETTY"
  set -e
  rm -f /work/repo/.fix-task.md

  # Verifieer dat er een nieuwe commit is + push
  local before after
  before=$(git rev-parse HEAD)
  after=$(git rev-parse HEAD)   # zelfde — als Claude commit, wijzigt HEAD
  # Re-check: count commits since base
  local new_total
  new_total=$(git log --oneline "origin/${BASE_BRANCH}..HEAD" 2>/dev/null | wc -l | tr -d ' ')
  if (( new_total <= NEW_COMMITS )); then
    echo "[runner] Claude maakte geen extra commit voor de fix — geef op."
    return 1
  fi
  echo "[runner] Claude maakte extra commit(s); push + wacht opnieuw"
  git push origin "$BRANCH"
  return 0
}

if [[ -n "$PR_NUMBER_FOR_CI" ]]; then
  echo "[runner] wacht op validate-pr (max 5 min)…"
  if wait_validate "$PR_NUMBER_FOR_CI"; then
    echo "[runner] validate-pr: OK ✓"
  else
    rc=$?
    if (( rc == 1 )); then
      ERR_LOG=$(fetch_validate_log)
      if attempt_fix "$ERR_LOG"; then
        echo "[runner] wacht na fix-attempt op validate-pr (max 5 min)…"
        if wait_validate "$PR_NUMBER_FOR_CI"; then
          echo "[runner] validate-pr: OK na fix ✓"
        else
          echo "[runner] validate-pr blijft falen — handmatig nodig."
        fi
      fi
    fi
  fi
fi

# ---------- preview-URL als PR-comment ----------
# Zodra de ApplicationSet de PR detecteert, spawnt 'ie een preview op
# https://pnf-pr-<num>.vdzonsoftware.nl. We posten 'm direct
# als PR-comment zodat de URL meteen klikbaar is.
PR_NUMBER=$(gh pr view "$BRANCH" --json number --jq .number 2>/dev/null || echo "")
PR_URL=""
PREVIEW_URL=""
if [[ -n "$PR_NUMBER" ]]; then
  PR_URL=$(gh pr view "$PR_NUMBER" --json url --jq .url 2>/dev/null || echo "")
  PREVIEW_URL="https://pnf-pr-${PR_NUMBER}.vdzonsoftware.nl"
  echo "[runner] preview-URL: $PREVIEW_URL"
  # Alleen comment posten als 'm er niet al staat (idempotent)
  if ! gh pr view "$PR_NUMBER" --json comments --jq '.comments[].body' 2>/dev/null | grep -q "$PREVIEW_URL"; then
    # Link naar JIRA-thread (waar de [DEVELOPER]-summary-comment staat)
    JIRA_LINK=""
    if [[ -n "${JIRA_BASE_URL:-}" && "$STORY_ID" =~ ^[A-Z][A-Z0-9]+-[0-9]+$ ]]; then
      JIRA_LINK=$(printf '\n\n📋 [%s in JIRA](%s/browse/%s) — daar staat de samenvatting van de agent en het lopende verhaal.' "$STORY_ID" "$JIRA_BASE_URL" "$STORY_ID")
    fi
    COMMENT_BODY=$(printf '🚀 **[DEVELOPER] Preview-deploy** — spint over ~2 min op:\n\n%s\n\n_Klik na de deploy om de branch live te bekijken. Bij merge wordt de preview automatisch opgeruimd._%s' "$PREVIEW_URL" "$JIRA_LINK")
    gh pr comment "$PR_NUMBER" --body "$COMMENT_BODY" 2>&1 || echo "[runner] (PR-comment kon niet geplaatst worden; niet kritiek)"
  fi
fi

# ---------- JIRA transition + comments (S-07) ----------
# Alleen actief als JIRA-env-vars zijn gezet (poller injecteert ze) én
# de story-id het JIRA-format heeft (b.v. KAN-42). Manual runs zonder
# JIRA blijven werken zoals voorheen.
jira_update() {
  if [[ -z "${JIRA_BASE_URL:-}" || -z "${JIRA_EMAIL:-}" || -z "${JIRA_API_KEY:-}" ]]; then
    echo "[runner] (JIRA env-vars ontbreken — skip JIRA-update)"
    return 0
  fi
  if ! [[ "$STORY_ID" =~ ^[A-Z][A-Z0-9]+-[0-9]+$ ]]; then
    echo "[runner] (STORY_ID '$STORY_ID' geen JIRA-format — skip JIRA-update)"
    return 0
  fi
  local target_status="${JIRA_REVIEW_STATUS:-AI IN REVIEW}"

  # Helper: zoek transition-id naar een specifieke status-naam
  local transitions tr_id
  transitions=$(curl -s -m 10 -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
    -H "Accept: application/json" \
    "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/transitions" 2>/dev/null)
  tr_id=$(echo "$transitions" | jq -r --arg name "$target_status" \
    '.transitions[] | select(.to.name == $name) | .id' 2>/dev/null | head -1)

  if [[ -z "$tr_id" ]]; then
    echo "[runner] geen transition naar '$target_status' beschikbaar voor $STORY_ID — skip"
    return 0
  fi

  # Transition uitvoeren
  echo "[runner] JIRA: $STORY_ID → '$target_status'"
  curl -s -m 10 -o /dev/null -w "  transition HTTP %{http_code}\n" \
    -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
    -H "Content-Type: application/json" \
    -X POST \
    -d "{\"transition\":{\"id\":\"${tr_id}\"}}" \
    "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/transitions"

  # AI Phase op JIRA naar de "completed"-waarde voor onze rol. Dashboard
  # gebruikt dit voor de pipeline-balk. Field-ID komt van de poller in
  # env-var JIRA_FIELD_AI_PHASE; lege string betekent veld niet gevonden
  # — dan slaan we 't over.
  if [[ -n "${JIRA_FIELD_AI_PHASE:-}" ]]; then
    local done_phase
    case "${AGENT_ROLE:-developer}" in
      refiner)   done_phase="refined"        ;;
      developer) done_phase="developed"      ;;
      reviewer)  done_phase="reviewed-ok"    ;;  # tester-trigger is reviewed-changes vs ok
      tester)    done_phase="tested-ok"      ;;
      *)         done_phase=""               ;;
    esac
    if [[ -n "$done_phase" ]]; then
      echo "[runner] JIRA: AI Phase = '$done_phase'"
      curl -s -m 10 -o /dev/null -w "  phase HTTP %{http_code}\n" \
        -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
        -H "Content-Type: application/json" \
        -X PUT \
        -d "{\"fields\":{\"${JIRA_FIELD_AI_PHASE}\":\"${done_phase}\"}}" \
        "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}"
    fi
  fi

  # Comment posten met PR-link + preview-URL. ADF-format vereist in v3.
  # Idempotent: check eerst of er al een comment met de PR-URL is.
  local existing
  existing=$(curl -s -m 10 -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
    -H "Accept: application/json" \
    "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/comment?maxResults=100" 2>/dev/null)
  if echo "$existing" | grep -q "$PR_URL"; then
    echo "[runner] JIRA-comment bestaat al; skip"
    return 0
  fi

  # Resultaten = lijst commit-messages (= door Claude geschreven samenvattingen)
  # plus de wijzigde files. Compact en informatief.
  local commits_json files_json
  commits_json=$(git log --format='%s' "origin/${BASE_BRANCH}..HEAD" 2>/dev/null \
    | jq -R . | jq -s . 2>/dev/null || echo '[]')
  files_json=$(git diff --name-only "origin/${BASE_BRANCH}..HEAD" 2>/dev/null \
    | jq -R . | jq -s . 2>/dev/null || echo '[]')

  # Claude's eigen samenvatting van wat 'ie gedaan heeft — onderdeel
  # van de JIRA-comment zodat de PO niet door commit-messages hoeft
  # te scannen om te zien wat er is gebeurd.
  local claude_summary
  claude_summary=$(extract_claude_summary)

  local comment_json
  comment_json=$(jq -n \
    --arg pr "$PR_URL" \
    --arg preview "$PREVIEW_URL" \
    --arg summary "$claude_summary" \
    --argjson commits "$commits_json" \
    --argjson files "$files_json" \
    '
    def link($text; $href):
      { type: "text", text: $text, marks: [{ type: "link", attrs: { href: $href }}] };
    def txt($s): { type: "text", text: $s };
    def par(ns): { type: "paragraph", content: ns };
    def bullet(items): { type: "bulletList", content: (items | map({ type: "listItem", content: [ par([ txt(.) ]) ] })) };

    ([
      par([ txt("[DEVELOPER] Claude heeft de story uitgewerkt.") ]),
      par([ txt("Pull request: "), link($pr; $pr) ]),
      par([ txt("Test-pagina (preview-deploy, klaar na ~2 min): "), link($preview; $preview) ])
    ] +
    (if ($summary | length) > 0
      then [ par([ txt("Samenvatting:") ]), par([ txt($summary) ]) ]
      else [] end) +
    (if ($commits | length) > 0
      then [ par([ txt("Wijzigingen:") ]), bullet($commits) ]
      else [] end) +
    (if ($files | length) > 0
      then [ par([ txt("Bestanden gewijzigd: \($files | length)") ]), bullet($files) ]
      else [] end) +
    [
      par([ txt("Reviewen en mergen via GitHub. Bij merge gaat de story automatisch naar Klaar.") ])
    ]) as $blocks
    |
    {
      body: {
        type: "doc",
        version: 1,
        content: $blocks
      }
    }')
  curl -s -m 10 -o /dev/null -w "  comment HTTP %{http_code}\n" \
    -u "${JIRA_EMAIL}:${JIRA_API_KEY}" \
    -H "Content-Type: application/json" \
    -X POST \
    -d "$comment_json" \
    "${JIRA_BASE_URL}/rest/api/3/issue/${STORY_ID}/comment"
}

jira_update || echo "[runner] (JIRA-update faalde — niet kritiek, PR werkt)"
push_agent_tips || true

echo "[runner] klaar."
