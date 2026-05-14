-- Schema voor de software-factory observability-DB.
--
-- Wordt idempotent toegepast door poller.py's init_schema() bij elke
-- pod-start. Toekomstige wijzigingen onderaan toevoegen als
-- "ALTER TABLE … ADD COLUMN IF NOT EXISTS …" statements.
--
-- Conventies:
--   * Snake_case kolomnamen
--   * Tijdkolommen TIMESTAMPTZ met DEFAULT now()
--   * Bedrag NUMERIC(10,4) — usd-cents-genauig genoeg
--   * Tokens INTEGER — output_tokens van een enkele run blijft <2B

CREATE SCHEMA IF NOT EXISTS factory;

-- Eén row per pipeline-run van een story. Een nieuwe story_run wordt
-- gestart als er nog geen lopende is voor de story_key. Wordt afgesloten
-- (ended_at + final_status) bij de Klaar-transition.
CREATE TABLE IF NOT EXISTS factory.story_runs (
  id                   BIGSERIAL PRIMARY KEY,
  story_key            TEXT NOT NULL,
  started_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at             TIMESTAMPTZ,
  final_status         TEXT,                          -- 'Klaar', 'paused', 'budget-exceeded', etc.
  total_input_tokens   INTEGER NOT NULL DEFAULT 0,
  total_output_tokens  INTEGER NOT NULL DEFAULT 0,
  total_cost_usd_est   NUMERIC(10,4) NOT NULL DEFAULT 0.0
);

-- Eén row per Job/agent-run. Meerdere rows per story_run (refiner +
-- developer + reviewer + tester + retries).
CREATE TABLE IF NOT EXISTS factory.agent_runs (
  id              BIGSERIAL PRIMARY KEY,
  story_run_id    BIGINT NOT NULL REFERENCES factory.story_runs(id) ON DELETE CASCADE,
  role            TEXT NOT NULL,                     -- 'refiner', 'developer', 'reviewer', 'tester'
  job_name        TEXT NOT NULL,                     -- K8s Job-naam, bv. 'claude-run-kan-42-20260514-103200'
  model           TEXT,                              -- 'claude-sonnet-4-6'
  effort          TEXT,                              -- 'quick' | 'default' | 'deep'
  level           SMALLINT,                          -- snapshot van AI Level op moment van spawn
  started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at        TIMESTAMPTZ,
  outcome         TEXT,                              -- 'success', 'failed', 'questions', 'killed'
  input_tokens    INTEGER NOT NULL DEFAULT 0,
  output_tokens   INTEGER NOT NULL DEFAULT 0,
  cost_usd_est    NUMERIC(10,4) NOT NULL DEFAULT 0.0
);

-- Eén row per stream-json event uit Claude. Bewaart de hele payload als
-- JSONB voor latere analyse. Secrets worden vooraf vervangen door de
-- factory-report-helper in de runner-pod.
CREATE TABLE IF NOT EXISTS factory.agent_events (
  id              BIGSERIAL PRIMARY KEY,
  agent_run_id    BIGINT NOT NULL REFERENCES factory.agent_runs(id) ON DELETE CASCADE,
  ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
  kind            TEXT NOT NULL,                     -- 'system', 'assistant', 'tool_use', 'tool_result', 'result', 'raw'
  payload         JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS agent_runs_story_idx     ON factory.agent_runs(story_run_id);
CREATE INDEX IF NOT EXISTS agent_events_run_idx     ON factory.agent_events(agent_run_id, ts);
CREATE INDEX IF NOT EXISTS story_runs_key_idx       ON factory.story_runs(story_key, started_at DESC);

-- ── Migraties — voeg hier nieuwe ALTER TABLE statements toe. ────────────
--
-- Schema-bootstrap loopt elke poller-startup, dus `IF NOT EXISTS` is
-- essentieel om herhaling kosteloos te maken.

-- 2026-05-14 — cache-tokens, num_turns, duration en echte cost-uit-Claude.
-- De Claude CLI rapporteert input_tokens (= non-cached) los van
-- cache_read en cache_creation; voor een correcte cost-monitor (Fase 2)
-- hebben we alle drie nodig. `cost_usd_est` komt rechtstreeks uit het
-- CLI-result-event (total_cost_usd) en is altijd accurater dan zelf
-- rekenen met tarieven per model.
ALTER TABLE factory.agent_runs
    ADD COLUMN IF NOT EXISTS cache_read_input_tokens     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cache_creation_input_tokens INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS num_turns                   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS duration_ms                 INTEGER NOT NULL DEFAULT 0;

ALTER TABLE factory.story_runs
    ADD COLUMN IF NOT EXISTS total_cache_read_tokens     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_cache_creation_tokens INTEGER NOT NULL DEFAULT 0;

-- 2026-05-14 — per agent_run de finale samenvatting opslaan zodat we
-- 'm snel kunnen tonen op het dashboard (timeline-page) en kunnen
-- queriën zonder agent_events.payload uit te pluizen. Inhoud is wat
-- de agent op JIRA als '[ROLE] …'-comment plaatst.
ALTER TABLE factory.agent_runs
    ADD COLUMN IF NOT EXISTS summary_text TEXT;
