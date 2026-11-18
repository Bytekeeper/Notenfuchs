# Notenfuchs

Self-hosted grade-management tool for German teachers (Klassen, Fächer, Noten). The focus is grade entry and grade calculation done well.

Language: teacher-facing docs (README and similar) in **German**; everything developer-facing (this file, code, comments, commits) in **English**.

## Stack & commands

Quarkus 3.40 · Java 17+ · Hibernate ORM Panache · quarkus-rest + Qute + htmx · PostgreSQL + Flyway · quarkus-oidc / elytron properties-file auth · Apache POI (xlsx) · FastCSV · quarkus-playwright. Maven Wrapper is committed.

```bash
./mvnw test                      # unit tests, no DB
./mvnw verify                    # + HTTP-level and Playwright ITs (needs Docker)
./mvnw verify -Dit.test=X -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false   # one IT class
./mvnw verify -Dit.test=WalkthroughIT -Dscreenshots=true   # regenerate guide + README images
./mvnw quarkus:dev               # needs `docker compose up -d postgres` first
```

## Commits

Conventional Commits (`feat(grid): …`, `fix(csv): …`), body 2–3 sentences on the why.

## Finishing a feature or change

Before calling it done, do both checks and report the result:
1. **Best practices** against the rules below: access via `OwnershipGuard`, Flyway migration for schema changes, scale-agnostic grade logic that reuses `GradeService`, no controls rendered that the viewer can't use, comments say why, no dead code or debug output, no class growing into a grab bag (split by page or concern).
2. **Coverage**: each new or changed behavior has a test where it can break: pure calculation → unit test, endpoint rules and access → HTTP-level IT, htmx/`notenfuchs.js` flows → Playwright IT. Real bugs get a regression test that fails without the fix. Never aim for 100 %; skip simple wiring, prefer a few telling samples.

Then run `./mvnw verify`, regenerate screenshots if the UI changed (commit only real diffs), and update this file, the README and the user guide (`docs/anleitung/`).

## Code map (`src/main/java/de/notenfuchs/`)

- `domain/` Panache entities. `service/` pure, DB-free logic: `GradeService`, `PointsConversionService`, `HalfYearAssessmentPartitioner`, `HalfYearGradeDisplayService`, `BehaviorGradeService`, `RequiredGradeService`, `CsvRosterService`. `rest/` plain CRUD API per entity + `ClassAveragesResource`. `security/` `CurrentUser`, `OwnershipGuard`, auth setup, `TeacherDirectoryRecorder`, dev-only helpers.
- `web/` server-rendered pages:
  - `ClassUiResource`: home page ("Meine Fächer" + "Meine Klassen"), class tabs, roster CSV, students (incl. "ausgetreten"), subjects, class teachers, school-year duplicate.
  - `SubjectUiResource`: Fach setup tab and Fach teachers; owns the create/update rules for a Leistung (`createAssessment`/`updateAssessment`).
  - `GradeGridResource`: grade grid (classic and half-year view), cell save, xlsx export, the Leistung dialog endpoints (add/edit/delete, Notenschlüssel, `band-preview`), starter categories.
  - `StudentPageResource`: one student in one Fach, incl. "Was braucht …".
  - `BehaviorGridResource`: Verhaltensnoten grid. `ClassOverviewResource`: admin-only Notenübersicht. `OpenGrades`: the "offen" rule and counts.
  - `ClassExportResource`: class-wide xlsx. Admins get a "Notenübersicht" sheet (final grades of every Fach); everyone gets a full sheet per Fach they teach, written by `GradeGridResource#addSubjectSheet` (the same code as the Fach export). It never widens access.
- Templates in `src/main/resources/templates/`; tab headers `ClassPage/classHeader.html` and `SubjectPage/subjectHeader.html` (every tab page passes `activeTab`; class pages also `isClassAdmin`, Fach pages `switchSubjects`). Grid JS: `static/js/notenfuchs.js`.

## Data model

```
SchoolClass → Student (name free text, displayName = optional Kürzel, leftOn = date left or null)
SchoolClass → Subject (GradeScale, roundingMode) → GradeCategory (weightPercent) → Assessment (= Leistung: factor, date, pointsBased + own roundingMode)
Assessment  → Grade (per student: value OR points OR neither, plus optional absence EXCUSED/UNEXCUSED)
Assessment  → PointsGradeBand (points → gradeValue: the Notenschlüssel)
Student + Subject → BehaviorGrade (one Verhaltensnote per student per Fach)
SchoolClass → ClassTeacher (ADMIN | FACHLEHRER) ; Subject → SubjectTeacher ; Teacher = directory (subject + name) of everyone who logged in
SchoolClass: halfYearCutoff, halfYearGradeDisplay WHOLE/HALF + tendency threshold, predecessorClass (informational)
GradeScale (min, max, lowerIsBetter, atRiskFrom) — shared reference data, seeded "DE 1-6"
```

## Rules that must hold

