-- Notenfuchs no longer asks the login provider for email addresses; drop the ones stored so far.
ALTER TABLE teacher DROP COLUMN email;
