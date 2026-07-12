# Notenfuchs

Open-source grade-management tool for teachers (Klassen, Fächer, Noten). Focus is **grade entry and grade calculation** done well — that's the gap in existing tools. German context, self-hosted.

## Tech stack

Quarkus 3.37.2 · Java 17+ · Hibernate ORM with Panache · RESTEasy Reactive (quarkus-rest) + Jackson · PostgreSQL · Flyway · Hibernate Validator · JUnit 5 · quarkus-oidc (auth) · Apache POI (poi-ooxml, xlsx export) · FastCSV (`de.siegmar:fastcsv`, roster CSV import/export) · quarkus-playwright (browser ITs). Postgres runs via Docker Compose. Group id `de.notenfuchs`.

The Maven Wrapper (`./mvnw`) is committed — **no local Maven install needed**, but Java 17+ is required.

## Commands

```bash
./mvnw test                    # unit tests (no DB needed — grade logic is pure)
./mvnw verify                  # also runs the Playwright browser ITs (needs Docker)
./mvnw quarkus:dev             # dev mode with hot reload (needs Postgres running)
./mvnw package                 # build
docker compose up postgres     # just the DB (for dev mode)
docker compose up --build      # full stack (app + Postgres)
```

App serves on `:8080`. Postgres connection is configured via env vars (`DB_USER`, `DB_PASSWORD`, `DB_URL`) in `application.properties`, defaulting to the docker-compose values.

## Architecture

### Package layout (`src/main/java/de/notenfuchs/`)
- `domain/` — Panache entities (the data model)
- `service/` — `GradeService` + its plain data carriers (`CategoryData`, `GradeData`, `SubjectAverageResult`); `CsvRosterService` + `RosterParseResult` for roster CSV (de)serialization
- `rest/` — one REST resource per entity + `ClassAveragesResource` for computed averages
- `dto/` — request/response records
- `web/` — server-rendered UI: one Qute/HTMX resource per entity (`ClassUiResource`, `SubjectUiResource`) + `GradeGridResource` for the grade-entry grid (also serves `/export` as an `.xlsx` download via Apache POI). `ClassUiResource` also serves the roster CSV endpoints (`/classes/{id}/roster/export`, `/roster/import/preview`, `/roster/import`) — see the README's "Roster CSV format" section. Templates live in `src/main/resources/templates/`; the grid's keyboard/autosave JS is `static/js/notenfuchs.js`.
- `security/` — `CurrentUser`, a request-scoped facade over the OIDC `SecurityIdentity`/`UserInfo`/`JsonWebToken` for reading the logged-in teacher's subject/email/display name; `OwnershipGuard`, the single point that enforces per-teacher data isolation (see Authorization below)

### Data model
```
SchoolClass   (ownerSubject = owning teacher's OIDC sub) → Student (name is free-text; displayName = optional Kürzel/pseudonym)
SchoolClass   → Subject (has a GradeScale + roundingMode)
Subject       → GradeCategory (weightPercent, e.g. Schriftlich 50 / Mündlich 50)
GradeCategory → Assessment (a Leistung; factor, e.g. 2.0 = "counts double")
Assessment    → Grade (student_id, value NUMERIC(4,2))
GradeScale    (min, max, lowerIsBetter) — seeded "DE 1-6" via Flyway, shared across all teachers (not owned)
SchoolClass   → SchoolClass (optional predecessorClass, set only by the "copy into a new school year" action below)
```

