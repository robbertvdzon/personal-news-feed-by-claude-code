-- ─────────────────────────────────────────────────────────────────────
-- V12 (KAN-66): event_videos-tabel. Per al ontdekt event (KAN-65) worden
-- wekelijks de online video's (keynotes/sessies) ontdekt met AI + Tavily.
-- In deze story wordt nog GEEN samenvatting gemaakt; alleen de video plus
-- een eventuele Nederlandse beschrijving wordt opgeslagen.
--
-- Dedup op de canonieke video-URL per (gebruiker, event): PK
-- (username, event_id, video_url) zorgt dat een tweede ontdekking dezelfde
-- rij bijwerkt i.p.v. dupliceert. FK naar events zorgt dat video's mee-
-- verdwijnen als het event wordt verwijderd (en events cascaden van users).
-- description_nl is nullable — leeg wanneer AI/webresultaten geen bruikbare
-- beschrijving opleveren.
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE event_videos (
    username       TEXT NOT NULL,
    event_id       TEXT NOT NULL,
    video_url      TEXT NOT NULL,
    title          TEXT NOT NULL,
    description_nl TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (username, event_id, video_url),
    FOREIGN KEY (username, event_id) REFERENCES events(username, id) ON DELETE CASCADE
);
CREATE INDEX event_videos_event_idx ON event_videos (username, event_id);
