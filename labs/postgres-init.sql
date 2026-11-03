-- Ch 28-30 labs connect as "postgres" (their chapter setup scripts ran initdb -U postgres with lock_timeout = 8 s).
-- Scope the timeout to that role so labs that connect as "claude" keep the server default.
CREATE ROLE postgres SUPERUSER LOGIN;
ALTER ROLE postgres SET lock_timeout = '8s';
