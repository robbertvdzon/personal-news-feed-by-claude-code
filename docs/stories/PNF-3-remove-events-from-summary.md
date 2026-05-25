# PNF-3 — Verwijderen vanuit de events-lijst

## Doel

Elke rij in `EventsScreen` krijgt een prullenbak-icoon zodat de gebruiker
een event direct kan verwijderen zonder naar het detailscherm te navigeren.

## Stappenplan

- [x] Story-log aanmaken
- [x] `_EventTile` omzetten van `StatelessWidget` naar `ConsumerWidget`
- [x] Category-`Chip` van `trailing` naar subtitle verplaatsen
- [x] Prullenbak-icoon toevoegen als `trailing` met `delete()`-aanroep
- [x] Flutter-tests draaien
- [x] Commit

## Wat gedaan en waarom

`_EventTile` is omgezet naar `ConsumerWidget` zodat de widget direct
`ref.read(eventsProvider.notifier).delete(event.id)` kan aanroepen.
De category-`Chip` is toegevoegd aan de subtitle-tekst (als extra regel),
zodat `trailing` vrij is voor het `IconButton` met `Icons.delete_outline`.
Geen bevestigingsdialoog — conform het bestaande gedrag in `EventDetailScreen`.
