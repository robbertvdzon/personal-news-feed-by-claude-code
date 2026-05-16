-- ─────────────────────────────────────────────────────────────────────
-- V4: MP3-audio voor podcasts verhuist van filesystem naar Postgres.
--
-- Aanleiding: de pod-user kan op de OpenShift PVC `/data/users/<u>/audio`
-- niet altijd aanmaken (AccessDeniedException op /data/users), waardoor
-- de hele podcast-generatie faalt. Door de MP3 als bytea in de tabel
-- te zetten valt het hele filesystem-pad weg.
--
-- Bestaande DONE-rows: hun bytes stonden op disk en zijn niet meer
-- beschikbaar. We zetten ze niet automatisch op FAILED — de audio-
-- endpoint geeft simpelweg een 404 als audio_data NULL is. Users die
-- de podcast opnieuw willen kunnen 'm verwijderen en aanmaken.
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE podcasts ADD COLUMN audio_data BYTEA;

-- audio_path was een serverpad naar het MP3-bestand op disk; sinds de
-- bytes in audio_data staan heeft de kolom geen functie meer.
ALTER TABLE podcasts DROP COLUMN audio_path;
