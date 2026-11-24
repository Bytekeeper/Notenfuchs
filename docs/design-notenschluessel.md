# Design: Notenschlüssel in steps

Status: built, with one change after testing with a real teacher (see "Update" at the end).
Printing is still open.

## Why

Today a points Leistung turns points into a grade by drawing straight lines between the
Notenschlüssel rows ("interpolation"): with 60 → 1 and 20 → 6, 50 points gives 2,3. Teachers
don't grade that way. In schools a Notenschlüssel is a list of steps: "ab 34 Punkten eine 1,
ab 29 eine 2, …". The Fachkonferenz usually fixes it as a minimum percentage per grade, and the
teacher applies it to the maximum points of each test. The Excel sheets teachers use work the
same way: enter the maximum points, get the full list of steps.

The research found many requests for reusable percentage keys (TeacherStudio: 587 votes) and none
for interpolation. Interpolation also produces grades like 2,4 that a teacher can't write under
a test.

## What the teacher sees

**Leistungen & Gewichtung tab:** a new card "Notenschlüssel" for the Fach. One row per grade with
a minimum percentage:

| Note | ab % |
|---|---|
| 1 | 87,5 |
| 2 | 75 |
| 3 | 62,5 |
| 4 | 50 |
| 5 | 25 |
| 6 | (der Rest) |

The worst grade has no row; it is whatever is below the last step.

**Leistung dialog, with "Punktebasiert" ticked:**

1. A required field "Höchstpunktzahl".
2. Below it, the steps in points, filled from the Fach's percentages:
   "1 ab 35 Punkten, 2 ab 30, …". The teacher can change a single step for this test only
   (for example lower the 4 by one point after a hard test), or add a row such as "2+ ab 32".
3. "Schlüssel des Fachs neu anwenden" refills the steps from the percentages, for example after
   the teacher changed the maximum points.
4. The existing Notenspiegel preview stays: it shows what the class would get with these steps.

The "Rundung (bei Punkten)" choice goes away: a step gives an exact grade, so there is nothing to
round.

## How it works

- A grade comes from the highest step whose points the student reached ("ab" means "at least").
  Below every step: the scale's worst grade. Points above the maximum (bonus points) are allowed
  and give the best grade.
- A step's grade can be any value `GradeScale#parseGrade` accepts, so tendency rows work. The
  default key has only whole grades.
- Percent → points: the percentage of the maximum, rounded down to half points, so rounding never
  makes a grade harder to get. Steps the teacher types are also half points.
- Points stay the stored value; the grade is still derived on every read. Changing a step or the
  maximum changes the grades at once. Nothing is frozen.
- The Fach's percentages are only a template. A Leistung stores its own steps in points, so
  changing the Fach key later does not change tests already written: a returned test keeps its
  grade.
- Scale-agnostic: the rows come from the scale (`min`, `max`, `lowerIsBetter`). Until the
  teacher saves a Fach key, a default is computed and not stored: the pass mark (the grade just
  before `atRiskFrom`) at 50 %, the better grades spread evenly up to 100 %, the worse ones evenly
  down to 0 %. For DE 1-6 this gives the table above. A scale without `atRiskFrom` spreads all
  grades evenly.

There is one calculation: interpolation is removed. `PointsConversionService#convert` becomes a
short loop over the steps.

## Data

- `assessment.max_points` (new): required for points Leistungen.
- `points_grade_band` keeps its columns; a row now means "ab `points` gives `grade_value`".
- New table `subject_points_key (subject_id, grade_value, min_percent)`. Empty means "use the
  computed default". "Ins neue Schuljahr übernehmen" and copying the setup from another Fach copy
  it.
- `assessment.rounding_mode` is dropped; it only served the interpolation. This removes the field
  from the REST API's Assessment too (a breaking API change to mention in the release notes).

## Migrating existing points Leistungen

All existing points Leistungen are converted. Their grades change from one decimal (2,3) to whole
grades, so the release notes must say so, and teachers should check their points tests after the
update.

The conversion keeps what the old line meant as closely as steps can:

- The step for a grade is where the old line crossed the midpoint to the next worse grade (for
  example the 2 starts where the line passed 2,5), rounded down to half points. So every student
  gets the whole grade their old value rounded to.
- `max_points` = the highest old row's points. The teacher can correct it; the steps don't depend
  on it.

This is a Flyway Java migration (`V14__ConvertNotenschluesselToSteps`); in SQL the segment lookup
per grade was hard to read.

## Notenspiegel: share below "ausreichend"

The share belongs to the boundary between two grades, so it is drawn as a narrow column between
two grade columns: between "4" and "5" it shows "32 %" (the share of 5 and 6). Only the boundary
at the scale's `atRiskFrom` is shown, because the state rules (Hessen ⅓, Niedersachsen about 30 %)
ask for that one. The header names the grades it counts, from `atRiskFrom` to the worst grade:
"5–6". This also works for scales where higher is better. A scale without `atRiskFrom` gets no
column. It applies to the Zeugnisnoten rows too.

## Second step: printing

A print button for one Leistung: its Notenspiegel row and its steps as point ranges
("1: 40–35 Punkte, 2: 34,5–30 …") for under the test and for the Fachleitung.

## Not planned

- "Points for a 1, the rest follows" (the Excel way) as its own control: the teacher edits the
  1 step instead. Build it if someone asks.
- Percentage keys per teacher across classes.

## Docs and tests when built

- Unit tests for the step function (exact threshold, half points, bonus points, a scale where
  higher is better, a tendency row) and for the default key.
- A test for the migration: a Leistung with the old default 60 → 1, 20 → 6 gets the expected
  steps.
- HTTP-level IT: a points Leistung needs maximum points; the share column.
- Playwright: the dialog fills the steps from the maximum, one step changed, "neu anwenden".
- CLAUDE.md "Points are never converted and stored" rule, the guide page
  `03-klassenarbeit-mit-punkten.md` and its screenshots, the README.

## Update: sliding keys stay

A teacher uses a sliding key in percent: 100 % → 1,0, 50 % → 3,5, 6 somewhere above 0 %, with
one-decimal grades in between. So the built version keeps both readings:

- A key (Fach or Leistung) is free rows of grade and percent/points, plus a switch "Gleitend".
  Steps: the grade holds from its row on. Sliding: linear between rows, extended beyond the outer
  rows, clamped at the scale, rounded to one decimal in the student's favor (no rounding choice).
- The Fach key no longer needs one row per whole grade. The default is sliding with two rows: the
  best grade at 100 % and the scale's middle at 50 % (1,0 and 3,5), one straight line to the worst
  grade at 0 %. Teachers build exactly this in Excel; a Fachkonferenz step key is one untick away.
- D1 is reversed: existing points Leistungen become sliding with their old rows, so no grade
  changes and no conversion migration. Their maximum is their highest row. Only the dropped
  rounding choice can move a "kaufmännisch" Leistung's grade by 0,1.
- Display: a Fach can show tendency grades as "2,3" instead of "2-" (`Subject#tendencySigns`).
