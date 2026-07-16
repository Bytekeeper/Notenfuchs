# Notenfuchs — Roadmap

Mission: **help teachers help students** by doing grade entry and grade
calculation better than existing tools. Self-hosted, open source. Monetization
(if ever) only via hosting/donations — never a driver of scope.

Guiding principle for scope: stay in the "grades done well" lane. Deliberately
**not** a Klassenbuch/LMS (no attendance, no timetables, no messaging).

Design principle (learned the hard way): **never freeze a computed value to
"preserve history".** A mis-entered grade must always stay correctable and every
average recomputes live. If a change trail is ever needed, that's an append-only
**audit log** (who/when/old→new) that never blocks the live value — an audit
concern, not an ACL/locking one. (This is why the earlier "Term/snapshot"
concept was removed.)

## Deployment model
Self-hosted, **one instance per school/Kollegium** (or per teacher for solo
use). The instance *is* the tenant boundary — no in-app multi-tenancy to build.

## Done
- Spreadsheet-style grade-entry grid (keyboard nav, per-cell autosave).
- Scale-agnostic grade calculation (`GradeService`): factor weighting, category
  combination, empty-category normalization, two rounding modes.
- xlsx export (Apache POI).
- Roster CSV import/export (German Excel dialect: semicolon + Windows-1252 in,
  UTF-8+BOM out; two-step import with preview).
- OIDC login as an optional alternative auth mode (Pocket ID as the recommended
  lightweight, passkey-only provider) — see the built-in-auth entry below for
  the default path.
- Per-teacher ownership (single-tenant data isolation via `OwnershipGuard`).
- One-command onboarding (compose prints a one-time sign-up link).
- Copy class into a new school year (class clone): "Ins neue Schuljahr übernehmen"
  on the class detail page creates a new `SchoolClass` (teacher supplies the new
  name + school year), copying Subjects + their GradeCategories and the Students
  as an editable starting roster. Assessments/Grades are NOT copied — fresh start.
  The source class stays completely normal and editable (no locking/archiving).
  A nullable `predecessorClass` link (via Flyway migration, `ON DELETE SET NULL`)
  traces the copy back to its source for future trend features.
- **Notenschlüssel (points → grade per Leistung):** an `Assessment` can be marked
  points-based (no upfront "max points" — just a freely editable list of
  `PointsGradeBand`s, each an absolute points anchor, seeded with two starting
  anchors: 60 → best grade, 20 → worst grade on the subject's actual
  `GradeScale`). The grid accepts raw points and shows the derived grade live
  (e.g. "65 → 1"), **linearly interpolated** between the two bracketing anchors
  rather than jumping at each threshold — two anchors alone already produce a
  smooth grade across the whole range; more bands let the teacher bend that
  line into a non-linear curve. The grade itself is never stored -
  `PointsConversionService` (pure, unit-tested) recomputes it from the stored
  points on every read, so editing the points or the key always updates every
  average that depends on it. `Grade` stores either `value` or `points`, never
  both (DB `CHECK` constraint).
- **Halbjahr split view:** a nullable `SchoolClass.halfYearCutoff` date, editable
  on the class detail page, is purely a display/query filter on the grade grid —
  no Halbjahr entity, no snapshot, no active-term switching. When set, the grid
  partitions each category's Leistungen by date ("kein Datum" only counts into
  the year figure, `date <= cutoff` → 1. Halbjahr, `date > cutoff` → 2. Halbjahr)
  and shows an "Ohne Datum" block (only if any undated Leistung exists), "1./2.
  Halbjahr" blocks each with their own live average column, and a final "Jahr"
  average over everything — identical to the pre-Halbjahr single view when the
  cutoff is null. `GradeService` itself is completely unaware of Halbjahr: a
  half's average is an ordinary `GradeService` call over a date-filtered subset,
  built via the new pure `HalfYearAssessmentPartitioner`. Mirrored in the xlsx
  export.
- **Turbo-fast self-host — built-in auth, no external IdP required:**
  `docker compose up` (app + Postgres, nothing else) → set `NOTENFUCHS_PASSWORD`
  → start grading at `/login`. Uses Quarkus' own embedded security realm
  (`quarkus-elytron-security-properties-file`) + built-in FORM authentication:
  one fixed username (`lehrer`), no user table, no registration, no
  password-reset UI, plaintext password from the env var (same trust level as
  `DB_PASSWORD`). `security/LocalAuthConfigSource` decides local-vs-OIDC purely
  from whether `NOTENFUCHS_PASSWORD` is set — never both active at once. OIDC
  stays available as an *optional* overlay (`docker-compose.oidc.yml`) for
  schools that already run central SSO; in production, if neither is
  configured, `security/AuthConfigurationCheck` fails the boot rather than
  serving grade data unauthenticated.

## Next

### 1. What-if / target-grade simulation
"What do I need in the next Klausur to reach a 2?" Directly student-helpful,
small, leverages the existing calculation engine.

## Later / maybe
- **Class collaboration + Klassenlehrer overview** — needs the Kollegiums-Instanz
  model (multiple teachers sharing one instance), which the per-teacher self-host
  focus does not assume. Deferred until that model is actually chosen. Would add a
  real authorization layer (class owned by the KL, subjects owned by their
  Fachlehrer, read-shared students, roles) plus a subjects × students overview
  with configurable cross-subject weighting (Subject `factor` → SubjectGroup
  `weightPercent`) and an at-risk signal (red cells + count of 5/6).
- Read-only student or parent share link (high PII surface — defer, design
  carefully).
- Subject/weighting templates to avoid re-entering category setups per class.
- Audit log (append-only change trail) — only if a real need appears; Postgres +
  backups suffice for v1.

## Deliberately out of scope
- In-app multi-tenancy (instance = tenant).
- Frozen/snapshotted grade results (see design principle above).
- Attendance / Klassenbuch / timetabling / messaging.
