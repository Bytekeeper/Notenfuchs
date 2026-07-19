-- Seeds one fixed demo class ("Demo-Klasse 8b") on a genuinely fresh install, so a new
-- self-hoster (or a fresh local dev setup) has something to look at without typing
-- anything in first - this replaces what used to be docker-compose.yml's separate
-- one-shot `seed-demo` service. As a Flyway migration it runs exactly once per database,
-- ever, tracked the same way as every other schema change - and specifically NOT on an
-- existing self-hosted instance's next upgrade, since the guard below only fires when
-- school_class is still completely empty. A stack that's ever had data in it (including
-- the demo class itself later being deleted) is never touched again.
--
-- This is deliberately a separate, one-time seed from demo/seed-reset.sql, which stays
-- the nightly TRUNCATE-and-reload script for the public demo instance (see
-- .github/workflows/demo-nightly-redeploy.yml) - that one runs directly against Neon via
-- psql, completely outside Flyway, and is free to keep evolving after this migration has
-- shipped. This file, once released, must never be edited (Flyway checksums it) - a
-- future change to the fresh-install dataset needs a new migration instead.
--
-- Every insert below assigns its id explicitly via nextval() on the same pooled
-- `<table>_seq` sequence Hibernate itself draws from at runtime (see V1__init.sql's
-- comment on why those sequences exist, decoupled from the identity columns' own hidden
-- counters), so - unlike relying on the IDENTITY default - the sequence is already
-- correctly advanced afterward with no separate setval step needed.
--
-- Granted to two teacher_subject values, not just one:
-- - "lehrer", the fixed local-auth login username (see LocalAuthConfigSource.FIXED_USERNAME)
--   - who you're actually logged in as when running the full stack (docker-compose's `app`
--   service, or a real self-hosted deploy) with local auth.
-- - "dev-user", CurrentUser.DEV_USER_SUBJECT - the fixed placeholder OwnershipGuard checks
--   against when there's no authenticated session at all, i.e. in %dev/%test, where the
--   HTTP auth policy is relaxed to permit and there's no login step to go through in the
--   first place (see CurrentUser#effectiveSubject). Without this second grant, a bare
--   `./mvnw quarkus:dev` against a fresh database would seed the class but never be able
--   to show it, since nobody is ever "lehrer" there. Harmless in a real deployment - nobody
--   ever authenticates as the literal string "dev-user" outside that bypass.
--
-- Every demo class must have a class_teacher row (and every demo subject a subject_teacher
-- row) for a given subject, or it won't show up for that teacher (see OwnershipGuard).
--
-- half_year_cutoff sits between "Mitarbeit 1. Halbjahr" (day -15, so it lands in "1.
-- Halbjahr" as its name implies) and "Klassenarbeit 2" (day -10, landing in "2.
-- Halbjahr") - Klassenarbeit 1 (day -30) also falls into the first half, so the grid's
-- Ohne Datum/H1/H2/Jahr split view has at least one Leistung on each side of the cutoff
-- to show off.
WITH new_class AS (
    INSERT INTO school_class (id, name, school_year, half_year_cutoff)
    SELECT nextval('school_class_seq'), 'Demo-Klasse 8b', '2025/26', CURRENT_DATE - INTERVAL '12 days'
    WHERE NOT EXISTS (SELECT 1 FROM school_class)
    RETURNING id
),
new_class_teacher AS (
    INSERT INTO class_teacher (id, school_class_id, teacher_subject)
    SELECT nextval('class_teacher_seq'), new_class.id, t.teacher_subject
    FROM new_class, (VALUES ('lehrer'), ('dev-user')) AS t(teacher_subject)
),
new_students AS (
    INSERT INTO student (id, school_class_id, name)
    SELECT nextval('student_seq'), new_class.id, s.name
    FROM new_class, (VALUES
        ('Anna Beispiel'),
        ('Ben Muster'),
        ('Clara Test'),
        ('David Vogel'),
        ('Emma Weiss')
    ) AS s(name)
    RETURNING id, name
),
new_subject AS (
    INSERT INTO subject (id, school_class_id, name, grade_scale_id, rounding_mode)
    SELECT nextval('subject_seq'), new_class.id, 'Mathematik', grade_scale.id, 'IN_FAVOR_OF_STUDENT'
    FROM new_class, grade_scale
    WHERE grade_scale.name = 'DE 1-6'
    RETURNING id
),
new_subject_teacher AS (
    INSERT INTO subject_teacher (id, subject_id, teacher_subject)
    SELECT nextval('subject_teacher_seq'), new_subject.id, t.teacher_subject
    FROM new_subject, (VALUES ('lehrer'), ('dev-user')) AS t(teacher_subject)
),
new_categories AS (
    INSERT INTO grade_category (id, subject_id, name, weight_percent)
    SELECT nextval('grade_category_seq'), new_subject.id, c.name, c.weight_percent
    FROM new_subject, (VALUES
        ('Schriftlich', 50.00),
        ('Mündlich', 50.00)
    ) AS c(name, weight_percent)
    RETURNING id, name
),
-- Klassenarbeit 2 is points-based, to also show off Notenschluessel
-- (points -> grade) conversion in the example - see the points_grade_band
-- insert below for its bands.
new_assessments AS (
    INSERT INTO assessment (id, category_id, name, date, factor, points_based)
    SELECT nextval('assessment_seq'), new_categories.id, a.name, a.assessment_date, a.factor, a.points_based
    FROM new_categories
    JOIN (VALUES
        ('Schriftlich', 'Klassenarbeit 1', CURRENT_DATE - INTERVAL '30 days', 2.00, FALSE),
        ('Schriftlich', 'Klassenarbeit 2 (Punkte)', CURRENT_DATE - INTERVAL '10 days', 2.00, TRUE),
        ('Mündlich', 'Mitarbeit 1. Halbjahr', CURRENT_DATE - INTERVAL '15 days', 1.00, FALSE)
    ) AS a(category_name, name, assessment_date, factor, points_based)
        ON a.category_name = new_categories.name
    RETURNING id, name
),
-- Every remaining insert below stays chained off new_class/new_students/new_subject/
-- new_assessments (rather than looking up rows by name against the live tables) so the
-- WHERE NOT EXISTS guard above cascades all the way through: on a database that already
-- has data (e.g. it already contains a class literally named "Demo-Klasse 8b"), every
-- CTE here is empty and nothing is inserted anywhere. A name-based lookup instead of this
-- chaining would re-match that pre-existing row and insert duplicate/conflicting data.
new_grades AS (
    INSERT INTO grade (id, assessment_id, student_id, value, points)
    SELECT nextval('grade_seq'), new_assessments.id, new_students.id, g.value, g.points
    FROM new_assessments
    JOIN (VALUES
        ('Klassenarbeit 1', 'Anna Beispiel', 1.7, NULL),
        ('Klassenarbeit 1', 'Ben Muster', 2.3, NULL),
        ('Klassenarbeit 1', 'Clara Test', 3.0, NULL),
        ('Klassenarbeit 1', 'David Vogel', 2.0, NULL),
        ('Klassenarbeit 1', 'Emma Weiss', 1.3, NULL),
        ('Klassenarbeit 2 (Punkte)', 'Anna Beispiel', NULL, 58),
        ('Klassenarbeit 2 (Punkte)', 'Ben Muster', NULL, 47),
        ('Klassenarbeit 2 (Punkte)', 'Clara Test', NULL, 33),
        ('Klassenarbeit 2 (Punkte)', 'David Vogel', NULL, 41),
        ('Klassenarbeit 2 (Punkte)', 'Emma Weiss', NULL, 62),
        ('Mitarbeit 1. Halbjahr', 'Anna Beispiel', 2.0, NULL),
        ('Mitarbeit 1. Halbjahr', 'Ben Muster', 2.0, NULL),
        ('Mitarbeit 1. Halbjahr', 'Clara Test', 2.7, NULL),
        ('Mitarbeit 1. Halbjahr', 'David Vogel', 1.7, NULL),
        ('Mitarbeit 1. Halbjahr', 'Emma Weiss', 1.0, NULL)
    ) AS g(assessment_name, student_name, value, points)
        ON g.assessment_name = new_assessments.name
    JOIN new_students ON new_students.name = g.student_name
),
-- Notenschluessel for "Klassenarbeit 2 (Punkte)": the same two default
-- anchor bands SubjectUiResource#seedDefaultBands seeds for any freshly
-- points-based Assessment (60 -> best grade, 20 -> worst grade on the
-- subject's own GradeScale) - left unedited, so the example matches what a
-- teacher sees the first time they flip an Assessment to "Punktebasiert".
new_points_grade_band AS (
    INSERT INTO points_grade_band (id, assessment_id, points, grade_value)
    SELECT nextval('points_grade_band_seq'), new_assessments.id, b.points, b.grade_value
    FROM new_assessments
    JOIN (VALUES (60.00, 1.0), (20.00, 6.0)) AS b(points, grade_value) ON true
    WHERE new_assessments.name = 'Klassenarbeit 2 (Punkte)'
)
-- Verhaltensnoten: one behavior grade per student for the class's Fach.
-- Anna and Clara sit exactly on a half-grade boundary (1.5 / 2.5) so their
-- row lights up BehaviorGradeService's borderline highlight in the grid's
-- Ø column - with only one Fach in this demo class, that row average is
-- numerically identical to the Ø je Fach column average, though; the
-- feature's actual point (averaging across Fächer on different GradeScales)
-- only becomes visible once a second Fach is added to a class.
INSERT INTO behavior_grade (id, student_id, subject_id, value)
SELECT nextval('behavior_grade_seq'), new_students.id, new_subject.id, v.value
FROM new_students
CROSS JOIN new_subject
JOIN (VALUES
    ('Anna Beispiel', 1.5),
    ('Ben Muster', 2.0),
    ('Clara Test', 2.5),
    ('David Vogel', 2.0),
    ('Emma Weiss', 1.0)
) AS v(student_name, value) ON v.student_name = new_students.name;
