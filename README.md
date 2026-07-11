# Notenfuchs

Notenfuchs is an open-source grade-management tool for teachers, built with
[Quarkus](https://quarkus.io/). It lets a teacher organize school classes, students,
subjects, weighted grade categories (e.g. "Schriftlich" / "Muendlich"), individual
assessments, and grades - and computes weighted per-student, per-subject averages
and rounded final grades automatically.

## Running Notenfuchs

### Option A: full stack with Docker Compose

This starts both PostgreSQL and the packaged application.

```bash
./mvnw package
docker compose up --build
```

The app builds a JVM-mode container image from `src/main/docker/Dockerfile.jvm`,
so `./mvnw package` needs to be run once beforehand to produce `target/quarkus-app/`.
The API is then available at `http://localhost:8080`, and PostgreSQL at `localhost:5432`
(database `notenfuchs`, user/password `notenfuchs`).

To stop everything: `docker compose down` (add `-v` to also drop the database volume).

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

Notenfuchs secures the whole application (all UI pages and all `/api/*` endpoints)
with [OIDC](https://openid.net/developers/how-connect-works/) using the
`quarkus-oidc` extension in **web-app mode**: a standard authorization-code flow
with a server-side session cookie, suitable for the server-rendered HTML (Qute +
HTMX) frontend - not a token-based SPA setup. It is provider-agnostic and works
with any standards-compliant OIDC provider (Clerk, Keycloak, Authentik, Auth0, ...).

### Required environment variables (production)

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

### `%dev` / test bypass

`quarkus.oidc.tenant-enabled` is set to `false` for the `dev` and `test` profiles
(`%dev.quarkus.oidc.tenant-enabled=false`, `%test.quarkus.oidc.tenant-enabled=false`;
note this is `tenant-enabled`, not the build-time `enabled` switch - the latter would
remove the OIDC extension's CDI beans entirely, breaking `CurrentUser`'s unconditional
`@Inject` fields), and the blanket `authenticated` HTTP permission policy is relaxed to
`permit` for those same profiles. This means `./mvnw quarkus:dev` and `./mvnw test` both
run without a live identity provider and without login - exactly like before OIDC was
added. This bypass is **not** active in the default/production profile; do not rely on
it outside local development.

### Setting up Clerk

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

### User identity in code

`de.notenfuchs.security.CurrentUser` (request-scoped bean) exposes the logged-in
user's OIDC subject, email, and display name for use in REST resources or (later)
Qute templates - see the Javadoc on that class for details, including where a
future per-teacher data-scoping hook would attach.

## REST API

All endpoints are under `/api`:

- `GET/POST /api/school-classes`, `GET/PUT/DELETE /api/school-classes/{id}`
- `GET/POST /api/students`, `GET/PUT/DELETE /api/students/{id}` (filter list with `?schoolClassId=`)
- `GET/POST /api/subjects`, `GET/PUT/DELETE /api/subjects/{id}` (filter list with `?schoolClassId=`)
- `GET/POST /api/grade-categories`, `GET/PUT/DELETE /api/grade-categories/{id}` (filter with `?subjectId=`)
- `GET/POST /api/assessments`, `GET/PUT/DELETE /api/assessments/{id}` (filter with `?categoryId=`)
- `GET/POST /api/grades`, `GET/PUT/DELETE /api/grades/{id}` (filter with `?studentId=` / `?assessmentId=`)
- `GET /api/grade-scales`, `GET /api/grade-scales/{id}` (read-only)
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
  LF line endings. A `Name` header (case-insensitive) is recognized and dropped if present;
  otherwise every line is treated as a name. Blank lines are skipped, names are trimmed.
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
