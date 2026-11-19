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

## Layout

- Page: header, breadcrumbs, `h1`, tabs (`.tabs`), then the content, max. 1100 px wide.
- Two separate lists of similar weight sit side by side in `.section-columns`; below 800 px
  they stack. A page with one main table (grade grid, Notenübersicht) uses the full width.
- Each section, top to bottom: `h2`, the list, its add panels directly below the list, then
  downloads.

## Building blocks

| Content | Use | Don't |
|---|---|---|
| A list of records (students, Fächer, Leistungen, teachers) | `table.entity-list`, one row per record, a header row; secondary facts (Kürzel, scale, "seit …") as small `.row-meta` text under or next to the name | Cards per record, free-floating text rows, columns that are mostly empty |
| A form that is the page's job (settings, login) | `.card` around the form | |
| A form you need rarely (new class, Fach, student, Kategorie, colleague; CSV import) | `details.add-panel` with a `summary` like "+ Fach anlegen", `.card` inside; `open` while its list is empty | A form that is always open |
| A form you use often in a group (new Leistung in a Kategorie) | an open one-line `form.inline-form` under the group's list | Hiding it behind a click |
| A group with its own sub-list (a Kategorie with its Leistungen) | one `.card` per group: header with "Ändern", then the table | |
| A form that needs focus or a preview (Leistung, feedback) | `<dialog>` | A separate page |
| Navigation targets on the home page (Fächer, classes) | `a.class-card` tiles, the whole tile is the link, no buttons on it | Tiles for data you compare; actions on a tile |
| Explanations | `.hint` (muted, small), one or two sentences | Long help text on the page; that belongs in `docs/anleitung/` |

## Actions

- **Row actions** (`.row-actions`, last column, as narrow as its buttons): one button, usually
  "Ändern". A row with a single action and nothing to edit (a teacher's access) shows that
  action directly ("Entfernen").
- **A whole class** (name, school year, delete) is changed in its "Einstellungen" tab, not on
  the home page.
- **Edit row:** "Ändern" opens a full-width `tr.edit-row.rename-wrap` below the row
  (`data-rename-target`), with all fields, "Speichern", "Verwerfen" and, on the right
  (`.rare-actions`), the rare actions such as "Ausgetreten" and "Löschen". Escape closes it.
  A row with nothing to edit but several actions (a student who left) still gets "Ändern"; its
  edit row holds only the actions and "Schließen".
- **Button styles:**
  - primary (orange) for the one submit of a form;
  - tonal (`.secondary`, `.row-btn`, `.rename-toggle`) for everything else;
  - `.delete-btn` (red text, no fill) for removing.
- **Destructive actions** ask first (`hx-confirm`). The question names the thing and says what
  goes with it ("… löscht auch alle Noten …"). Where an action can be undone instead, offer
  that one (e.g. "Ausgetreten" instead of "Löschen").
- **Links** for moving to another page. Downloads ("Klasse als Excel exportieren") are tonal
  buttons (`a.btn.secondary`) in `.list-actions` below the add panels.
- **No hover-only actions.** Tablets have no hover, and a hidden action is a lost one.

## Words

German, short, and the same word for the same action everywhere:

| Action | Label |
|---|---|
| Create a new thing | "Anlegen" (summary: "+ Fach anlegen") |
| Add an existing thing to a list | "Hinzufügen" (summary: "+ Schüler hinzufügen") |
| Open edit mode | "Ändern" |
| Save / discard changes | "Speichern" / "Verwerfen" |
| Remove for good | "Löschen" |
| Take away access (deletes nothing) | "Entfernen" |

Dates show as `dd.MM.yyyy` (`{date.format('dd.MM.yyyy')}`); date inputs keep ISO values.

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
| "Offen" (a grade is due but missing) | light amber cell |
| Absence mark (`e` / `u`) | light blue cell |
| Read-only (students who left) | muted text, below the active rows |

## Grids

The grade and Verhaltensnoten grids are the exception to "rows with actions": every cell is
an input that saves when you leave it (one save at a time, see `queueSave`). The student
column stays in place when you scroll sideways; averages are in the last columns. Nothing else
on a grid page competes with the grid.
