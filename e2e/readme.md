# E2E-tests met browser-opname

End-to-end scenario's die handmatig (of door een agent) worden afgespeeld in de Flutter web-app, met een **GIF-opname** van de browser zodat je het resultaat later terug kunt kijken. Geen test-framework — gewoon menselijk leesbare scripts en een procedurele runner.

## Mappenstructuur

```
e2e/
├── readme.md                       ← dit bestand
├── scenarios/                      ← gecheckt in; vrije tekst, in NL
│   ├── start-scenario.md           ← altijd eerst (verse user, feed, categorieën)
│   ├── cleanup-scenario.md         ← altijd laatst (user wissen)
│   ├── rss-scenario.md
│   ├── feed-scenario.md
│   └── samenvatting-scenario.md
└── testruns/                       ← gitignored, lokaal per machine
    ├── latest -> history/<datetime> (symlink naar laatste run)
    └── history/
        └── 2026-05-09T14-32-05/    ← één map per run, ISO-8601 zonder kolons
            ├── start-scenario.md   ← kopieën van de scripts die zijn afgespeeld
            ├── rss-scenario.md
            ├── feed-scenario.md
            ├── samenvatting-scenario.md
            ├── cleanup-scenario.md
            ├── report.md           ← samenvattingstabel
            └── recording.gif       ← opname van de hele run
```

## Een testrun draaien

### Vooraf
1. **Backend draait** op `http://localhost:8080` met `PNF_ANTHROPIC_API_KEY` gezet (anders falen scenario's die echte AI-output verwachten):
   ```bash
   cd newsfeedbackend/newsfeedbackend
   PNF_ANTHROPIC_API_KEY=sk-ant-… mvn -DskipTests package
   PNF_ANTHROPIC_API_KEY=sk-ant-… java -jar target/newsfeedbackend-1.0.0.jar &
   ```
2. **Flutter web draait** op `http://localhost:3000`:
   ```bash
   cd frontend
   make serve-ext
   ```
3. **Chrome-browser** klaar (bij voorkeur ~1024×768 voor leesbaarheid).

### Procedure
1. Maak de runmap:
   ```bash
   STAMP=$(date -u +"%Y-%m-%dT%H-%M-%S")
   RUN=e2e/testruns/history/$STAMP
   mkdir -p "$RUN"
   ```
2. **Start GIF-opname** (in agent-context: `gif_creator action=start_recording`).
3. Voer de scenario's in deze volgorde uit:
   1. `start-scenario.md`
   2. één of meer scenario's (`rss-scenario.md`, `feed-scenario.md`, `samenvatting-scenario.md`)
   3. `cleanup-scenario.md`
4. **Kopieer** elke .md die je gedraaid hebt naar `$RUN/`.
5. **Stop opname** en exporteer als `$RUN/recording.gif`.
6. **Schrijf** `$RUN/report.md` met een tabel (zie hieronder) — start- en eindtijden zijn de minuut:seconde-positie in de GIF, niet wandklok-tijden.
7. **Update `latest`-symlink:**
   ```bash
   ln -sfn "history/$STAMP" e2e/testruns/latest
   ```

### `report.md` template

```markdown
# E2E testrun — 2026-05-09 14:32 UTC

| # | Scenario | Resultaat | Start in video | Eind in video | Notities |
|---|---|---|---|---|---|
| 1 | start-scenario | ✅ Pass | 00:00 | 01:25 | 7 RSS-items binnengekomen |
| 2 | rss-scenario | ✅ Pass | 01:26 | 02:10 | filter + ongelezen-toggle werken |
| 3 | feed-scenario | ⚠️ Partial | 02:11 | 02:55 | 0 items in feed (Claude rejecteerde alles) |
| 4 | samenvatting-scenario | ✅ Pass | 02:56 | 03:40 | summary aangemaakt, ~800 woorden |
| 5 | cleanup-scenario | ✅ Pass | 03:41 | 03:55 | user verwijderd uit users.json |

## Bevindingen

- …

## Volgende stappen

- …
```

Resultaat-iconen: ✅ Pass, ❌ Fail, ⚠️ Partial (deels werkend), ⏭ Skipped.

## Scenario-conventies

Elke `*.md` in `scenarios/` is **menselijk leesbaar** en bestaat uit:

```markdown
# Scenario: <naam>

## Doel
Eén-zin samenvatting van wat we testen.

## Voorwaarden
- … (welke andere scenario's eerst moeten draaien, wat in de state moet staan)

## Stappen
1. …
2. …

## Verwacht resultaat
- Concrete checks die de runner verifieert (UI-tekst, item-aantallen, …).
```

De runner volgt de stappen letterlijk en noteert per scenario pass/fail in `report.md`.
