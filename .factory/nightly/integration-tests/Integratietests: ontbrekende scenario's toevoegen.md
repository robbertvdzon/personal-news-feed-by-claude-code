# Integratietests: ontbrekende scenario's toevoegen

Zorg dat alle relevante scenario's gedekt zijn door de integratietests (incl. de `e2e`-suite).
Deze subtaak omvat al het ontwikkelwerk: tests in kaart brengen, toevoegen en draaien.

## Scope
- Breng in kaart welke functionele scenario's de code ondersteunt.
- Voeg ontbrekende integratie-/e2e-tests toe en verbeter bestaande waar nodig.

## Randvoorwaarden
- Pas alleen tests aan / voeg tests toe; verander **geen functioneel gedrag** van de productiecode.
- Alle tests (oud + nieuw) moeten slagen.
- Kom je een test tegen die duidelijk buggy gedrag zou "bevriezen"? Voeg 'm niet toe — ga in error
  met een notitie.
