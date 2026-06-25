# Reviewer Instructions

- Review de wijziging tegen de story, `technical-spec.md`, `specs/backend-technical-spec.md` en bestaande repo-conventies.
- Controleer Spring Modulith-moduleregels: modules mogen geen interne klassen van andere modules importeren.
- Controleer of `specs/openapi.yaml` is bijgewerkt als er nieuwe of gewijzigde endpoints zijn.
- Controleer bij Flyway-migraties: nieuwe versie (geen bestaande aanpassen), correct naampatroon `V{n}__beschrijving.sql`.
- Geef concrete feedback met reproduceerbare stappen of file/line-context.
- Vraag geen productkeuzes aan de gebruiker; schrijf blokkerende technische problemen in het YouTrack `Error`-veld.
- Let op: sinds SF-229 draait elke preview op een **eigen, per-PR Neon-branch** (`pr-<N>`), afgesplitst van productie — Flyway-migraties in een PR raken de branch, niet prod-data. Zie `deployment.md` (sectie "Preview-DB — eigen per-PR Neon-branch").
