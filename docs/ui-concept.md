# UI concept

How Notenfuchs pages look and behave. Read this before you build or change a page. If a new
page needs something that isn't here, add it here first, then build it.

## Principles

1. **The data comes first.** A page exists for its list or table. Controls you use rarely
   (add, import, delete, settings) take less space and attention than the data.
2. **Built for scanning.** A teacher looks for one row among 30. Rows are compact, aligned,
   and the same on every page.
3. **One way per job.** The same kind of action looks the same and is in the same place on
   every page (see "Actions").
4. **No dead controls.** A viewer only sees what they can do (see the access rules in
   CLAUDE.md).
5. **Quiet by default.** Color has a meaning (state, action), never decoration.

## Page types

| Type | Examples | Shape |
|---|---|---|
| List page | class page, "Leistungen & Gewichtung", "Lehrkräfte", settings | Sections of `h2`, the list, its add panels, then import and download buttons |
| Grid | Notenerfassung, Verhaltensnoten | One table of inputs, full width (see "Grids") |
| Data page | Notenspiegel, Notenübersicht, student page | Read-only tables, full width, printable |

## Layout

- Page: header, breadcrumbs, `h1`, tabs (`.tabs`), then the content, max. 1100 px wide.
  Breadcrumbs lead up to the class or Fach; tabs switch between the views of that one
  class or Fach. A page below a tab (student page, import preview) keeps its tab active.
- Two separate lists of similar weight sit side by side in `.section-columns`; below 800 px
  they stack. Grids and data pages use the full width; wide tables scroll sideways inside
  `.grid-wrapper`, never the whole page.
- List page sections, top to bottom: `h2`, the list, its add panels directly below the list,
  then import and download buttons.
- Grids and data pages put their few actions (Excel export, print) in a toolbar
  (`.list-actions`) **above** the table: below a 30-row table they would be out of sight.
- A data page about one record (the student page) sits below its Fach: the `h1` stays the
  Fach, the record's name is an `h2`, its sections are `h3`.

## Building blocks

| Content | Use | Don't |
|---|---|---|
| A list of records (students, Fächer, Leistungen, teachers) | `table.entity-list`, one row per record, a header row; secondary facts (Kürzel, scale, "seit …") as small `.row-meta` text under or next to the name | Cards per record, free-floating text rows, columns that are mostly empty |
| A form that is the page's job (settings, login) | `.card` around the form | |
| A form to add to a list (new class, Fach, student, Kategorie, colleague) | `details.add-panel` with a `summary` like "+ Fach anlegen"; `.card` inside unless it already sits in a card; `open` while its list is empty; opening it and each add put the cursor in its first field, so Enter adds the next entry. A panel inside an htmx fragment needs an `id`, so it stays open after an add from it | A form that is always open |
| A group with its own sub-list (a Kategorie with its Leistungen) | one `.card` per group: header with "Ändern", then the table | |
| A form that needs focus or a preview (Leistung with its Notenschlüssel, feedback) | `<dialog>`, the same one wherever the thing is edited (the Leistung dialog serves the grade grid and "Leistungen & Gewichtung") | A separate page; a second editor for the same thing |
| Navigation targets on the home page (Fächer, classes) | `a.class-card` tiles, the whole tile is the link, no buttons on it | Tiles for data you compare; actions on a tile |
| A rarely-needed form on a data page (the Zeugnisnote on the student page) | closed `details.add-panel`, summary names the action without "+" ("Zeugnisnote festlegen") | |
| Read-only figures (counts, averages) | `table.data-table`; numbers right-aligned in tabular figures; headers of number columns right-aligned too | `table.entity-list` (that is for records with actions) |
| An empty list or table | inside a table: one `tr.empty-row` with one sentence; a list that has no table yet: one `.hint` sentence above its open add panel. Say what goes here, not just "Keine." | |
| Explanations | `.hint` (muted, small), one or two sentences | Long help text on the page; that belongs in `docs/anleitung/` |

## Actions

