-- Splits the single "class owner" concept into two tiers: ADMIN (today's full co-owner - roster,
-- settings, admin/Fachlehrer-tier management, delete any Subject, read-only class-wide grade
-- overview) and FACHLEHRER (a class-level Fachlehrer who can add a Subject or delete/manage
-- Subjects they personally teach, but not administer the class) - see OwnershipGuard's
-- isAdmin/isClassTeacher and CLAUDE.md's Authorization section. Every existing row predates this
-- distinction and was, under the old model, unconditionally a full owner - backfill ADMIN for all
-- of them so no existing access silently narrows on upgrade.
ALTER TABLE class_teacher ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE class_teacher ALTER COLUMN role DROP DEFAULT;
