-- Hibernate ORM 7 (Quarkus 3.37+) resolves GenerationType.AUTO on Panache
-- entities to a per-entity pooled sequence generator, not the native
-- IDENTITY columns this schema was originally built for (older Hibernate
-- mapped AUTO to IDENTITY on PostgreSQL). The identity columns stay in
-- place - no data migration needed - this just adds the sequences Hibernate
-- now expects at startup schema validation.
--
-- Pool size (50) and start value must match exactly what Hibernate's
-- SequenceStyleGenerator creates (verified against its generated DDL),
-- or its block-allocation optimizer will hand out ids the sequence hasn't
-- actually reserved. Each sequence is seeded past any existing max id so
-- newly Hibernate-generated ids can't collide with rows already inserted
-- via the identity default (e.g. the seeded "DE 1-6" grade_scale row).

CREATE SEQUENCE grade_scale_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE school_class_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE student_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE subject_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE grade_category_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE assessment_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE grade_seq START WITH 1 INCREMENT BY 50;

SELECT setval('grade_scale_seq', COALESCE((SELECT MAX(id) FROM grade_scale), 0) + 1, false);
SELECT setval('school_class_seq', COALESCE((SELECT MAX(id) FROM school_class), 0) + 1, false);
SELECT setval('student_seq', COALESCE((SELECT MAX(id) FROM student), 0) + 1, false);
SELECT setval('subject_seq', COALESCE((SELECT MAX(id) FROM subject), 0) + 1, false);
SELECT setval('grade_category_seq', COALESCE((SELECT MAX(id) FROM grade_category), 0) + 1, false);
SELECT setval('assessment_seq', COALESCE((SELECT MAX(id) FROM assessment), 0) + 1, false);
SELECT setval('grade_seq', COALESCE((SELECT MAX(id) FROM grade), 0) + 1, false);
