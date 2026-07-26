# Audits (`.factory/nightly/`)

Elke submap hier is één **audit**: een read-only AI-agent-run die de Software Factory elke ochtend
om 08:00 oppakt. Een audit past **nooit zelf code aan** — hij onderzoekt, schrijft een rapport, en
stelt hoogstens 1 kleine, afgebakende vervolg-story voor om het belangrijkste gevonden probleem op
te lossen. Die vervolg-story is een gewone (niet-silent) story: vragen zijn toegestaan, goedkeuring
is automatisch, en hij start in de wachtrij (`start-next`) i.p.v. meteen.

Per project draait **hoogstens 1 audit per nacht**: de scheduler kiest per project de enabled audit
met de oudste laatste-rapport-timestamp (nooit gedraaid = oudste), zodat alle geconfigureerde
audits om beurten aan bod komen.

## Structuur

```
.factory/nightly/<audit-naam>/
  job.yaml         # metadata (titel, aan/uit, AI-instellingen)
  prompt.md         # de vaste audit-instructie die de agent uitvoert
```

## job.yaml

| veld        | verplicht | uitleg |
|-------------|-----------|--------|
| `title`     | ja        | titel van de audit (gebruikt in het dashboard/rapport) |
| `enabled`   | ja        | `false` = audit overslaan zonder hem te verwijderen |
| `aiSupplier`| nee       | bv. `claude`; anders de default van de factory |
| `aiModel`   | nee       | specifiek model |
| `priority`  | nee       | voor latere volgorde-bepaling (nu nog niet gebruikt) |

De **repo** wordt hier niet gezet: die volgt uit de repo waarin deze map staat.

## prompt.md

De vaste instructie voor de auditor-agent: wat te onderzoeken. De agent krijgt er automatisch bij:
de laatste eerdere rapporten voor deze audit (historische context, incl. score-trend indien
aanwezig) en zijn eigen memory-tips van vorige keren (via het knowledge-domein van de
software-factory — rol `auditor`, category = audit-naam).

De agent sluit af met een JSON-besluit: `{"phase":"audited"}`, optioneel aangevuld met `score`,
`scoreLabel` en/of `proposedStory` (titel + beschrijving, hoogstens 1 per run).

## Regel voor álle audits

Functioneel niets veranderen — een audit **wijzigt geen code, maakt geen commits, geen PR**. Bij
onduidelijkheid rapporteert de audit dat gewoon; hij is nooit interactief.

## Geschiedenis

Tot medio 2026 waren dit "nachtelijke jobs" die zelf code aanpasten (tot en met automerge/deploy),
via een `story.md`+`subtasks.yaml`-config. Dat bleek achteraf de verkeerde vorm: eigenlijk was het
een audit, geen ontwikkelwerk. Vervangen door bovenstaande opzet.
