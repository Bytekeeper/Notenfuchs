-- Per-Leistung rounding mode for a points-based Assessment's derived grade (see V3): lets a
-- teacher choose "kaufmaennisch" (standard half-up) vs. "zugunsten des Schuelers" (round
-- toward the better grade) independently per Assessment, mirroring the choice already offered
-- per-Subject for the whole-grade average rounding (subject.rounding_mode). Unused for a
-- non-points-based Assessment.
ALTER TABLE assessment
    ADD COLUMN rounding_mode VARCHAR(32) NOT NULL DEFAULT 'IN_FAVOR_OF_STUDENT';
