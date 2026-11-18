# Usability plan

How to let a teacher do every use case in [use-cases.md](use-cases.md) with little hassle.
Ordered by value: what teachers do most often comes first.

## Guiding ideas

1. **The grade table is the home of a Fach.** Teachers spend most of their time there (U1–U3,
   U7). Anything they need *while grading* belongs on that page or one click away from it, not on
   a separate settings page.
2. **Set up once, reuse often.** Category setups and Notenschlüssel repeat across Fächer and
   years. Copying beats retyping (U11).
3. **Show what is missing.** Teachers lose time checking by eye who has no grade yet (U4, U7–U9).
   The app knows; it should say so.
4. **Real pages, not hidden panels.** Like the class tabs: every view has its own address, the
   back button works, each page loads only its own data.
5. **No new concepts unless a use case needs one.** Every step below names the use cases it
   serves. Stay in the "grades done well" lane (see ROADMAP.md).

## Phase 1 – Fach pages: grade where you set up (U1, U2, U5, U6, U8)

The planned "subject views overhaul". Mirrors the class tabs.

**1.1 Fach tabs.** Header with breadcrumbs (Übersicht / 8b / Mathe) and tabs:
- *Noten* (`/subjects/{id}/grid`) – the grade table, default when opening a Fach.
- *Leistungen & Gewichtung* (`/subjects/{id}`) – today's Fach page (categories, Leistungen,
  Notenschlüssel).
- *Lehrkräfte* – today's Fach teacher section, moved out of the setup page.
- A link to the class's *Verhaltensnoten* tab, so U8 is reachable from the Fach.

**1.2 Add a Leistung from the grade table.** A "+ Leistung" control at the end of each category's
column group opens a small form (name, date, factor, points yes/no) right there. After saving,
the new column appears and the cursor sits in its first cell. This removes the detour in U1 and
U2 (about 4 page loads down to 0).

**1.3 Edit a Leistung from its column header.** Clicking a column header opens the same form for
rename, date, factor, delete – and, for points-based Leistungen, the Notenschlüssel.

**1.4 Notenschlüssel next to the points.** While editing a Notenschlüssel from the grade table,
the grade column updates live, so the teacher sees how their key turns this class's points into
grades (U2).

**1.5 Fach switcher.** A small "Meine Fächer" dropdown in the Fach header jumps directly to
another Fach's grade table (U6), without going back to Übersicht.

Open question: should the grade table show the per-category weights in the column group header
(e.g. "Schriftlich 50 %")? Cheap, and answers "why is the average like this?".

## Phase 2 – Show what is missing (U4, U7, U8, U9)

**2.1 Absence mark on a cell.** A mark next to the grade, not a replacement for it, with three
states: none (default), "abwesend entschuldigt", "abwesend unentschuldigt". This gives two
cases:
- *Absent, no grade:* the cell does not count in any average (as an empty cell does today),
  but it is shown as absent, so the teacher knows the gap is on purpose.
- *Absent, with a grade:* the grade counts as normal. The mark only records that the student
  was absent, e.g. a Nachschreiber's later result, or a 6 the teacher chose to give for an
  unexcused absence.

The mark never changes a grade by itself; whether an absence earns a 6 stays the teacher's
decision. This is the one new concept in this plan. It needs a migration: a nullable `absence`
enum column on `Grade` (`EXCUSED`/`UNEXCUSED`), and the `value`/`points` CHECK must allow a row
that has only the mark. The mark is a record of what happened and stays when a grade is
entered later. There is no separate "still open" state; it follows from the two fields: mark
without grade = Nachschreiber still pending, mark with grade = absent, grade given later.

**2.2 Missing-grade hints.** In the grade table, for each Leistung with a date in the past,
highlight empty cells and show "3 offen" in the column footer. Cells with an absence mark are not
"offen" and have no count of their own; the mark in the cell is enough. On the Übersicht, a "Meine Fächer"
card shows a small badge ("5 Noten offen").

**2.3 Zeugnis check per class (Klassenlehrer).** On the Notenübersicht tab: mark final grades of
5 or worse (at-risk signal from ROADMAP.md), and list per Fach how many students have no grade
in the current half, plus missing Verhaltensnoten. Read-only, no new data.

## Phase 3 – Faster setup (U11, U14)

**3.1 Copy a Fach's setup.** When creating a Fach, offer "Kategorien übernehmen von: [one of my
Fächer]". Copies categories and weights (not Leistungen, not grades) – the same rule the
school-year copy already follows. Covers the "Subject/weighting templates" ROADMAP item without a
separate template entity.

**3.2 Sensible default for an empty Fach.** If the teacher does not copy, a brand-new Fach's grade
table shows a short starter instead of the "Erst Kategorien anlegen" link: "Schriftlich 50 % /
Mündlich 50 % anlegen" as one button, or "selbst festlegen".

**3.3 Invite a colleague who has never logged in.** Today a colleague must log in once before they
can be picked. Possible later step: share by email address and attach the access on their first
login. Lower priority – the current workaround is "log in once, then tell me".

## Phase 4 – Student view (U5, U17)

**4.1 Student in my Fach.** Clicking a student's name in the grade table opens their page for
that Fach: every Leistung with date, grade and category, the half-year and year averages, and
"abwesend" marks. Printable, for parent conversations.

**4.2 "Was brauche ich für eine 2?"** On that student page: for the next (or a hypothetical)
Leistung, the grade needed to reach each final grade. Pure calculation on top of
`GradeService` (ROADMAP "Later / maybe").

## Phase 5 – Small screens (U1)

**5.1 One-column entry mode.** On a phone, the full grade table is unusable. A view for a single
Leistung: student list with large inputs, same autosave. Lets a teacher enter Mitarbeit grades
in class. Only worth it after checking that teachers actually want to grade on a phone –
ask a few before building.

## Not planned (and why)

- **Free dashboards / widgets on the Übersicht.** "Meine Fächer" plus missing-grade badges
  answer "what do I need to do". More would be noise.
- **Automatic grading rules** (e.g. "fehlt = 6"). Pedagogical decisions stay with the teacher.
- **Freezing or archiving old years.** Against the anti-freeze principle in ROADMAP.md; the
  Übersicht already shows only the newest year's Fächer.
- **Class-wide export of all Fächer** (U16). Wait until someone asks; per-Fach xlsx plus the
  Notenübersicht cover today's needs.

## Suggested order

| Step | Effort | Serves | Status |
|---|---|---|---|
| 1.1 Fach tabs | S | U1, U2, U8 | Done |
| 1.2 + 1.3 Leistung in the grade table | M | U1, U2 | Done |
| 3.1 + 3.2 Copy setup / starter | S | U11 | Done |
| 2.2 Missing-grade hints | S | U7, U9 | Done |
| 2.1 Absence mark | M | U4, U7 | Done |
| 2.3 Zeugnis check | S | U9 | Done (at-risk threshold stored per grade scale) |
| 1.4 Notenschlüssel in the grid | S | U2 | Done (band editor + live Notenspiegel in the dialog) |
| 1.5 Fach switcher | S | U6 | Done |
| 4.1, 4.2 Student view | M | U5, U17 | Done |
| 3.3 Invite a colleague before first login | M | U14 | Open: needs a decision on matching by email |
| 5.1 Phone mode | M | U1 | Open: only after asking teachers |

S = up to a day, M = a few days.

Also done since: U8 missing Verhaltensnoten are highlighted; U10 a student can be marked
"ausgetreten" (grades kept, read-only below the tables). Still open: U16 (class-wide export).
