# Teacher use cases

What a teacher actually wants to get done with Notenfuchs, grouped by how often it happens.
Each use case notes how it works today and where it hurts. "Click path" counts page loads,
not every keystroke.

Roles used below:
- **Fachlehrer** – teaches one or more Fächer in a class (`SubjectTeacher`).
- **Klassenlehrer** – runs a class: roster, Verhaltensnoten, Zeugnis preparation (`ClassTeacher` ADMIN).
- **Kollegium admin** – runs the instance (Pocket ID accounts, backups). Not a teacher role in the app.

## Daily / weekly

### U1 – Enter oral grades (Mitarbeit) after a lesson
A handful of students got a mündliche Note today. The teacher wants to note them in under a
minute, often between lessons, sometimes on a phone or tablet.
- Today: Übersicht → Fach card (grade table) → find the Mitarbeit column → type. Fast *if* a
  fitting Leistung column already exists.
- Pain: if the teacher grades Mitarbeit per lesson or per week, they first need a new Leistung,
  which can only be created on the Fach settings page (leave the grid, add, come back). The grid
  is not usable on a phone.

### U2 – Grade a written test (Klassenarbeit, Test, Referat)
The teacher has corrected a stack of tests and enters a result for every student, often as
points with a Notenschlüssel.
- Today: Fach card → grade table has no column yet → breadcrumb to the Fach page → add the
  Leistung in the right category (name, date, factor, points mode) → adjust the Notenschlüssel
  there → "Zur Notenerfassung" → type points top to bottom. About 4 page loads before the first
  grade.
- Pain: setting up a column happens far away from where the grades go. The Notenschlüssel is
  edited without seeing the class's points next to it.

### U3 – Correct a wrong grade
A student points out a mistake, or the teacher mistyped.
- Today: Fach card → find cell → overwrite. Good.

### U4 – Note that a grade is missing for a reason
A student was absent and must write the test later (Nachschreiber), or is excused.
- Today: the cell stays empty. An empty cell means both "not entered yet" and "will not get a
  grade", so the teacher has to remember who is still open.

### U5 – Look at one student
Before a parent conversation or a student asks "Wie stehe ich?".
- Today: grade table of one Fach, read the student's row. Across Fächer only the Klassenlehrer
  has the Notenübersicht, which shows only final grades.
- Pain: no single view of "this student in my Fach", e.g. with Leistungen, dates and both
  half-year averages in a readable list.

### U6 – Switch between my Fächer
A teacher with 6–10 Fächer moves from 7a Mathe to 9c Physik.
- Today: back to Übersicht → next card. Acceptable since "Meine Fächer" exists.

## Per term

### U7 – Prepare Halbjahres-/Jahreszeugnis grades
Each Fachlehrer delivers the final grade per student.
- Today: grade table shows H1/H2/Jahr averages and final grades; xlsx export exists.
- Pain: no hint which students still lack grades in a half (see U4), so the teacher checks by eye.

### U8 – Enter Verhaltensnoten
Each Fachlehrer gives one behavior grade per student in their Fach.
- Today: class → tab Verhaltensnoten. Works, but it is not reachable from the Fach the teacher
  works in, and nothing shows what is still missing.

### U9 – Klassenlehrer: check the class before the Notenkonferenz
Who is at risk (5/6)? Did every Fachlehrer deliver? Are Verhaltensnoten complete?
- Today: tab Notenübersicht shows final grades; at-risk students must be spotted by eye; no
  completeness check.

### U10 – Add or remove a student mid-year
A student joins or leaves the class.
- Today: class → Fächer & Schüler → add/delete. Works. Deleting removes all of the student's
  grades, which is right for a mistake, but wrong for a student who leaves mid-year if the
  teacher still needs their grades for a report.

## Once per school year

### U11 – Set up a new class
New Klasse 5a: create the class, import the roster from the school administration's CSV
export, create the Fächer.
- Today: Übersicht → create class → class → CSV import with preview → create each Fach.
- Pain: every new Fach starts with no categories. The grade table is empty until the teacher
  goes to the Fach page and creates categories and weights – usually the same ones as in their
  other Fächer.

### U12 – Carry a class into the new school year
8b becomes 9b.
- Today: class → tab Einstellungen → "Ins neue Schuljahr übernehmen". Good.

### U13 – Set the half-year date and display
- Today: class → tab Einstellungen. Fine, once a year.

### U14 – Share a Fach or a class with a colleague
Team teaching, a Referendar, a substitute teacher.
- Today: Fach page → Lehrkräfte → pick from a list of everyone who has ever logged in. Works
  only after the colleague logged in once.

## One-off / rare

### U15 – Get a new colleague started
Kollegium admin sends a Pocket ID signup link; the colleague signs up and needs to land in
Notenfuchs. Solved by setting Pocket ID's home page (`HOME_PAGE_URL`) to Notenfuchs; see the
README's Pocket ID steps.

### U16 – Export grades for the school system or a personal backup
- Today: xlsx export per Fach. Nothing per class.

### U17 – "What does the student need in the next Klausur for a 2?"
On the roadmap ("Later / maybe"). Students ask this a lot before the last test of a half.
