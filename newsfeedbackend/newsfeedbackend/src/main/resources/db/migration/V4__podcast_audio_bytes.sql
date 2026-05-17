-- ─────────────────────────────────────────────────────────────────────
-- V4: MP3 van podcasts opslaan in de database i.p.v. op het filesystem.
--
-- Op de OpenShift PVC kan de pod-user `/data/users` soms niet aanmaken
-- (AccessDeniedException uit Files.createDirectories), waardoor de
-- audio-render-stap faalt en de podcast op FAILED eindigt. Door de MP3
-- in een BYTEA-kolom te zetten verdwijnt de filesystem-dependency en is
-- de audio onderdeel van de DB-backup.
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE podcasts ADD COLUMN audio_bytes BYTEA;
ALTER TABLE podcasts DROP COLUMN audio_path;