- **Row actions** (`.row-actions`, last column, as narrow as its buttons): one button, usually
  "Ändern". A row with a single action and nothing to edit (a teacher's access) shows that
  action directly ("Entfernen").
- **A whole class** (name, school year, delete) is changed in its "Einstellungen" tab, not on
  the home page.
- **Edit row:** "Ändern" opens a full-width `tr.edit-row.rename-wrap` below the row
  (`data-rename-target`), with all fields, "Speichern", "Abbrechen" and, on the right
  (`.rare-actions`), the rare actions such as "Ausgetreten" and "Löschen". Escape closes it.
  A row with nothing to edit but several actions (a student who left) still gets "Ändern"; its
  edit row holds only the actions and "Schließen".
- **Button styles:**
  - primary (orange) for the one submit of a form;
  - tonal (`.secondary`, `.row-btn`, `.rename-toggle`) for everything else;
  - `.delete-btn` (red text, no fill) for removing.
- **Destructive actions** ask first (`hx-confirm`, or `confirm()` in a dialog). This includes
  changes that delete data as a side effect (switching a Leistung between points and grades
  clears its entries). The question names the thing and says what goes with it ("… löscht auch
  alle Noten …"). Actions that are easy to undo ("Wieder aufnehmen") don't ask. Where an action
  can be undone instead, offer that one (e.g. "Ausgetreten" instead of "Löschen").
- **Links** for moving to another page. A link in a table row that opens that row's
  record (the grid's **„Details“**) is a small tonal button-style link at the end of the
  cell, so it is visible as a control; plain text links get lost among 30 names.
- **Icon-only controls** (the grid's "+" and ✎, "×" in the Notenschlüssel) are allowed where
  space is tight and the meaning is shown nearby (legend, column header). Each has an
  `aria-label` and a `title` with the full words.
- **Links** Downloads and file imports are tonal buttons in
  `.list-actions` below the add panels. When a list has both, a muted label names the list once
  and the buttons say only the verb: "Schülerliste [Importieren] [Exportieren]". "Importieren"
  opens the file picker at once; the next page shows a preview to confirm.
- **No hover-only actions or facts.** Tablets have no hover, and a hidden action is a lost one.
  A tooltip may repeat what the page says (legend, label), never be the only place for it.

## Feedback

- **Busy:** while a request runs, the button that sent it is disabled, so a double click
  doesn't add a record twice (`notenfuchs.js` does this for every htmx request).
- **Refused:** the server answers 400 with one German sentence (`BadRequestException`); the
  page shows it as `.hint.warning` (`role="alert"`) directly below the form or button that
  sent it, and removes it on the next try. 404 ("gibt es nicht mehr") asks to reload the
  page. Dialogs show the same box inside the dialog.
- **Saved:** the changed page is the confirmation (the new row, the changed value). A grid
  cell flashes green. A form that stays as it was after saving (settings) shows
  `.saved-note` "Gespeichert" next to its button. Nothing pops up.

## Words

German, short, and the same word for the same action everywhere:

| Action | Label |
|---|---|
| Create a new thing | "Anlegen" (summary: "+ Fach anlegen", "+ Leistung anlegen"; in the grid "+ Leistung in Mündlich", or just "+" in a column header) |
| Add an existing thing to a list | "Hinzufügen" (summary and button: "+ Lehrkraft hinzufügen" / "Hinzufügen") |
| Open edit mode | "Ändern" |
| Save changes | "Speichern" |
| Leave a form or dialog without saving | "Abbrechen" |
| Close something where nothing can be lost (a read-only edit row, a sent message) | "Schließen" |
| Remove for good | "Löschen" in a row; "Leistung löschen", "Klasse löschen" where the thing isn't in view (dialog, settings) |
| Take away access (deletes nothing) | "Entfernen" |
| Set a value by hand that is otherwise computed | "Festlegen" |
| Open a record's own page | "Details" |
| Files | "Importieren", "Exportieren" (Excel), "Drucken" |

Dates show as `dd.MM.yyyy` (`{date.format('dd.MM.yyyy')}`); date inputs keep ISO values.

Numbers show with a decimal comma and without trailing zeros: "2,5", "Faktor 1,5", "50 %"
(a space before %). Grades with a tendency show as "2-" / "2+". Number inputs and data
attributes keep the dot (browsers need it); typed grades accept both. The Excel export
writes real numbers.

The user guide quotes labels as **„…“**, and `WalkthroughIT` checks that the app shows them.
So a label change also means a guide change.

## Colors and states

Tokens are in `:root` of `notenfuchs.css`; use them, don't add new hex values for the same
meaning.

| Meaning | Look |
|---|---|
| Action, link, active tab | `--color-primary` (orange) |
| Saved (grade cell) | short green flash (`--color-success`) |
| Error, at-risk grade, delete | `--color-error` / `--color-error-bg` |
| Missing: "offen" grade, missing Verhaltensnote | `--color-warning-bg` cell (amber) |
| Close to a rounding boundary (Verhaltensnoten average) | dotted `--color-warning` underline, not a fill (it is a hint, not a gap) |
| Absence mark (`e` / `u`) | `--color-info-bg` cell (blue) |
| Read-only (students who left), "Ohne Datum" | muted text / `--color-tonal` |

Each color means one thing. A new state gets a new token here first.

## Grids

The grade and Verhaltensnoten grids are the exception to "rows with actions": every cell is
an input that saves when you leave it (one save at a time, see `saveChain` in `notenfuchs.js`). The student
column stays in place when you scroll sideways; averages are in the last columns. Nothing else
on a grid page competes with the grid. Ctrl+Z takes back the last saved cell change (the page's
history only; reloading ends it) and says above the grid whose value came back, since the cell may
be out of view. Deletes elsewhere have no undo; they ask first, and backups cover the rest.

## Privacy mode

"Noten verbergen" in the header (every page) covers every value that comes from grades: grade
cells, averages, Zeugnisnoten, Notenspiegel counts, the at-risk count and the Notenschlüssel preview.
Mark such an element `.private`. Hidden, a filled cell, a cell with an absence mark and an empty cell
look the same, because "offen", absence and at-risk colors tell as much as the grade. A click shows
one value; a cell with an input shows while it has focus. Aggregates per Fach without names (the
"offen" counts on the home page and in the Notenübersicht footer) stay visible. Print ignores it.

## Print

Data pages print: controls carry `.no-print`, tables print in full width. Grids are not
meant for print; the Excel export is the printable copy.
