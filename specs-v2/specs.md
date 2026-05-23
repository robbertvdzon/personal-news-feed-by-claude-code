# Software Factory — Specs

## 1. Doel

De **software factory** is een autonome pijplijn die Jira-stories door een keten van AI-agents loodst: van refinen → ontwikkelen → reviewen → testen. Een ticket dat in Jira de status **AI** krijgt, wordt door de factory opgepakt en doorloopt automatisch alle fases tot het ticket succesvol getest is (of vastloopt op een vraag voor de gebruiker).

## 2. Architectuur op hoofdlijnen

- **Taal/stack:** Kotlin, JDK 21, Spring Boot, maven multi-module, spring modilith.
- **Jira-detectie:** polling (geen webhooks). De orchestrator polt elke **15 seconden** alle tickets met status `AI`.
- **Agent-runtime:** elke agent draait als losse **Docker container** die wordt gestart, zijn taak uitvoert (ticket bijwerken), en weer stopt. Eén Docker image, verschillende entrypoints/prompts per agent-type.
- **Isolatie per ticket:** elke agent-run werkt op een eigen git-clone van de target-repo, zodat parallelle tickets elkaar niet raken.
- **AI-aanroep:** agents praten met een AI-model via een CLI tool (later in te vullen — placeholder in de eerste iteratie).

## 3. Jira-integratie

### Status en Phase

- **Status** (standaard Jira-veld): de factory pakt alleen tickets met status `AI` op. Wanneer de hele keten succesvol is doorlopen wordt de status door de orchestrator op iets als `Done` gezet (definitieve waarde nog te bepalen).
- **Phase** (custom field): de fijnmazige state machine binnen de factory. Wordt geschreven door agents (na hun werk) en door de orchestrator (bij overgangen).
- **AgentStartedAt** (custom field, timestamp): wordt gezet door de orchestrator op het moment dat een agent wordt gestart. Hiermee kan een hangende agent gedetecteerd worden (zie §6).
- **AgentType** (custom field, optioneel): welke agent op dit moment draait — handig voor monitoring/UI.

### Communicatie

- Vragen van de refiner aan de gebruiker, en feedback van reviewer/tester aan de developer, worden als **Jira-comments** op het ticket gezet. De volgende agent in de keten leest die comments als context.

## 5. Phase state machine

Mogelijke waardes voor het Phase-veld:

| Phase                                  | Betekenis                                                        | Door wie gezet              |
|----------------------------------------|------------------------------------------------------------------|-----------------------------|
| *(leeg)*                               | Initiële status; ticket is net op `AI` gezet.                    | gebruiker                   |
| `refining`                             | Refiner-agent draait.                                            | orchestrator (bij start)    |
| `refined-with-questions-for-user`      | Refiner heeft vragen → wacht op gebruiker.                       | refiner                     |
| `questions-answered`                   | Gebruiker heeft vragen beantwoord (in comment); start refiner opnieuw. | gebruiker            |
| `refined-finished`                     | Refinement klaar, klaar om te ontwikkelen.                       | refiner                     |
| `developing`                           | Developer-agent draait.                                          | orchestrator (bij start)    |
| `developed`                            | Developer is klaar, code gereed voor review.                     | developer                   |
| `reviewing`                            | Reviewer-agent draait.                                           | orchestrator (bij start)    |
| `reviewed-with-feedback-for-developer` | Reviewer heeft feedback → terug naar developer.                  | reviewer                    |
| `review-finished`                      | Review goedgekeurd, klaar voor test.                             | reviewer                    |
| `testing`                              | Tester-agent draait.                                             | orchestrator (bij start)    |
| `tested-with-feedback-for-developer`   | Test gefaald → terug naar developer.                             | tester                      |
| `tested-succesfully`                   | Eindstatus: alles klaar.                                         | tester                      |

### Overgangen (door de orchestrator)

```
(leeg)                              → start refiner    → refining
refined-with-questions-for-user     → (wachten op gebruiker)
questions-answered                  → start refiner    → refining
refined-finished                    → start developer  → developing
developed                           → start reviewer   → reviewing
reviewed-with-feedback-for-developer → start developer → developing   (loop-back)
review-finished                     → start tester     → testing
tested-with-feedback-for-developer  → start developer  → developing   (loop-back)
tested-succesfully                  → Jira-status → Done; klaar
```

De agents zetten zelf de "klare" phase (`developed`, `review-finished`, etc.). De orchestrator detecteert die bij de volgende poll en zet de phase op de bijbehorende `*ing`-waarde plus start de volgende agent.

## 6. Orchestrator

