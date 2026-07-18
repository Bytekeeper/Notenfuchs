# Notenfuchs

Notenfuchs is an open-source grade-management tool for teachers, built with
[Quarkus](https://quarkus.io/). It lets a teacher organize school classes, students,
subjects, weighted grade categories (e.g. "Schriftlich" / "Muendlich"), individual
assessments, and grades - and computes weighted per-student, per-subject averages
and rounded final grades automatically.

## Running Notenfuchs

### Option A: full stack with Docker Compose

This starts PostgreSQL and the packaged application - nothing else. Login uses
Notenfuchs' own built-in local auth (see "Authentication" below); no separate
identity-provider container or setup step required.

```bash
cp .env.example .env      # then set NOTENFUCHS_PASSWORD
docker compose up
```

This pulls a prebuilt image from `ghcr.io/bytekeeper/notenfuchs` (published by
CI on every push to `master`, see `.github/workflows/publish-image.yml`) - no
local Java/Maven toolchain needed. The API is then available at
`http://localhost:8080` (log in at `/login`), and PostgreSQL at
`localhost:5432` (database `notenfuchs`, user/password `notenfuchs`).

Working on the code and want to run your own changes instead of the published
image? Build locally first:

```bash
./mvnw package
docker compose up --build
```

This builds a JVM-mode container image from `src/main/docker/Dockerfile.jvm`,
which expects `./mvnw package` to already have produced `target/quarkus-app/`.

To stop everything: `docker compose down` (add `-v` to also drop the database volume).

Prefer an external SSO provider instead (e.g. for a school that already runs
one)? See "Alternative: OIDC (external SSO)" under "Authentication" below - it
adds an optional `docker-compose.oidc.yml` overlay rather than changing the
default stack.

### Option B: local development against Postgres

Start just the database:

```bash
docker compose up postgres
```

Then run the app in Quarkus dev mode (live reload) with the Maven Wrapper - no local
Maven installation required:

```bash
./mvnw quarkus:dev
```

By default this connects to `jdbc:postgresql://localhost:5432/notenfuchs` (user/password
`notenfuchs`), matching the `postgres` service above. Override any of `DB_URL`, `DB_USER`,
`DB_PASSWORD` as environment variables if you point at a different database.

> Note: Quarkus can normally auto-provision a throwaway database via **Dev Services** when
> no datasource is configured at all. This project deliberately configures an explicit
> PostgreSQL datasource (see `src/main/resources/application.properties`) so that Dev Mode
> and `docker compose` use the same schema-managed database instead of an ephemeral one.

### Running tests

```bash
./mvnw test      # unit tests
./mvnw verify    # also runs the browser end-to-end tests (needs Docker)
```

`GradeServiceTest` is a plain JUnit 5 unit test (no `@QuarkusTest`, no database) that
verifies the grade-calculation logic directly.

`GradeGridE2EIT` (`src/test/java/de/notenfuchs/e2e`) drives the real grade-entry grid
through a browser with [Playwright](https://playwright.dev/), via the
[quarkus-playwright](https://docs.quarkiverse.io/quarkus-playwright/dev/) extension. It
runs as a Failsafe integration test (`./mvnw verify`, not `./mvnw test`), since it needs
Docker: both the browser (Playwright's Dev Services container) and PostgreSQL
(Testcontainers Dev Services) run in containers, no local browser install required.

`OwnershipGuardIT` (`src/test/java/de/notenfuchs/security`) is also a Failsafe integration
test (needs Docker for its Testcontainers Postgres, no browser involved) that seeds
`SchoolClass` rows with different `ownerSubject` values and asserts cross-tenant isolation
directly against `OwnershipGuard` - see "Per-teacher data ownership" above.

## The grade model

- **GradeScale**: defines a grading scale (`min`, `max`, and `lowerIsBetter`). The
  German school scale ("DE 1-6", 1 = best, 6 = worst) is seeded by the initial Flyway
  migration.
- **Subject**: belongs to a `SchoolClass`, references a `GradeScale`, and has a
  `roundingMode` (`COMMERCIAL` or `IN_FAVOR_OF_STUDENT`).
- **GradeCategory**: a weighted category within a subject (e.g. "Schriftlich" 50%,
  "Muendlich" 50%), identified by `weightPercent`.
- **Assessment**: a single graded event within a category (e.g. one test), with a
  `factor` (default 1.0) controlling how strongly it counts within its category.
- **Grade**: one student's numeric result (`NUMERIC(4,2)`) for one assessment.

### How averages are computed

For a given student and subject:

1. **Category average** = the weighted mean of the student's grades in that category,
   each grade weighted by its assessment's `factor`:
   `sum(value_i * factor_i) / sum(factor_i)`.
2. **Subject average** = the weighted combination of category averages using each
   category's `weightPercent`, normalized only over the categories that actually have
   at least one grade for that student. An empty category (no grades yet) is excluded
   entirely rather than dragging the average down - e.g. if "Muendlich" (50%) has no
   grades yet, the subject average is simply the "Schriftlich" average, not diluted by
   an implicit zero.
3. The **raw average** is exposed rounded to 2 decimal places for display (internal
   computation uses higher precision, `MathContext.DECIMAL64`, throughout).
4. The **final grade** is the raw average rounded to a whole number per the subject's
   `roundingMode`:
   - `COMMERCIAL`: standard numeric half-up rounding (e.g. 2.50 -> 3), independent of
     which direction is "better" on the scale.
   - `IN_FAVOR_OF_STUDENT`: identical, except an exact half (x.50) rounds toward
     whichever whole number is better for the student, based on the scale's
     `lowerIsBetter` flag (e.g. on the DE 1-6 scale, 2.50 -> 2, the better grade).

This logic lives in `GradeService` (`src/main/java/de/notenfuchs/service/GradeService.java`)
as a pure, dependency-free POJO service operating on plain DTOs (`CategoryData`,
`GradeData`) - it never touches the database and never hardcodes any specific scale
(such as 1-6), so it is fully unit-testable and scale-agnostic.

## Why grade values are `NUMERIC`, not an enum

Grade values are stored as a plain `NUMERIC(4,2)` (`BigDecimal` in Java), not as an
enum tied to the German 1-6 scale. The scale itself - its bounds and whether lower or
higher values are "better" - lives entirely in the `GradeScale` entity/table. This
means a future grading scale (for example a 0-15 "Punkte" scale, common in German
*Oberstufe* / IB-style grading) can be added later with a simple `INSERT INTO
grade_scale ...` and does **not** require any schema migration or code change to the
`grade` table - only a new `GradeScale` row and a `Subject` referencing it.

## Authentication

Notenfuchs needs exactly one of two things configured to run: a local password
(the default, turbo-fast path - nothing else to set up) or an OIDC issuer (for
schools that already run central SSO). Whichever is actually configured wins -
the two are never active at the same time (see `de.notenfuchs.security.LocalAuthConfigSource`).
In production, if **neither** is configured, the app refuses to start rather
than serve grade data unauthenticated (see "Fail-fast in production" below).

### Default: local built-in auth

Set `NOTENFUCHS_PASSWORD` (in `.env`, or as a real env var) and nothing else -
`docker compose up` (see "Option A" above) is then immediately usable at
`http://localhost:8080/login`. This uses Quarkus' own embedded security realm
(`quarkus-elytron-security-properties-file`) plus built-in FORM authentication
- no database table, no registration flow, no password-reset UI, no hashing.

- **Username** is the fixed value `lehrer` (single-teacher/self-host use case
  - see ROADMAP.md's design principles). To use a different username, change
  the literal in `de.notenfuchs.security.LocalAuthConfigSource` and rebuild.
- **Password** is whatever `NOTENFUCHS_PASSWORD` is set to, stored in plain
  text in the embedded realm config - the same trust level as `DB_PASSWORD`.
  There's no hashing/rotation tooling; treat the env var itself as the secret.
- Logging in happens at `/login`; logging out uses the "Logout" link in the
  nav (`/local-logout`), which clears the session cookie.

### Alternative: OIDC (external SSO)

Leave `NOTENFUCHS_PASSWORD` unset to use this mode instead. Notenfuchs then
secures the whole application (all UI pages and all `/api/*` endpoints) with
[OIDC](https://openid.net/developers/how-connect-works/) using the
`quarkus-oidc` extension in **web-app mode**: a standard authorization-code flow
with a server-side session cookie, suitable for the server-rendered HTML (Qute +
HTMX) frontend - not a token-based SPA setup. It is provider-agnostic and works
with any standards-compliant OIDC provider (Clerk, Keycloak, Authentik, Auth0, ...).

#### Required environment variables (production)

| Variable | Description |
|---|---|
| `OIDC_ISSUER_URL` | Your provider's issuer/discovery URL, e.g. `https://your-app.clerk.accounts.dev` (Clerk) or `https://idp.example.com/realms/notenfuchs` (Keycloak). Quarkus fetches `<issuer>/.well-known/openid-configuration` from this. |
| `OIDC_CLIENT_ID` | The OIDC client ID registered with your provider. |
| `OIDC_CLIENT_SECRET` | The confidential client secret for that client. |

These map to `quarkus.oidc.auth-server-url`, `quarkus.oidc.client-id`, and
`quarkus.oidc.credentials.secret` in `application.properties`, which otherwise
fall back to non-functional local-dev placeholder values.

Register the following redirect URI with your provider (adjust the host to your
deployment): `https://your-domain.example/auth-callback`. This is controlled by
`quarkus.oidc.authentication.redirect-path` and defaults to `/auth-callback`.
Logout is available at `/logout` (RP-initiated logout, `quarkus.oidc.logout.*`),
which redirects back to `/` afterwards.

By default the app requests the `openid profile email` scopes (`openid` is added
automatically by Quarkus; `profile` and `email` are configured explicitly) so
that the logged-in user's name/email are available via the OIDC UserInfo endpoint.

#### Setting up Pocket ID (optional overlay)

```bash
docker compose -f docker-compose.yml -f docker-compose.oidc.yml up
```

starts [Pocket ID](https://pocket-id.org), a lightweight, passkey-based OIDC
provider, alongside Notenfuchs - no separate signup with a third-party service
required. (Set `COMPOSE_FILE=docker-compose.yml:docker-compose.oidc.yml` in
`.env` once - see `.env.example` - and every later `docker compose` command
picks up both files automatically, no `-f -f` needed.) Everything is set up by
hand in Pocket ID's own admin UI, no bootstrap automation involved:

1. **Generate the secret Pocket ID needs** and put it in `.env` (copied from
   `.env.example`): `POCKET_ID_ENCRYPTION_KEY`, via `openssl rand -base64 32`.
2. **Create the Pocket ID admin account.** This is the one step that can't be automated:
   Pocket ID has no passwords, only WebAuthn passkeys, and registering one requires an
   actual browser + authenticator. Visit `http://localhost:1411/setup` (or
   `http://<BASE_URL>:1411/setup`) once and follow the prompts.
3. **Register Notenfuchs as an OIDC client.** In Pocket ID's admin UI, go to
   OIDC Clients → New Client, set the callback URL to
   `http://<BASE_URL>:8080/auth-callback` and the logout callback URL to
   `http://<BASE_URL>:8080/`, then expand "Advanced options" and set the
   **Client ID** field to exactly `notenfuchs` (matching the fixed
   `OIDC_CLIENT_ID` in `docker-compose.oidc.yml`). Saving generates a Client
   Secret - copy it into `.env` as `OIDC_CLIENT_SECRET`, then run
   `docker compose up -d app` to pick it up.
4. **Hand out a sign-up link.** On the Users page, use "Create signup token" to
   generate a single-use link and give it to whoever should actually use
   Notenfuchs (e.g. yourself, or another teacher) so they can create their own
   passkey account directly - you never need to share the admin login.

By default the whole stack assumes you're running it on your own machine: `BASE_URL`
in `.env` defaults to `localhost`, and both Notenfuchs (`:8080`) and Pocket ID (`:1411`)
are reached over plain HTTP on that host. If you're hosting this for others, set
`BASE_URL` to your real domain, put a reverse proxy with TLS in front of both ports,
and set `TRUST_PROXY=true` in `.env` so Pocket ID trusts the proxy's forwarded-for
headers.

Pocket ID is entirely optional - the OIDC wiring is provider-agnostic (see above), so
you can point `OIDC_ISSUER_URL` / `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` at Clerk,
Keycloak, Authentik, or any other standards-compliant provider instead and skip
`docker-compose.oidc.yml` entirely.

#### Setting up Clerk

1. In the Clerk dashboard, create (or reuse) an application and add an **OAuth
   application** under "OAuth Applications" (Clerk's OIDC/OAuth client feature) -
   this gives you a client ID and client secret and exposes a standard OIDC
   discovery document.
2. Copy the issuer URL shown there (typically `https://<your-instance>.clerk.accounts.dev`,
   or your custom domain) into `OIDC_ISSUER_URL`.
3. Copy the client ID and client secret into `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET`.
4. Add your deployment's callback URL as an allowed redirect URI in the Clerk
   OAuth application settings: `https://your-domain.example/auth-callback`.
5. Double-check before going live: confirm Clerk's discovery document
   (`<issuer>/.well-known/openid-configuration`) is reachable and that the OAuth
   application is a **confidential** client (i.e. it actually has a client secret) -
   Clerk primarily markets itself as an auth provider with its own SDKs, and the
   generic OIDC/OAuth application support is a secondary feature, so verify the
   exact discovery URL and scope support (`profile`, `email`) against Clerk's
   current OAuth documentation for your account rather than assuming Keycloak-like
   defaults.

### Fail-fast in production

`de.notenfuchs.security.AuthConfigurationCheck` observes `StartupEvent` and, in
the default/production profile only, throws (aborting startup) if neither
`NOTENFUCHS_PASSWORD` nor `OIDC_ISSUER_URL` is set - so a misconfigured
deployment fails loudly at boot instead of silently serving every teacher's
grade data unauthenticated.

### `%dev` / test bypass

`quarkus.oidc.tenant-enabled` is set to `false` for the `dev` and `test` profiles
(`%dev.quarkus.oidc.tenant-enabled=false`, `%test.quarkus.oidc.tenant-enabled=false`;
note this is `tenant-enabled`, not the build-time `enabled` switch - the latter would
remove the OIDC extension's CDI beans entirely, breaking `CurrentUser`'s unconditional
`@Inject` fields), and the blanket `authenticated` HTTP permission policy is relaxed to
`permit` for those same profiles. This means `./mvnw quarkus:dev` and `./mvnw test` both
run without a live identity provider (or a locally-configured password) and without
login - exactly like before authentication was added. This bypass is **not** active in
the default/production profile; do not rely on it outside local development.

### User identity in code

`de.notenfuchs.security.CurrentUser` (request-scoped bean) exposes the logged-in
user's OIDC subject, email, and display name for use in REST resources or Qute
templates - see the Javadoc on that class for details. `CurrentUser.effectiveSubject()`
is the value actually used for per-teacher data ownership (see below).

### Per-teacher data ownership

Authentication (above) proves *who* is logged in; ownership is a separate layer that
makes sure each teacher only ever sees and touches their **own** classes. There are no
roles and no sharing between teachers - just single-tenant isolation per OIDC identity.

- `SchoolClass.ownerSubject` (the owning teacher's OIDC `sub`) is the ownership root.
  `Student`, `Subject`, `GradeCategory`, `Assessment`, and `Grade` don't carry their own
  owner column - they're scoped by walking up to their `SchoolClass`. `GradeScale` is
  shared reference data, not owned by anyone.
- `de.notenfuchs.security.OwnershipGuard` is the single place this is enforced. Every
  REST/web endpoint that reads or writes an entity by id resolves it through one of its
  `requireOwned*` methods; a foreign class/subject/student/etc. and an unknown id both
  come back as a plain **404**, deliberately indistinguishable, so a teacher can't tell
  "doesn't exist" apart from "isn't yours". List endpoints (e.g. `GET /api/school-classes`)
  are filtered to the current teacher rather than returning everyone's data.
- In `%dev`/`%test`, where OIDC is disabled (see above), `CurrentUser.effectiveSubject()`
  falls back to a fixed `"dev-user"` subject, so ownership still works locally and in
  tests without a real login.

## Deploying a free demo instance

For letting people try Notenfuchs before committing to self-hosting it for real: a
publicly reachable instance running on free-tier hosting, redeployed from latest
`master` and reset to a fixed demo dataset every night. Two pieces, wired together by
files already in this repo:

- **[Render](https://render.com)** runs the app itself, built from
  `src/main/docker/Dockerfile.render` (a multi-stage Dockerfile that runs the Maven
  build from source - unlike `Dockerfile.jvm`, which expects `./mvnw package` to have
  already run) via `render.yaml` (Render "Blueprint" - Render auto-detects this file).
- **[Neon](https://neon.tech)** provides the free Postgres - Render's own free tier has
  no persistent free Postgres option.

### One-time setup

1. Create a free Neon project and note its connection details (host, database, user,
   password).
2. In Render, create a new **Blueprint** pointed at this repo - it picks up
   `render.yaml` automatically.
3. In the Render service's environment settings, set:

   | Variable | Value |
   |---|---|
   | `DB_URL` | `jdbc:postgresql://<neon-host>/<db>?sslmode=require` |
   | `DB_USER` | Your Neon role name |
   | `DB_PASSWORD` | Your Neon role password |
   | `NOTENFUCHS_PASSWORD` | A throwaway password, safe to publish next to the demo link - this instance only ever holds demo data that gets wiped nightly (see below). |

4. Create a **Deploy Hook** in the Render service's settings and copy its URL.
5. Note the service id (`srv-...`, in the service's URL) and create a Render API key
   (account settings).
6. Add four secrets to this GitHub repo (Settings → Secrets and variables → Actions):
   `RENDER_DEPLOY_HOOK_URL`, `RENDER_API_KEY`, `RENDER_SERVICE_ID`, and
   `DEMO_DATABASE_URL` (the Neon connection string, direct/unpooled,
   `postgresql://user:password@host/db?sslmode=require`).

### What happens nightly

`.github/workflows/demo-nightly-redeploy.yml` runs on a cron schedule (03:00 UTC) and:

1. Triggers the Render deploy hook, which redeploys the latest `master` commit.
2. Polls the Render API until that deploy reports `live` (rather than a fixed sleep -
   free-tier builds can take a few minutes).
3. Runs `demo/seed-reset.sql` directly against the Neon database, wiping every
   teacher-owned table (`grade_scale` - shared reference data - is left untouched) and
   reloading one fixed demo class (`Demo-Klasse 8b`, owned by the fixed local-auth user
   `lehrer` - see "Default: local built-in auth" above) with a handful of students and
   grades.

Trigger it manually from the Actions tab (`workflow_dispatch`) to redeploy/reset on
demand instead of waiting for the nightly run.

### Limitations

- Render's free web services spin down after about 15 minutes idle; the first request
  after that wakes it back up (30-60s cold start). Fine for a "kick the tires" demo, not
  for anything latency-sensitive.
- Free-tier terms on both platforms change over time - check Render's and Neon's
  current limits before relying on this long-term.
- This is a throwaway demo, not a template for a real deployment: the published
  `NOTENFUCHS_PASSWORD` and nightly wipe are both intentional here and wrong for an
  instance holding real student data.

## REST API

All endpoints are under `/api` and scoped to the logged-in teacher (see "Per-teacher
data ownership" above) - list endpoints only return that teacher's data, and a foreign
or unknown id returns 404:

- `GET/POST /api/school-classes`, `GET/PUT/DELETE /api/school-classes/{id}`
- `GET/POST /api/students`, `GET/PUT/DELETE /api/students/{id}` (filter list with `?schoolClassId=`)
- `GET/POST /api/subjects`, `GET/PUT/DELETE /api/subjects/{id}` (filter list with `?schoolClassId=`)
- `GET/POST /api/grade-categories`, `GET/PUT/DELETE /api/grade-categories/{id}` (filter with `?subjectId=`)
- `GET/POST /api/assessments`, `GET/PUT/DELETE /api/assessments/{id}` (filter with `?categoryId=`)
- `GET/POST /api/grades`, `GET/PUT/DELETE /api/grades/{id}` (filter with `?studentId=` / `?assessmentId=`)
- `GET/POST /api/behavior-grades`, `GET/PUT/DELETE /api/behavior-grades/{id}` (filter with
  `?studentId=` / `?subjectId=`) - Verhaltensnoten, independent of the academic grade above
- `GET /api/grade-scales`, `GET /api/grade-scales/{id}` (read-only, shared across all teachers)
- `GET /api/school-classes/{classId}/averages` - computed raw average + final grade for
  every student x subject combination in that class

## Web frontend

Notenfuchs ships a server-rendered HTML frontend (Quarkus Qute templates + HTMX for
partial updates, plus a small amount of vanilla JS for the grade grid's spreadsheet-style
keyboard navigation) - no React/SPA, no Node build step.

- `/` redirects to `/classes`
- `/classes` - list/create/delete school classes
- `/classes/{id}` - manage a class's subjects and students
- `/subjects/{id}` - manage a subject's grade categories and assessments ("Leistungen")
- `/subjects/{id}/grid` - the grade-entry grid: students as rows, assessments as columns
  (grouped by category), each cell autosaves on blur/navigate-away via a small `fetch()`
  call, with a live per-student average column computed by `GradeService`
- `/subjects/{id}/grid/export` - downloads the same grid as an `.xlsx` workbook
  (Apache POI), for teachers who want the grades outside the app
- `/classes/{id}/roster/export` - downloads that class's student names as CSV
- `/classes/{id}/roster/import/preview` and `/classes/{id}/roster/import` - upload a CSV
  of student names, preview which rows are new vs. already-existing (by exact name match),
  then confirm to create the new students
- `/classes/{id}/behavior-grid` - the Verhaltensnoten grid: students as rows, every Fach
  of the class as columns, for entering a behavior/conduct grade per student per Fach.
  Independent of `GradeService`/the academic average - it's its own figure on the
  Halbjahres-/Endjahreszeugnis. Shows a live per-Fach average (own scale, rounded final
  grade) and a per-student average across all their Fächer (raw only, since Fächer may
  use different scales), highlighted when close to a whole-grade rounding boundary
  (e.g. 2.4-2.6, near 2.5)

### Roster CSV format

A "roster" is just a class's list of student names, one per line under a `Name` header.
Handled by `CsvRosterService` (`de.notenfuchs.service`), a pure/DB-free service - like
`GradeService` - so it's unit-tested without a database.

- **Export** always writes UTF-8 with a BOM and a **semicolon**-delimited `Name` column,
  so German-locale Excel opens umlauts correctly out of the box without a manual encoding
  prompt.
- **Import** is tolerant of what real German Excel exports actually look like: it sniffs
  the delimiter (`;` vs `,`) from the header line, decodes UTF-8 (with or without a BOM)
  and falls back to Windows-1252 if the bytes aren't valid UTF-8, and accepts both CRLF and
  LF line endings. A `Name` header (case-insensitive) is recognized and dropped if present.
  A header with separate `Vorname`/`Nachname` columns (case-insensitive, any position, any
  other columns such as `Alter`/`Klasse`/`Geburtsdatum` ignored) is also recognized and the
  two columns are joined with a space into the full name - the shape many school-admin
  systems export. Without either recognizable header, every line is treated as a name.
  Blank lines are skipped, names are trimmed.
- Import is a two-step, stateless flow: uploading a CSV renders a **preview** page marking
  each row NEW or DUPLICATE (against the class's existing students, exact name match) before
  anything is written to the database. The preview's confirm form carries the parsed names
  back as hidden inputs rather than relying on a server-side session, so the confirm request
  is self-contained. Confirming creates a `Student` per new name and skips duplicates
  (including duplicates within the uploaded file itself).

**HTMX is self-hosted**, not loaded from a CDN. `htmx.min.js` (v1.9.12) is vendored at
`src/main/resources/META-INF/resources/static/js/htmx.min.js` and served from
`/static/js/htmx.min.js` via the `<script>` tag in `templates/base.html`. To upgrade,
replace that file with a newer `htmx.min.js` from https://htmx.org and update the version
noted here.

## License

MIT, see [LICENSE](LICENSE).
