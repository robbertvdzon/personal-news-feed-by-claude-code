# Test de wijzigingen

Verifieer als tester dat de wijzigingen uit de nachtelijke security-job werken en niets
kapotmaken. De tester schrijft zelf geen productiecode of unittests — die horen bij de
development-subtaak; hier wordt alleen geverifieerd.

## Wat te doen
- Draai de bestaande build en testsuite en bevestig dat alles groen is.
- Controleer dat het functionele gedrag ongewijzigd is gebleven.
- Zijn er geen code-aanpassingen (bv. een worklog-only resultaat), dan hoeft er niets getest te
  worden; leg dat expliciet vast.

## Uitkomst
- Rapporteer de testresultaten duidelijk.
- Slaagt een test niet of is gedrag gewijzigd, meld dat concreet zodat het opgepakt kan worden.
