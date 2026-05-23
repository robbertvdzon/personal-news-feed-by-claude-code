-- ─────────────────────────────────────────────────────────────────────
-- V13 (KAN-67): summary_nl-kolom op event_videos voor de Nederlandse
-- on-demand samenvatting per video. Default NULL — wordt pas gevuld
-- nadat de gebruiker op "Maak samenvatting" drukt en de pipeline
-- transcript + Claude succesvol heeft uitgevoerd.
--
-- BELANGRIJK: de wekelijkse video-discovery-upsert in
-- EventVideoRepository.UPSERT_SQL mag dit veld NIET overschrijven.
-- Een tweede ontdekking van dezelfde video moet de eerder gemaakte
-- samenvatting laten staan (anders zou de Claude-call effectief
-- weggegooid worden). De huidige UPSERT raakt summary_nl niet aan
-- (alleen title, description_nl, updated_at), maar deze migratie
-- documenteert de afspraak expliciet.
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE event_videos
    ADD COLUMN summary_nl TEXT;