- **Scale-agnostic.** Grades are `BigDecimal` (`NUMERIC`), never a 1–6 enum. Read `lowerIsBetter` (and `atRiskFrom`) from the scale; never assume 1–6. No `double`.
- **One calculation.** `GradeService` (pure POJO) does all averaging: category average weighted by factor; subject average by `weightPercent`, normalized over categories that have grades; raw average + final grade per `COMMERCIAL` (half up) or `IN_FAVOR_OF_STUDENT` (half toward the better grade). Other features feed it filtered data instead of copying the math (half-year averages, `RequiredGradeService` tries candidate grades through it, the Notenspiegel preview).
- **Points are never converted and stored.** A points-based grade is derived live by `PointsConversionService` (linear interpolation between bands, extrapolated beyond, clamped only at the scale; rounded to one decimal per the Leistung's rounding mode). New points Leistungen get default bands 60 → best, 20 → worst. Switching points mode clears grades but keeps absence marks.
- **No freezing.** No locks, archives or snapshots. Half-year cutoff and half-grade display only change how the same data is shown. "Jahr" is always a whole grade. Verhaltensnoten have no H1/H2. "Ins neue Schuljahr übernehmen" copies Fächer, categories and active students, never Leistungen or grades; the source class stays editable.
- **Absence mark** (`e`/`u` typed in a cell, alone or next to the grade) never changes a grade and never derives one. A marked cell is not "offen". xlsx keeps grades numeric and writes a mark without grade as its letter.
- **"Offen"** = empty cell of a Leistung whose date has come (`OpenGrades#isDue`; undated never). Highlighted in the grid; counted on the home page and in the Notenübersicht. No per-column count in the grid footer.
- **Students who left** (`leftOn`): grades kept, shown read-only below the active rows (grids, overview, class page list), excluded from column averages, "offen" counts, at-risk summary, roster export and the school-year copy; cell saves refuse them (400). xlsx lists them last, marked "(ausgetreten)".

## Access model

- **Authentication:** exactly one of local password (`NOTENFUCHS_PASSWORD`, fixed user `lehrer`, FORM auth) or OIDC (`OIDC_ISSUER_URL`). `LocalAuthConfigSource` decides, and pins the auth mechanism to `form` or `code` (not `oidc`); without the pin both mechanisms race and login breaks. Prod refuses to start with neither (`AuthConfigurationCheck`). `%dev`/`%test` permit everything and act as `dev-user`.
- **Every path is authenticated** by the global policy in `application.properties`; don't add `@PermitAll`.
- **Tiers:** `ClassTeacher` ADMIN (roster, settings, teachers, delete any Fach, Notenübersicht) or FACHLEHRER (add Fächer, delete own). `SubjectTeacher` gates everything inside a Fach (categories, Leistungen, grades), with **no admin override**, and is self-service: any current teacher of the Fach can add/remove colleagues. Plain class access (roster read, Verhaltensnoten view) is derived: any ClassTeacher row or teaching one Fach of the class.
- **`OwnershipGuard` is the only gate.** Resolve every entity by id through its `require*` methods (never raw `findById`), list via its scoped queries (never `listAll()`), pass `CurrentUser.effectiveSubject()`. Foreign and unknown ids both 404. New class/subject-scoped entities get a new `require*` method. Endpoints that take two ids (e.g. Fach + Leistung) also check they belong together.
- **Don't render dead controls.** Templates hide what the viewer can't do (`isClassAdmin`, `isClassTeacher`, `taughtSubjectIds`, `adminClassIds`), and every htmx fragment response must pass the same flags again (templates aren't type-checked, so nothing forces it).
- **No email addresses.** OIDC requests only `profile`; teachers are shown by name (`name`, then `preferred_username`, then the subject). Don't add the `email` scope or store addresses; the Pocket ID overlay sets `REQUIRE_USER_EMAIL=false`.
- **Feedback** ("Fehler melden" in `base.html`, `FeedbackResource`, table `feedback`) stores only the page type (`FeedbackResource#pageType` replaces ids server-side), the message and the time; never who sent it or anything shown on the page. Instance admins read it at `/admin/feedback` (`AdminFeedbackResource`, 404 for everyone else).
- **Instance admin** (`CurrentUser#isInstanceAdmin`, separate from the per-class ADMIN role): the local `lehrer` user, or with OIDC a member of `notenfuchs.admin-group` (`NOTENFUCHS_ADMIN_GROUP`, default `notenfuchs-admins`; the `groups` claim of the ID token becomes the roles, scope `groups` via `OIDC_SCOPES`). Without a login (%dev/%test) `notenfuchs.dev-instance-admin` decides; `AdminFeedbackNonAdminIT` turns it off.
- Dev only: `DevTeacherSeeder` adds `dev-user` and a colleague; `/dev/identity` switches identity via a cookie that `CurrentUser` honors only in `%dev`.

## Gotchas

- **Flyway owns the schema** (`schema-management=validate`); every change is a new `V*__*.sql`.
- **Lazy associations in Qute:** a template rendered from an `@Transactional` endpoint runs after the session closed; `Hibernate.initialize(...)` what it reads (see `SubjectUiResource#categoryFragment`).
- **JAX-RS paths:** two resources may share a prefix (`/subjects/{id}/grid` vs `/subjects/{id}/grid/students`); the longer literal path wins. `@FormParam` for an optional id must be a `String`: an empty select value doesn't convert to `Long`.
- **`TeacherDirectoryRecorder`** observes `AuthenticationSuccessEvent` on the event loop: read identity from the event itself and write via `ManagedExecutor` + `QuarkusTransaction.requiringNew()` (no `@Transactional`/`@Blocking` there). It never fires under `%test`'s permit-all; tests seed `Teacher` rows directly.
- **HTTP-level ITs** use `web/TestHttp` (`private final TestHttp http = new TestHttp(() -> rootUrl);`, then `http.get/post/patch/delete/getBytes`); don't copy request helpers into test classes.
- **ITs reuse one Hibernate session per test method:** an entity loaded earlier in the test stays cached after an HTTP request changed it. Re-read inside `QuarkusTransaction.requiringNew()`.
- **Playwright:** each test creates uniquely named data (no DB reset). After an htmx submit, wait for the swap before the next fill. Wait for `NETWORKIDLE` before a reload that must see saved cells. `getByRole` names match substrings; use `setExact(true)` or a scoped locator (e.g. `.tabs`) when labels overlap ("Anlegen", "Noten…").
- **`WalkthroughIT` is the user guide's test.** One ordered test per journey, one German page per journey in `docs/anleitung/` (same order). Each journey checks its page both ways: every **„label“** quoted there must appear on a page it visited, and the page must reference exactly the images it takes. With `-Dscreenshots=true` (run the class alone) it writes `docs/anleitung/bilder/` and the README's `screenshots/`: fixed names and dates, CSS transitions off, a wait for the save flash, PNGs re-encoded so an unchanged UI gives byte-identical files. Keep values that change daily (e.g. "ausgetreten seit") out of images. When a UI change breaks it, update the guide text too.
- **Roster CSV** must handle German Excel: `;` delimiter, Windows-1252 fallback; export is UTF-8 with BOM. Import is preview → confirm, stateless.
- **versions-maven-plugin:** `processDependencyManagement=false` and the enforcer's Maven 3.9.0 floor keep its reports readable; `showVersionless=false` crashes the plugin, don't add it.

## Test map

| Area | Tests |
|---|---|
| Averages, rounding | `GradeServiceTest` |
| Points / Notenschlüssel | `PointsConversionServiceTest`, `GridAssessmentIT`, `GradeGridE2EIT` |
| Half-year view and display | `HalfYearAssessmentPartitionerTest`, `HalfYearGradeDisplayServiceTest`, `GradeGridHalfYearIT`, `GradeGridHalfYearE2EIT` |
| Leistung dialog, "offen" highlight | `GridAssessmentIT`, `GradeGridAssessmentE2EIT` |
| Absence mark | `GradeAbsenceIT` |
| Students who left | `StudentLeftIT`, `StudentLeftE2EIT` |
| Student page, "Was braucht …" | `RequiredGradeServiceTest`, `StudentPageIT` |
| Home page, Fach switcher | `MySubjectsIT`, `SubjectSwitcherE2EIT` |
| New Fach setup (copy, starter) | `SubjectSetupIT` |
| Notenübersicht, at-risk | `ClassOverviewIT`, `GradeScaleTest` |
| Verhaltensnoten | `BehaviorGradeServiceTest`, `BehaviorGridIT`, `BehaviorGridE2EIT` |
| Access rules | `OwnershipGuardIT`, `SubjectStructuralAccessIT`, `CurrentUserTest` |
| Class / Fach teachers | `ClassTeacherIT`, `ClassTeacherE2EIT`, `SubjectTeacherIT`, `SubjectTeacherE2EIT`, `SubjectTeacherDisplayIT`, `TransitiveTeacherIT`, `TeacherDirectoryRecorderIT` |
| Class export | `ClassExportIT` |
| School-year copy | `ClassDuplicationIT`, `ClassDuplicationE2EIT` |
| Roster CSV | `CsvRosterServiceTest`, `RosterImportExportE2EIT` |
| User guide flows | `WalkthroughIT` |
| Feedback, instance admin | `FeedbackPageTypeTest`, `FeedbackIT`, `AdminFeedbackIT`, `AdminFeedbackNonAdminIT`, `CurrentUserTest`, `WalkthroughIT` (Alltag) |
| Login | `LocalAuthConfigSourceTest`, `AuthConfigurationCheckTest`, `LoginIT`, `LoginE2EIT` |

## Deployment

Self-hosted on a VPS; student names and grades live in Postgres. Recommend a LUKS-encrypted DB volume and pseudonyms via `displayName`. The Pocket ID overlay (`docker-compose.oidc.yml`) runs with `UI_CONFIG_DISABLED=true`, so Pocket ID settings (e.g. `HOME_PAGE_URL` from `NOTENFUCHS_APP_URL`) come from the compose file, not its admin UI.
