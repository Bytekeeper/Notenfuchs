-- "Copy class into a new school year" (see ClassUiResource#duplicate) links the new class
-- back to the one it was cloned from. Nullable and purely informational (future trend
-- features) - never used for access control or locking, so deleting the predecessor must
-- not be blocked by, or cascade into, a class only ever derived from it.
ALTER TABLE school_class
    ADD COLUMN predecessor_class_id BIGINT REFERENCES school_class(id) ON DELETE SET NULL;

CREATE INDEX idx_school_class_predecessor ON school_class(predecessor_class_id);
