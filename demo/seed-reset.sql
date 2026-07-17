-- Nightly demo-data reset for the free public test instance (see the
-- README's "Deploying a free demo instance" section and
-- .github/workflows/demo-nightly-redeploy.yml, which runs this against the
-- demo database every night after redeploying). Wipes every teacher-owned
-- table and reloads one fixed demo class, so visitors always land on the
-- same clean example regardless of what previous visitors typed into it
-- during the day. Also used by docker-compose.yml's seed-demo service to
-- seed a fresh local/self-host Postgres volume on first boot.
--
-- grade_scale is shared reference data (seeded once by Flyway in
-- V1__init.sql) and is deliberately left untouched here.

TRUNCATE TABLE grade, points_grade_band, assessment, grade_category, subject, student, school_class
    RESTART IDENTITY CASCADE;

-- The demo instance's fixed local-auth user is "lehrer" (see
-- LocalAuthConfigSource.FIXED_USERNAME) - every demo class must be owned by
-- that subject or it won't show up after logging in.
--
-- half_year_cutoff sits between "Mitarbeit 1. Halbjahr" (day -15, so it
-- lands in "1. Halbjahr" as its name implies) and "Klassenarbeit 2" (day
-- -10, landing in "2. Halbjahr") - Klassenarbeit 1 (day -30) also falls
-- into the first half, so the grid's Ohne Datum/H1/H2/Jahr split view has
-- at least one Leistung on each side of the cutoff to show off.
WITH new_class AS (
    INSERT INTO school_class (name, school_year, owner_subject, half_year_cutoff)
    VALUES ('Demo-Klasse 8b', '2025/26', 'lehrer', CURRENT_DATE - INTERVAL '12 days')
    RETURNING id
),
new_students AS (
    INSERT INTO student (school_class_id, name)
    SELECT new_class.id, s.name
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
    INSERT INTO subject (school_class_id, name, grade_scale_id, rounding_mode)
    SELECT new_class.id, 'Mathematik', grade_scale.id, 'IN_FAVOR_OF_STUDENT'
    FROM new_class, grade_scale
    WHERE grade_scale.name = 'DE 1-6'
    RETURNING id
),
new_categories AS (
    INSERT INTO grade_category (subject_id, name, weight_percent)
    SELECT new_subject.id, c.name, c.weight_percent
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
    INSERT INTO assessment (category_id, name, date, factor, points_based)
    SELECT new_categories.id, a.name, a.assessment_date, a.factor, a.points_based
    FROM new_categories
    JOIN (VALUES
        ('Schriftlich', 'Klassenarbeit 1', CURRENT_DATE - INTERVAL '30 days', 2.00, FALSE),
        ('Schriftlich', 'Klassenarbeit 2 (Punkte)', CURRENT_DATE - INTERVAL '10 days', 2.00, TRUE),
        ('Mündlich', 'Mitarbeit 1. Halbjahr', CURRENT_DATE - INTERVAL '15 days', 1.00, FALSE)
    ) AS a(category_name, name, assessment_date, factor, points_based)
        ON a.category_name = new_categories.name
    RETURNING id, name
)
INSERT INTO grade (assessment_id, student_id, value, points)
SELECT new_assessments.id, new_students.id, g.value, g.points
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
JOIN new_students ON new_students.name = g.student_name;

-- Notenschluessel for "Klassenarbeit 2 (Punkte)": the same two default
-- anchor bands SubjectUiResource#seedDefaultBands seeds for any freshly
-- points-based Assessment (60 -> best grade, 20 -> worst grade on the
-- subject's own GradeScale) - left unedited, so the example matches what a
-- teacher sees the first time they flip an Assessment to "Punktebasiert".
INSERT INTO points_grade_band (assessment_id, min_points, grade_value)
SELECT assessment.id, b.min_points, b.grade_value
FROM assessment
JOIN grade_category ON grade_category.id = assessment.category_id
JOIN subject ON subject.id = grade_category.subject_id
JOIN school_class ON school_class.id = subject.school_class_id
JOIN (VALUES (60.00, 1.0), (20.00, 6.0)) AS b(min_points, grade_value) ON true
WHERE assessment.name = 'Klassenarbeit 2 (Punkte)'
  AND school_class.name = 'Demo-Klasse 8b';

-- Verhaltensnoten: one behavior grade per student for the class's Fach.
-- Anna and Clara sit exactly on a half-grade boundary (1.5 / 2.5) so their
-- row lights up BehaviorGradeService's borderline highlight in the grid's
-- Ø column - with only one Fach in this demo class, that row average is
-- numerically identical to the Ø je Fach column average, though; the
-- feature's actual point (averaging across Fächer on different GradeScales)
-- only becomes visible once a second Fach is added to a class.
INSERT INTO behavior_grade (student_id, subject_id, value)
SELECT st.id, su.id, v.value
FROM student st
JOIN school_class sc ON sc.id = st.school_class_id
JOIN subject su ON su.school_class_id = sc.id AND su.name = 'Mathematik'
JOIN (VALUES
    ('Anna Beispiel', 1.5),
    ('Ben Muster', 2.0),
    ('Clara Test', 2.5),
    ('David Vogel', 2.0),
    ('Emma Weiss', 1.0)
) AS v(student_name, value) ON v.student_name = st.name
WHERE sc.name = 'Demo-Klasse 8b';
