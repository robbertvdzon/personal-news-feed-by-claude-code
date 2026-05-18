-- ─────────────────────────────────────────────────────────────────────
-- V9 (KAN-62): rijke podcast-detail-samenvatting + takeaways.
--
-- De short-summary in `summary` (1-2 zinnen) blijft de feed-scanbare
-- card-tekst. Voor het podcast-detail-scherm willen we daarnaast:
--   - `long_summary`: 400-600 woorden NL-prose in 3-5 alinea's. NULL
--     voor cards die nog niet door de uitgebreide Claude-prompt heen
--     zijn — frontend valt in dat geval terug op de short-summary.
--   - `key_takeaways`: JSONB-array van 5-10 1-regel bullets. NULL of
--     leeg = sectie wordt verborgen.
--
-- We zetten de kolommen zowel op `podcast_episodes` (source-of-truth
-- die door de backfill-runner gevuld wordt) als op `rss_items` (waar
-- het detail-scherm 'm via het bestaande `/api/v1/stories`-payload
-- ophaalt). Geen NOT NULL — bestaande 14 KAN-60-rijen krijgen via de
-- backfill-runner alsnog hun waarde (story AC #7).
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE podcast_episodes ADD COLUMN IF NOT EXISTS long_summary  TEXT;
ALTER TABLE podcast_episodes ADD COLUMN IF NOT EXISTS key_takeaways JSONB;

ALTER TABLE rss_items        ADD COLUMN IF NOT EXISTS long_summary  TEXT;
ALTER TABLE rss_items        ADD COLUMN IF NOT EXISTS key_takeaways JSONB;

-- Index om de backfill-runner-query (status=DONE, summary_source=
-- transcript, long_summary IS NULL) snel te houden als de tabel
-- groeit. Partial index op de exacte WHERE-clausule.
CREATE INDEX IF NOT EXISTS podcast_episodes_backfill_long_summary_idx
    ON podcast_episodes (created_at)
    WHERE status = 'DONE'
      AND summary_source = 'transcript'
      AND long_summary IS NULL;
