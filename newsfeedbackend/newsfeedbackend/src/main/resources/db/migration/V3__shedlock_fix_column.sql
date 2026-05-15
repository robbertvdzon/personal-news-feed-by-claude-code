-- ─────────────────────────────────────────────────────────────────────
-- V3: ShedLock-kolom hernoemen naar `lock_until` (default kolomnaam
-- die net.javacrumbs.shedlock-provider-jdbc verwacht).
--
-- V2 maakte per ongeluk een kolom `lock_at` aan. ShedLock kan daardoor
-- geen locks acquiren → alle @Scheduled-methodes (hourlyRefresh,
-- dailySummary) werden sinds KAN-19 stilletjes geskipt.
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE shedlock RENAME COLUMN lock_at TO lock_until;
DROP INDEX IF EXISTS shedlock_lock_at_idx;
CREATE INDEX shedlock_lock_until_idx ON shedlock (lock_until);
