-- ─────────────────────────────────────────────────────────────────────
-- V5: MP3 van podcasts opslaan in de database i.p.v. op het filesystem.
--
-- Op de OpenShift PVC kan de pod-user `/data/users` soms niet aanmaken
-- (AccessDeniedException uit Files.createDirectories), waardoor de
-- audio-render-stap faalt en de podcast op FAILED eindigt. Door de MP3
-- in een BYTEA-kolom te zetten verdwijnt de filesystem-dependency en is
-- de audio onderdeel van de DB-backup.
--
-- Genummerd als V5 (niet V4) omdat de gedeelde preview-DB al een
-- verlaten eerdere V4 ("add audio data to podcasts", kolom `audio_data`)
-- in `flyway_schema_history` heeft staan — die migratie zit niet in deze
-- repo. Daardoor is deze migratie idempotent t.o.v. beide situaties:
--   • fresh DB:   podcasts heeft alleen `audio_path` → drop het.
--   • preview-DB: podcasts heeft `audio_path` + `audio_data` → migreer
--                 de bytes naar `audio_bytes` en drop beide oude kolommen.
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE podcasts ADD COLUMN IF NOT EXISTS audio_bytes BYTEA;

-- Migreer eventuele bytes uit een legacy `audio_data`-kolom (preview-DB
-- met de verlaten eerdere V4) naar `audio_bytes` en ruim die kolom op.
-- EXECUTE is nodig omdat de planner anders bij een fresh DB faalt op
-- een statement dat een onbekende kolom benoemt.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'podcasts' AND column_name = 'audio_data'
    ) THEN
        EXECUTE 'UPDATE podcasts SET audio_bytes = audio_data WHERE audio_data IS NOT NULL AND audio_bytes IS NULL';
        EXECUTE 'ALTER TABLE podcasts DROP COLUMN audio_data';
    END IF;
END $$;

ALTER TABLE podcasts DROP COLUMN IF EXISTS audio_path;