### Copy class into a new school year (`ClassUiResource#duplicate`)
The "Ins neue Schuljahr übernehmen" action on the class detail page (e.g. 8b → 9b) is an explicit **duplicate**, not an archive/lock: it creates a new `SchoolClass` (teacher supplies the new name + school year) owned by the current teacher, copying that class's Subjects (name, GradeScale, roundingMode), each Subject's GradeCategories (name, weightPercent), and Students (name, displayName) as a fresh, editable roster. Assessments and Grades are deliberately **not** copied — fresh start for the new year. The source class is completely untouched afterwards and stays a normal, editable class; there is no locking/archiving concept anywhere in this app (see ROADMAP.md's design principle). The source is resolved via `OwnershipGuard.requireOwnedClass`, same as every other endpoint. `SchoolClass.predecessorClass` (nullable, `ON DELETE SET NULL`) links the copy back to its source — purely informational (future trend features), never consulted for access control, and set to null rather than blocking/cascading if the predecessor is later deleted.

### Key design decision — scale-agnostic grades
Grade values are stored as `NUMERIC` (BigDecimal), **never** a 1–6 enum. The scale lives in `GradeScale`. This is deliberate: a future 0–15 Punkte scale can be added **without a schema change or any change to the calculation math**. When touching grade logic, keep it scale-agnostic — read `lowerIsBetter` from the scale, never assume 1–6 or "lower is better".

### Grade calculation (`GradeService`)
Per student, per subject:
1. **Category average** = mean of the student's grades in that category, each weighted by `assessment.factor`.
2. **Subject average** = category averages combined by `weightPercent`, normalized over the sum of weights of **only the categories that actually have grades** (an empty category must not distort the result).
3. Expose the **raw average** (2 decimals) *and* a **final grade** rounded per `roundingMode`:
   - `COMMERCIAL` — round half up (2.50 → 3)
   - `IN_FAVOR_OF_STUDENT` — round half toward the better grade (consults `lowerIsBetter`; for DE 1-6: 2.50 → 2)

Use `BigDecimal` throughout — no `double`. `GradeService` is a pure POJO service over in-memory data so it's unit-testable without a DB; keep it that way.

### Authorization
Every path is authenticated by default via a global HTTP permission policy in `application.properties` (`quarkus.http.auth.permission.authenticated.paths=/*`, `policy=authenticated`) — not per-endpoint `@RolesAllowed` annotations. Only `/auth-callback,/logout,/q/health/*,/favicon.ico,/static/*` are public. This means a new REST or web resource is protected automatically with no annotation required; don't add `@PermitAll` without a specific reason. OIDC (`quarkus-oidc`, web-app/authorization-code mode) backs the login; `CurrentUser` (`security/`) exposes the logged-in teacher's identity. The `%dev`/`%test` profiles disable the OIDC tenant and relax the policy to `permit` so local dev and `./mvnw test` don't need a live IdP — see the README's Authentication section for details.

Authentication only proves *who* is logged in; **per-teacher ownership** (single-tenant data isolation, no roles, no sharing) is a separate layer on top. `SchoolClass.ownerSubject` (the owning teacher's OIDC `sub`) is the ownership root — `Student`, `Subject`, `GradeCategory`, `Assessment` and `Grade` don't carry their own owner column, they're scoped by walking up to their `SchoolClass`. `GradeScale` is shared reference data and is *not* owned. `security/OwnershipGuard` is the single place this is enforced: every REST/web endpoint that reads or writes an entity by id must resolve it via one of `OwnershipGuard`'s `requireOwned*` methods (never a raw `findById`), and every list endpoint must go through `listOwnedClasses`/an equivalent scoped query (never `listAll()`). A foreign or unknown id both come back as 404 (`NotFoundException`), deliberately indistinguishable, so a teacher can't probe for the existence of another teacher's data. `CurrentUser.effectiveSubject()` — not `subject()` — is what call sites pass in: it falls back to the fixed `CurrentUser.DEV_USER_SUBJECT` ("dev-user") constant when there's no authenticated session, so ownership still works in `%dev`/`%test` where OIDC is disabled. New entities scoped by class must add a `requireOwned*` method to `OwnershipGuard` rather than checking ownership inline at the call site.

## Conventions & gotchas

- **Flyway owns the schema**, not Hibernate. `quarkus.hibernate-orm.database.generation=validate`. Schema changes go in a new `src/main/resources/db/migration/V*__*.sql` migration — never rely on auto-DDL.
- Grade-calc changes must come with tests in `GradeServiceTest` (covers factor weighting, category combination, empty-category normalization, both rounding modes at the .5 boundary).
- Free-text student names by design — the teacher decides what to enter (real name or Kürzel). No PII assumptions baked into the model.
- The grade-entry grid (`GradeGridResource` + `notenfuchs.js`) is a spreadsheet-style UI (students as rows, assessments as columns, tab/enter between cells, per-cell autosave) — the thing existing tools do badly, and the main reason to browser-test a change instead of trusting `GradeServiceTest` alone.
- UI/grid changes should come with a Playwright IT in `src/test/java/de/notenfuchs/e2e` (`GradeGridE2EIT`) — run via `./mvnw verify`, not `./mvnw test` (see Commands). These drive the real server-rendered pages through a browser (`quarkus-playwright`, Dev Services container - no local browser install needed) against a Testcontainers Postgres, so they need Docker. Each test creates its own uniquely-named class/subject/student rather than relying on a clean DB, since there's no reset between tests.
- Roster CSV import/export (`CsvRosterService`) has to cope with real German Excel output: semicolon (not comma) delimiter and Windows-1252 (not UTF-8) encoding. Export always writes the Excel-friendly dialect (UTF-8 with a BOM, semicolon-delimited); import sniffs the delimiter from the header line and falls back UTF-8 → Windows-1252 on decode failure. Import is two-step and stateless — upload renders a preview (new vs. duplicate per row, by exact name match) whose confirm form round-trips the parsed names as hidden inputs rather than a server-side session; only the confirm POST persists anything. Changes here need unit tests in `CsvRosterServiceTest` (delimiter/encoding/line-ending/header/quoting/round-trip) plus a Playwright IT (`RosterImportExportE2EIT`) if the web flow changes.
- Ownership changes (new endpoint scoped by class, new entity type) need coverage in `OwnershipGuardIT` (`src/test/java/de/notenfuchs/security`, a `@QuarkusTest` + `@TestTransaction` IT — needs Docker/Postgres, run via `./mvnw verify`) asserting cross-tenant isolation (a foreign id 404s, an owned id resolves), plus `CurrentUserTest` if `CurrentUser`'s fallback logic changes.
- Class duplication changes need coverage in `ClassDuplicationIT` (`src/test/java/de/notenfuchs/web`, a `@QuarkusTest` IT driving the real endpoint over plain `java.net.http.HttpClient` — no RestAssured dependency in this project) asserting exactly what gets copied (Subjects/GradeCategories/Students) versus what doesn't (Assessments/Grades), that both classes stay independently editable, and that a foreign source class 404s; plus a Playwright IT (`ClassDuplicationE2EIT`) if the web flow changes.

## Deployment note
Self-hosted on a VPS. Sensitive data (student names + grades) lives in Postgres. Recommended at-rest protection is a LUKS-encrypted volume for the DB (protects against snapshot/disk theft; not against a live host compromise). Pseudonymization via `displayName` is the cheapest strong safeguard.
