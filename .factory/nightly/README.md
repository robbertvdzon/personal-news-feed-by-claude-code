# Nachtelijke jobs (`.factory/nightly/`)

Elke submap hier is één **nachtelijke job**: een autonome verbetertaak die de Software Factory
's nachts oppakt en als *silent* story verwerkt — zonder interactie, en bij echte onduidelijkheid
gaat de story in error i.p.v. te wachten op een mens.

## Structuur

```
.factory/nightly/<job-naam>/
  job.yaml         # metadata (titel, aan/uit, AI-instellingen)
  story.md         # de story-beschrijving die de agent uitvoert
  subtasks.yaml    # geordende lijst subtaken (uitvoervolgorde)
  <titel>.md       # één beschrijving per AI-subtaak, bestandsnaam == title
```

## job.yaml

| veld        | verplicht | uitleg |
|-------------|-----------|--------|
| `title`     | ja        | titel van de aangemaakte story |
| `enabled`   | ja        | `false` = job overslaan zonder hem te verwijderen |
| `silent`    | ja        | altijd `true` voor nachtelijke jobs (autonoom; vragen → error) |
| `aiSupplier`| nee       | bv. `claude`; anders de default van de factory |
| `aiModel`   | nee       | specifiek model |
| `priority`  | nee       | voor latere volgorde-bepaling (nu nog niet gebruikt) |

De **repo** wordt hier niet gezet: die volgt uit de repo waarin deze map staat.
Het **ritme** (nachtelijk) volgt uit deze `nightly/`-map.

## subtasks.yaml

`subtasks.yaml` bepaalt uit welke subtaken de story bestaat en in welke volgorde ze draaien;
daarmee slaat de factory de refine- en plan-fase over. Het bestand is **volledig leidend**:
precies de opgesomde subtaken, niet meer en niet minder.

Het bevat een top-level `subtasks:`-lijst; elke entry heeft een `type` en een `title`. De
volgorde in het bestand is de uitvoervolgorde. De standaardketen per job is:

```
development → review → test → summary → documentation → merge → deploy
```

Geldige `type`-waarden: `development`, `review`, `test`, `summary`, `documentation`, `merge`,
`deploy`, `manual-approve`. Het projectgedrag (bv. `merge` = handmatig, `deploy` =
openshift-watch) komt uit `projects.yaml` in de software-factory-repo en wordt hier niet herhaald.

## Subtaak-beschrijvingen (`<titel>.md`)

Voor elke AI-subtaak (`development`, `review`, `test`, `summary`, `documentation`) staat in de
jobmap een beschrijvingsbestand waarvan de bestandsnaam **exact** gelijk is aan de `title` uit
`subtasks.yaml` (bv. titel `Werk documentatie bij` → bestand `Werk documentatie bij.md`). De
`development`-`.md` draagt de inhoud van `story.md`; de overige beschrijven de generieke stap.
Subtaken van type `merge` en `deploy` krijgen **geen** `.md`.

## Regel voor álle nachtelijke jobs

Functioneel niets veranderen. Zolang alle tests slagen mag de job autonoom afgerond worden;
faalt iets, of is er een echte inhoudelijke vraag, dan gaat de story in error.
