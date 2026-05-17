-- ─────────────────────────────────────────────────────────────────────
-- V7 (KAN-60): tweefasen podcast-ingest + filter-discriminator op feed.
--
-- KAN-56 deed download+Whisper+Claude in één async-batch op het kritieke
-- pad. Bij een feed-burst (7 nieuwe afleveringen tegelijk) raakten alle
-- Whisper-calls het OpenAI-quotum en eindigden ze in show-notes-fallback
-- — zonder retry, zonder zichtbare badge. Deze migratie ondersteunt de
-- nieuwe flow:
--
--   1. Snelle fase: show-notes → Claude → rss_items card met
--      summary_source='show_notes', status=NEEDS_TRANSCRIPT (of
--      SHOW_NOTES_DONE als transcribe per feed uit staat).
--   2. Async fase: PodcastTranscriptWorker pakt MAX 1 episode per tick
--      op met status=NEEDS_TRANSCRIPT en next_attempt_at <= now(), doet
--      download + Whisper + re-summarize. Op 429/5xx: retry_count++ en
--      next_attempt_at = now() + backoff (5m → 15m → 45m → 24h).
--
-- Bestaande rijen (status DONE/FAILED uit KAN-56) blijven onaangetast;
-- defaults zijn backward-compatible.
-- ─────────────────────────────────────────────────────────────────────

-- Retry-state voor de async transcript-worker. NULL next_attempt_at =
-- "klaar om opgepakt te worden". Op 429/5xx zet de worker hem in de
-- toekomst conform de backoff-tabel. retry_count is het aantal mislukte
-- Whisper-pogingen (0 = nog niet geprobeerd).
ALTER TABLE podcast_episodes ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE podcast_episodes ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ;

-- 'show_notes' wanneer de samenvatting nog op basis van de RSS-
-- description gemaakt is; 'transcript' wanneer Whisper het transcript
-- heeft geleverd en Claude opnieuw is gedraaid. Default 'transcript'
-- houdt KAN-56-rijen achterwaarts compatibel (die zijn altijd op
-- transcript-basis afgerond).
ALTER TABLE podcast_episodes ADD COLUMN IF NOT EXISTS summary_source TEXT NOT NULL DEFAULT 'transcript';
ALTER TABLE rss_items        ADD COLUMN IF NOT EXISTS summary_source TEXT NOT NULL DEFAULT 'transcript';

-- Discriminator op feed_items zodat de Feed-tab filter (AC8) op rij-
-- niveau kan filteren zonder cross-join met rss_items. Default 'ARTICLE'
-- → bestaande feed_items uit de KAN-56-periode worden veilig als RSS
-- gezien (refiner-aanname).
ALTER TABLE feed_items ADD COLUMN IF NOT EXISTS media_type TEXT NOT NULL DEFAULT 'ARTICLE';

-- Index om de worker-query (status NEEDS_TRANSCRIPT, sorteer op
-- next_attempt_at) snel te houden als de tabel groeit.
CREATE INDEX IF NOT EXISTS podcast_episodes_pending_transcript_idx
    ON podcast_episodes (next_attempt_at)
    WHERE status = 'NEEDS_TRANSCRIPT';
