-- A student who left the class mid-year ("ausgetreten") keeps their grades for the reports; NULL means still in the class.
ALTER TABLE student ADD COLUMN left_on DATE;
