# End-to-end tests

E2E-scenario's, procedure en testrun-administratie staan in **[`/e2e/readme.md`](../e2e/readme.md)**.

Korte samenvatting:

| Map | Inhoud | In git? |
|---|---|---|
| [`/e2e/scenarios/`](../e2e/scenarios/) | Vrije-tekst markdown-scripts: `start-scenario.md`, `cleanup-scenario.md`, `rss-scenario.md`, `feed-scenario.md`, `samenvatting-scenario.md` | ✅ ja |
| `/e2e/testruns/history/<datetime>/` | Per run: kopieën van de gedraaide scripts + `report.md` + `recording.gif` | ❌ gitignored |
| `/e2e/testruns/latest` | Symlink naar de laatste history-folder | ❌ gitignored |

Een testrun begint altijd met `start-scenario` (verse user, RSS-feed, categorieën, RSS-pipeline triggeren) en eindigt met `cleanup-scenario` (user wissen). Daartussenin draaien één of meer scenario-scripts. De runner neemt de hele sessie op als GIF en schrijft een `report.md` met een tabel van scenario, resultaat, en start/eind-positie in de video.

Zie [`/e2e/readme.md`](../e2e/readme.md) voor de exacte procedure.
