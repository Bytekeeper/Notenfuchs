# Notenfuchs

Open-source grade-management tool for teachers (Klassen, Fächer, Noten). Focus is **grade entry and grade calculation** done well — that's the gap in existing tools. German context, self-hosted.

## Tech stack

Quarkus 3.15.1 · Java 17+ · Hibernate ORM with Panache · RESTEasy Reactive (quarkus-rest) + Jackson · PostgreSQL · Flyway · Hibernate Validator · JUnit 5. Postgres runs via Docker Compose. Group id `de.notenfuchs`.

The Maven Wrapper (`./mvnw`) is committed — **no local Maven install needed**, but Java 17+ is required (Quarkus 3.15 minimum).

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
- `service/` — `GradeService` + its plain data carriers (`CategoryData`, `GradeData`, `SubjectAverageResult`)
- `rest/` — one REST resource per entity + `ClassAveragesResource` for computed averages
- `dto/` — request/response records
- `web/` — server-rendered UI: one Qute/HTMX resource per entity (`ClassUiResource`, `SubjectUiResource`) + `GradeGridResource` for the grade-entry grid. Templates live in `src/main/resources/templates/`; the grid's keyboard/autosave JS is `static/js/notenfuchs.js`.

### Data model
```
SchoolClass   → Student (name is free-text; displayName = optional Kürzel/pseudonym)
SchoolClass   → Subject (has a GradeScale + roundingMode)
Subject       → GradeCategory (weightPercent, e.g. Schriftlich 50 / Mündlich 50)
GradeCategory → Assessment (a Leistung; factor, e.g. 2.0 = "counts double")
Assessment    → Grade (student_id, value NUMERIC(4,2))
GradeScale    (min, max, lowerIsBetter) — seeded "DE 1-6" via Flyway
```

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

## Conventions & gotchas

- **Flyway owns the schema**, not Hibernate. `quarkus.hibernate-orm.database.generation=validate`. Schema changes go in a new `src/main/resources/db/migration/V*__*.sql` migration — never rely on auto-DDL.
- Grade-calc changes must come with tests in `GradeServiceTest` (covers factor weighting, category combination, empty-category normalization, both rounding modes at the .5 boundary).
- Free-text student names by design — the teacher decides what to enter (real name or Kürzel). No PII assumptions baked into the model.
- The grade-entry grid (`GradeGridResource` + `notenfuchs.js`) is a spreadsheet-style UI (students as rows, assessments as columns, tab/enter between cells, per-cell autosave) — the thing existing tools do badly, and the main reason to browser-test a change instead of trusting `GradeServiceTest` alone.
- UI/grid changes should come with a Playwright IT in `src/test/java/de/notenfuchs/e2e` (`GradeGridE2EIT`) — run via `./mvnw verify`, not `./mvnw test` (see Commands). These drive the real server-rendered pages through a browser (`quarkus-playwright`, Dev Services container - no local browser install needed) against a Testcontainers Postgres, so they need Docker. Each test creates its own uniquely-named class/subject/student rather than relying on a clean DB, since there's no reset between tests.

## Deployment note
Self-hosted on a VPS. Sensitive data (student names + grades) lives in Postgres. Recommended at-rest protection is a LUKS-encrypted volume for the DB (protects against snapshot/disk theft; not against a live host compromise). Pseudonymization via `displayName` is the cheapest strong safeguard.