- **Poll-interval:** 15 seconden.
- **Per cyclus:** haal alle tickets op met Jira-status `AI`. Voor elk ticket:
  1. Bepaal aan de hand van Phase wat de volgende actie is (zie tabel hierboven).
  2. Als een agent gestart moet worden: zet Phase op de actieve waarde (`refining`, `developing`, …), zet `AgentStartedAt` op nu, en start de bijbehorende Docker-container.
  3. Als Phase een `*ing`-waarde is (er draait al een agent): controleer `AgentStartedAt`. Overschrijdt die een drempel (bv. **30 min**, configureerbaar), dan wordt de agent als vastgelopen beschouwd → log/markeer (eerste iteratie: log + skip; later: optioneel kill + retry).
  4. Als Phase een wacht-op-gebruiker waarde is (`refined-with-questions-for-user`): niets doen.
  5. Als Phase `tested-succesfully` is: Jira-status afronden.

- **Concurrency:** in de eerste versie één agent tegelijk per ticket. Meerdere tickets parallel mag wel.

## 7. Agents

Alle agents zijn opgebouwd uit dezelfde Kotlin CLI (`agent`-module), draaien in dezelfde Docker image, en delen:
- Lezen het ticket (inclusief comments) uit Jira via `jira-client`.
- Hebben toegang tot de tips-database (lezen + schrijven).
- Hebben toegang tot een AI-model via een CLI tool (placeholder; concrete tool volgt later).
- Werken aan een eigen git-clone van de target-repo.
- Schrijven hun resultaat terug naar Jira: nieuwe Phase + eventuele comment.
- Exit-code 0 = succes, non-zero = fout (orchestrator logt dit; de Phase blijft staan zoals de agent hem heeft achtergelaten of op `*ing` als hij niet eens zo ver kwam).

### Refiner
- Input: ruwe story.
- Output: opgeschoonde story (acceptance criteria, scope) → Phase `refined-finished`,
  of openstaande vragen als comment → Phase `refined-with-questions-for-user`.

### Developer
- Input: refined story + eventuele review-/test-feedback uit comments.
- Output: code-wijzigingen committed in een branch → Phase `developed`.

### Reviewer
- Input: de branch van de developer.
- Output: review-feedback als comment → Phase `reviewed-with-feedback-for-developer`,
  of goedkeuring → Phase `review-finished`.

### Tester
- Input: de branch van de developer.
- Output: test-resultaten. Bij fouten: comment met logs → Phase `tested-with-feedback-for-developer`. Bij succes → Phase `tested-succesfully`.

## 8. Tips-database

Doel: agents bewaren herbruikbare kennis. Bv. de tester ontdekt hoe je in een specifieke applicatie inlogt — die kennis slaat hij op zodat hij dat de volgende keer niet opnieuw hoeft uit te zoeken.

- **Structuur:** één tabel per agent-type (`tips_refiner`, `tips_developer`, `tips_reviewer`, `tips_tester`).
- **Velden per record (concept):** `id`, `created_at`, `updated_at`, `title`, `body` (markdown), `tags` (string array of comma-separated), `source_ticket_key` (optioneel: het ticket waar de tip uit voortkwam).
- **Toegang:** elke agent leest/schrijft alleen zijn eigen tabel. Indien later cross-agent inzicht nodig is, kan dat als view of expliciete API toegevoegd worden.
- **Tech-keuze:** nog open — voorstel PostgreSQL (past bij Spring Boot/JPA), maar SQLite is ook voldoende voor de eerste iteratie. Definitief vastleggen bij projectopzet.

## 9. Docker-runner

- Start de agent-image met env-vars/args: `TICKET_KEY`, `AGENT_TYPE`, Jira-credentials, DB-connectie, pad naar AI CLI tool.
- Mountpoints: workspace voor de git-clone, eventueel een gedeelde cache.
- Wacht op exit van de container en geeft exit-code door aan de orchestrator.
- Eerste iteratie: serieel uitvoeren is prima; later kan parallelle uitvoering met een pool.

## 10. AI CLI tool

Placeholder in deze versie. De agent roept een externe CLI aan voor LLM-calls (bv. iets als `claude`, `aider`, of een eigen wrapper). Concrete keuze + interface (stdin/stdout JSON? command-line prompts?) wordt in een volgende iteratie vastgelegd. Tot die tijd: de agent-code definieert een interface `AiClient` met één implementatie die een externe binary aanroept.

## 11. Open punten / later te beslissen

- DB-keuze voor tips (PostgreSQL vs SQLite).
- Exacte Jira custom-field IDs en eindstatus na `tested-succesfully` (`Done`? `Closed`?).
- Drempelwaarde voor "agent is vastgelopen" (default-voorstel: 60 min) + gedrag (alleen loggen vs kill + retry vs comment naar gebruiker).
- Concrete AI CLI tool en het contract daarvan.
- Authenticatie/secrets management voor Jira en AI-tool richting de Docker containers.
- Strategie voor parallelle tickets: max aantal gelijktijdige agent-containers.
- Wat gebeurt er als een agent crasht voordat hij Phase bijwerkt (Phase blijft `*ing` → wordt na timeout als vastgelopen gemarkeerd).
- Retry-beleid op de loop-back (review/test → developer): nu ongelimiteerd; later eventueel max N pogingen.
