/**
 * Notenfuchs grade grid: spreadsheet-style keyboard navigation + autosave.
 *
 * Each editable cell is an <input class="grade-input"> with data attributes:
 *   data-row   - zero-based row index (student)
 *   data-col   - zero-based column index (assessment)
 *   data-student-id
 *   data-assessment-id
 *
 * Navigation:
 *   Tab / Shift+Tab   -> next/previous cell horizontally (native browser tab order also
 *                        works since inputs are in DOM order, but we intercept to skip
 *                        non-grid elements reliably and to trigger save-on-navigate)
 *   Enter / Shift+Enter -> move down/up within the same column
 *   Arrow keys        -> move in the corresponding direction
 *
 * Autosave:
 *   On blur (which also fires when navigating away via keyboard), if the cell's value
 *   changed since it was focused, POST it to the grid's save endpoint. Empty value
 *   clears/deletes the grade. Visual feedback: briefly flash green (saved) or show a
 *   persistent red state (validation/server error) until corrected.
 */
(function () {
    "use strict";

    function initGradeGrid(root) {
        if (!root || root.dataset.gfInitialized === "1") {
            return;
        }
        root.dataset.gfInitialized = "1";

        const saveUrl = root.dataset.saveUrl; // e.g. /subjects/42/grid/cell
        const avgUrl = root.dataset.averageUrlTemplate; // e.g. /subjects/42/grid/average/{studentId}

        // Save requests have no server-side ordering guarantee - a fast edit followed by a
        // slower one (or, for the same student, edits to two different cells) can have their
        // responses arrive out of order. Without tracking "is this response still current",
        // whichever response happens to land LAST wins the DOM update, even if it's actually
        // the older edit - silently reverting a newer, already-saved value back to a stale
        // display (the average briefly losing a tendency suffix, or worse, being just wrong).
        // Two counters guard against this: one per cell (its own saved-state/value), one per
        // student (the shared H1/H2/Jahr average row several cells can all update).
        let studentSaveSeq = {};

        // One save at a time: the server may process overlapping saves in any order, and each
        // answer carries averages over the whole column, so an answer computed before a neighbor's
        // save landed would overwrite a correct average with a stale one.
        let saveChain = Promise.resolve();
        function queueSave(body) {
            const request = saveChain.then(function () {
                return fetch(saveUrl, {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded" },
                    body: body.toString()
                });
            });
            saveChain = request.catch(function () {});
            return request;
        }

        function nextCellSeq(input) {
            const seq = (parseInt(input.dataset.saveRequestId || "0", 10) + 1).toString();
            input.dataset.saveRequestId = seq;
            return seq;
        }

        function nextStudentSeq(studentId) {
            studentSaveSeq[studentId] = (studentSaveSeq[studentId] || 0) + 1;
            return studentSaveSeq[studentId];
        }

        function cellAt(row, col) {
            return root.querySelector(
                '.grade-input[data-row="' + row + '"][data-col="' + col + '"]'
            );
        }

        function maxRow() {
            return parseInt(root.dataset.maxRow || "0", 10);
        }

        function maxCol() {
            return parseInt(root.dataset.maxCol || "0", 10);
        }

        function focusCell(input) {
            if (input) {
                input.focus();
                input.select();
            }
        }

        // A category with no Leistungen (yet) still reserves one column so the table stays
        // rectangular (see GradeGridResource's CategoryColumns#columnCount) - and in the
        // Halbjahr split view, a category with assessments in only one half leaves the other
        // half's slot empty too. Neither has a .grade-input, so cellAt() returns null there;
        // without skipping past it, Tab/Arrow navigation would silently stop moving focus
        // altogether, which also means the cell just edited never blurs and never autosaves.
        function findNextCellAcrossRows(row, col, dir) {
            let r = row;
            let c = col;
            const maxIterations = (maxRow() + 1) * (maxCol() + 1);
            for (let i = 0; i < maxIterations; i++) {
                c += dir;
                if (c < 0) {
                    c = maxCol();
                    r -= 1;
                } else if (c > maxCol()) {
                    c = 0;
                    r += 1;
                }
                if (r < 0 || r > maxRow()) {
                    return null;
                }
                const cell = cellAt(r, c);
                if (cell) {
                    return cell;
                }
            }
            return null;
        }

        function findNextCellInRow(row, col, dir) {
            let c = col + dir;
            while (c >= 0 && c <= maxCol()) {
                const cell = cellAt(row, c);
                if (cell) {
                    return cell;
                }
                c += dir;
            }
            return null;
        }

        function normalizeValue(raw) {
            // Accept German-style comma decimals: "2,3" -> "2.3"
            return raw.trim().replace(",", ".");
        }

        function setState(input, state) {
            input.classList.remove("state-saved", "state-error", "state-saving");
            if (state) {
                input.classList.add("state-" + state);
            }
        }

        function clearSavedFlashSoon(input) {
            setTimeout(function () {
                if (input.classList.contains("state-saved")) {
                    input.classList.remove("state-saved");
                }
            }, 1200);
        }

        function saveCell(input) {
            const row = input.dataset.row;
            const col = input.dataset.col;
            const studentId = input.dataset.studentId;
            const assessmentId = input.dataset.assessmentId;
            const rawValue = input.value;
            const normalized = normalizeValue(rawValue);

            // Start from what the page was rendered with, so clearing a pre-filled cell is a change.
            if (input.dataset.lastSaved === undefined) {
                input.dataset.lastSaved = normalizeValue(input.defaultValue);
            }
            if (normalized === input.dataset.lastSaved) {
                // nothing changed since last save/load
                return;
            }

            setState(input, "saving");

            const cellSeq = nextCellSeq(input);
            const studentSeq = nextStudentSeq(studentId);

            const body = new URLSearchParams();
            body.set("studentId", studentId);
            body.set("assessmentId", assessmentId);
            body.set("value", normalized);

            queueSave(body)
                .then(function (resp) {
                    if (!resp.ok) {
                        return resp.text().then(function (msg) {
                            throw new Error(msg || ("HTTP " + resp.status));
                        });
                    }
                    return resp.json();
                })
                .then(function (data) {
                    // The average row is shared by every cell of this student - checked and
                    // applied FIRST and independently of the cell-specific block below, so an
                    // unexpected error in the latter (e.g. a future change to
                    // updateDerivedGrade) can never prevent this student's H1/H2/Jahr average
                    // from refreshing.
                    if (studentSaveSeq[studentId] === studentSeq) {
                        updateAverageRow(studentId, data);
                    }
                    updateAssessmentAverage(data);
                    // A newer edit of this same cell has already been issued (and its own
                    // response applied, or is still in flight) - this response is stale, skip
                    // everything cell-specific so it can't clobber the newer edit.
                    if (input.dataset.saveRequestId === cellSeq) {
                        input.dataset.lastSaved = normalized;
                        setState(input, "saved");
                        clearSavedFlashSoon(input);
                        if (data && typeof data.displayValue === "string") {
                            input.value = data.displayValue;
                        }
                        updateDerivedGrade(input, data);
                        updateOpenState(input);
                    }
                })
                .catch(function (err) {
                    if (input.dataset.saveRequestId !== cellSeq) {
                        return;
                    }
                    setState(input, "error");
                    input.title = err.message || "Fehler beim Speichern";
                });
        }

        // Every average cell (classic single view or Halbjahr split) carries a "data-scope"
        // attribute: "jahr" always, plus "h1"/"h2" for the two half-year columns when a
        // cutoff is set. Updating by scope (not just student id) lets a single cell edit
        // refresh all of a student's average columns live, without a page reload.
        function updateAverageRow(studentId, data) {
            if (!data) return;
            // "jahr" always shows the plain finalGrade (whole number) - only H1/H2 can be
            // decorated with a tendency suffix or shown as a half-grade, per
            // SchoolClass#halfYearGradeDisplay (see HalfYearGradeDisplayService).
            updateAverageScope(studentId, "jahr", data.rawAverage, data.finalGrade);
            updateAverageScope(studentId, "h1", data.h1RawAverage, data.h1DisplayLabel);
            updateAverageScope(studentId, "h2", data.h2RawAverage, data.h2DisplayLabel);
        }

        function updateAverageScope(studentId, scope, rawAverage, finalLabel) {
            const rawCell = root.querySelector(
                '.average-raw[data-student-id="' + studentId + '"][data-scope="' + scope + '"]'
            );
            const finalCell = root.querySelector(
                '.average-final[data-student-id="' + studentId + '"][data-scope="' + scope + '"]'
            );
            if (rawCell) {
                rawCell.textContent = rawAverage != null ? rawAverage : "–";
            }
            if (finalCell) {
                finalCell.textContent = finalLabel != null ? finalLabel : "–";
            }
        }

        function updateDerivedGrade(input, data) {
            if (!input.classList.contains("points-input")) return;
            const indicator = input.parentElement.querySelector(".derived-grade");
            const arrow = input.parentElement.querySelector(".derived-grade-arrow");
            const display = data && data.derivedGradeDisplay ? data.derivedGradeDisplay : "";
            if (indicator) indicator.textContent = display;
            if (arrow) arrow.textContent = display ? "→" : "";
        }

        // Only cells of a Leistung whose date has come carry data-due; see GradeGridResource#isDue.
        function updateOpenState(input) {
            const cell = input.closest("td.grade-cell");
            if (!cell) return;
            cell.classList.toggle("grade-cell-absent", /^[eu]|[eu]$/i.test(input.value.trim()));
            if (cell.dataset.due !== "true") return;
            cell.classList.toggle("grade-cell-open", input.value.trim() === "");
        }

        function updateAssessmentAverage(data) {
            if (!data || data.assessmentId == null) return;
            const footer = root.querySelector("tfoot");
            if (!footer) return;
            const rawCell = footer.querySelector('.assessment-average-raw[data-assessment-id="' + data.assessmentId + '"]');
            const finalCell = footer.querySelector('.assessment-average-final[data-assessment-id="' + data.assessmentId + '"]');
            if (rawCell) {
                rawCell.textContent = data.assessmentRawAverage != null ? data.assessmentRawAverage : "–";
            }
            if (finalCell) {
                finalCell.textContent = data.assessmentFinalGrade != null ? data.assessmentFinalGrade : "–";
            }
        }

        root.addEventListener(
            "focusin",
            function (ev) {
                const input = ev.target;
                if (input.classList && input.classList.contains("grade-input")) {
                    input.dataset.valueOnFocus = input.value;
                    setState(input, null);
                }
            },
            true
        );

        root.addEventListener(
            "focusout",
            function (ev) {
                const input = ev.target;
                if (input.classList && input.classList.contains("grade-input")) {
                    saveCell(input);
                }
            },
            true
        );

        root.addEventListener("keydown", function (ev) {
            const input = ev.target;
            if (!input.classList || !input.classList.contains("grade-input")) {
                return;
            }
            const row = parseInt(input.dataset.row, 10);
            const col = parseInt(input.dataset.col, 10);

            if (ev.key === "Tab") {
                ev.preventDefault();
                const dir = ev.shiftKey ? -1 : 1;
                focusCell(findNextCellAcrossRows(row, col, dir));
            } else if (ev.key === "Enter") {
                ev.preventDefault();
                const dir = ev.shiftKey ? -1 : 1;
                const nextRow = row + dir;
                if (nextRow < 0 || nextRow > maxRow()) {
                    return;
                }
                focusCell(cellAt(nextRow, col));
            } else if (ev.key === "ArrowDown") {
                ev.preventDefault();
                focusCell(cellAt(row + 1, col));
            } else if (ev.key === "ArrowUp") {
                ev.preventDefault();
                focusCell(cellAt(row - 1, col));
            } else if (ev.key === "ArrowRight" && caretAtEnd(input)) {
                focusCell(findNextCellInRow(row, col, 1));
            } else if (ev.key === "ArrowLeft" && caretAtStart(input)) {
                focusCell(findNextCellInRow(row, col, -1));
            } else if (ev.key === "Escape") {
                input.value = input.dataset.valueOnFocus || "";
            }
        });

        function caretAtEnd(input) {
            return input.selectionStart === input.value.length;
        }

        function caretAtStart(input) {
            return input.selectionStart === 0;
        }
    }

    function scan() {
        document.querySelectorAll(".grade-grid-root").forEach(initGradeGrid);
    }

    document.addEventListener("DOMContentLoaded", scan);
    // Re-scan after HTMX swaps in new content (e.g. after adding an assessment/student).
    document.body.addEventListener("htmx:afterSettle", scan);
})();

/**
 * Verhaltensnoten grid: the same keyboard navigation/autosave shape as the grade grid above, but
 * over (student, Fach) cells instead of (student, Leistung) ones - no points-based cells, no
 * Halbjahr scopes, and the row average carries a "borderline" flag instead of a discrete grade
 * (see BehaviorGridResource/BehaviorGradeService for why). Kept as its own small init function
 * scanning ".behavior-grid-root" (not ".grade-grid-root") rather than generalizing initGradeGrid
 * above, since the field names genuinely differ (subjectId, not assessmentId) and reusing that
 * function as-is would mean lying about what the id represents.
 *
 *   data-row / data-col               - zero-based grid position
 *   data-student-id / data-subject-id - identify the cell being saved
 */
(function () {
    "use strict";

    function initBehaviorGrid(root) {
        if (!root || root.dataset.bgInitialized === "1") {
            return;
        }
        root.dataset.bgInitialized = "1";

        const saveUrl = root.dataset.saveUrl;

        // One save at a time: the server may process overlapping saves in any order, and each
        // answer carries averages over the whole column, so an answer computed before a neighbor's
        // save landed would overwrite a correct average with a stale one.
        let saveChain = Promise.resolve();
        function queueSave(body) {
            const request = saveChain.then(function () {
                return fetch(saveUrl, {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded" },
                    body: body.toString()
                });
            });
            saveChain = request.catch(function () {});
            return request;
        }

        function cellAt(row, col) {
            return root.querySelector(
                '.grade-input[data-row="' + row + '"][data-col="' + col + '"]'
            );
        }

        function maxRow() {
            return parseInt(root.dataset.maxRow || "0", 10);
        }

        function maxCol() {
            return parseInt(root.dataset.maxCol || "0", 10);
        }

        function focusCell(input) {
            if (input) {
                input.focus();
                input.select();
            }
        }

        function normalizeValue(raw) {
            return raw.trim().replace(",", ".");
        }

        function setState(input, state) {
            input.classList.remove("state-saved", "state-error", "state-saving");
            if (state) {
                input.classList.add("state-" + state);
            }
        }

        function clearSavedFlashSoon(input) {
            setTimeout(function () {
                if (input.classList.contains("state-saved")) {
                    input.classList.remove("state-saved");
                }
            }, 1200);
        }

        function saveCell(input) {
            const studentId = input.dataset.studentId;
            const subjectId = input.dataset.subjectId;
            const rawValue = input.value;
            const normalized = normalizeValue(rawValue);

            // Start from what the page was rendered with, so clearing a pre-filled cell is a change.
            if (input.dataset.lastSaved === undefined) {
                input.dataset.lastSaved = normalizeValue(input.defaultValue);
            }
            if (normalized === input.dataset.lastSaved) {
                // nothing changed since last save/load
                return;
            }

            setState(input, "saving");

            const body = new URLSearchParams();
            body.set("studentId", studentId);
            body.set("subjectId", subjectId);
            body.set("value", normalized);

            queueSave(body)
                .then(function (resp) {
                    if (!resp.ok) {
                        return resp.text().then(function (msg) {
                            throw new Error(msg || ("HTTP " + resp.status));
                        });
                    }
                    return resp.json();
                })
                .then(function (data) {
                    input.dataset.lastSaved = normalized;
                    setState(input, "saved");
                    clearSavedFlashSoon(input);
                    if (data && typeof data.displayValue === "string") {
                        input.value = data.displayValue;
                    }
                    input.closest("td").classList.toggle("grade-cell-open", input.value.trim() === "");
                    updateStudentAverage(studentId, data);
                    updateSubjectAverage(data);
                })
                .catch(function (err) {
                    setState(input, "error");
                    input.title = err.message || "Fehler beim Speichern";
                });
        }

        function updateStudentAverage(studentId, data) {
            if (!data) return;
            const cell = root.querySelector('.average-cell[data-student-id="' + studentId + '"]');
            if (!cell) return;
            const raw = cell.querySelector(".behavior-average-raw");
            if (raw) {
                raw.textContent = data.studentRawAverage != null ? data.studentRawAverage : "–";
            }
            cell.classList.toggle("borderline", !!data.studentBorderline);
        }

        function updateSubjectAverage(data) {
            if (!data || data.subjectId == null) return;
            const footer = root.querySelector("tfoot");
            if (!footer) return;
            const rawCell = footer.querySelector('.behavior-subject-average-raw[data-subject-id="' + data.subjectId + '"]');
            const finalCell = footer.querySelector('.behavior-subject-average-final[data-subject-id="' + data.subjectId + '"]');
            if (rawCell) {
                rawCell.textContent = data.subjectRawAverage != null ? data.subjectRawAverage : "–";
            }
            if (finalCell) {
                finalCell.textContent = data.subjectFinalGrade != null ? data.subjectFinalGrade : "–";
            }
        }

        root.addEventListener(
            "focusin",
            function (ev) {
                const input = ev.target;
                if (input.classList && input.classList.contains("grade-input")) {
                    input.dataset.valueOnFocus = input.value;
                    setState(input, null);
                }
            },
            true
        );

        root.addEventListener(
            "focusout",
            function (ev) {
                const input = ev.target;
                if (input.classList && input.classList.contains("grade-input")) {
                    saveCell(input);
                }
            },
            true
        );

        root.addEventListener("keydown", function (ev) {
            const input = ev.target;
            if (!input.classList || !input.classList.contains("grade-input")) {
                return;
            }
            const row = parseInt(input.dataset.row, 10);
            const col = parseInt(input.dataset.col, 10);

            if (ev.key === "Tab") {
                ev.preventDefault();
                const dir = ev.shiftKey ? -1 : 1;
                let nextCol = col + dir;
                let nextRow = row;
                if (nextCol < 0) {
                    nextCol = maxCol();
                    nextRow = row - 1;
                } else if (nextCol > maxCol()) {
                    nextCol = 0;
                    nextRow = row + 1;
                }
                if (nextRow < 0 || nextRow > maxRow()) {
                    return;
                }
                focusCell(cellAt(nextRow, nextCol));
            } else if (ev.key === "Enter") {
                ev.preventDefault();
                const dir = ev.shiftKey ? -1 : 1;
                const nextRow = row + dir;
                if (nextRow < 0 || nextRow > maxRow()) {
                    return;
                }
                focusCell(cellAt(nextRow, col));
            } else if (ev.key === "ArrowDown") {
                ev.preventDefault();
                focusCell(cellAt(row + 1, col));
            } else if (ev.key === "ArrowUp") {
                ev.preventDefault();
                focusCell(cellAt(row - 1, col));
            } else if (ev.key === "ArrowRight" && caretAtEnd(input)) {
                focusCell(cellAt(row, col + 1));
            } else if (ev.key === "ArrowLeft" && caretAtStart(input)) {
                focusCell(cellAt(row, col - 1));
            } else if (ev.key === "Escape") {
                input.value = input.dataset.valueOnFocus || "";
            }
        });

        function caretAtEnd(input) {
            return input.selectionStart === input.value.length;
        }

        function caretAtStart(input) {
            return input.selectionStart === 0;
        }
    }

    function scan() {
        document.querySelectorAll(".behavior-grid-root").forEach(initBehaviorGrid);
    }

    document.addEventListener("DOMContentLoaded", scan);
    document.body.addEventListener("htmx:afterSettle", scan);
})();

/**
 * Inline rename (click pencil -> edit form -> htmx-submit): a ".rename-wrap" holds a
 * ".rename-view" (display + pencil) and a ".rename-form" (input + save/cancel), toggled
 * purely via the "editing" class - no server round-trip needed just to enter/leave edit
 * mode. These are document-level delegated listeners, so they keep working after htmx
 * swaps in fresh list fragments (e.g. after a save) without any re-scan/init step.
 */
(function () {
    "use strict";

    function resetInput(wrap) {
        const display = wrap.querySelector(".rename-display, .rename-view a");
        const input = wrap.querySelector(".rename-form input[name='name']");
        if (input && display) {
            input.value = display.textContent.trim();
        }
        // Extra fields alongside the name (e.g. Gewichtung/Faktor) carry their pre-edit
        // value in data-original, since it can't be recovered from the display text.
        wrap.querySelectorAll(".rename-form input[data-original], .rename-form select[data-original]").forEach(function (extra) {
            if (extra.type === "checkbox") {
                extra.checked = extra.dataset.original === "true";
            } else {
                extra.value = extra.dataset.original;
            }
        });
    }

    document.addEventListener("click", function (ev) {
        const toggle = ev.target.closest(".rename-toggle");
        if (toggle) {
            // In a table list the toggle sits in the row and opens a separate edit row,
            // which .closest() can't reach; data-rename-target names it instead.
            const wrap = toggle.closest(".rename-wrap")
                || (toggle.dataset.renameTarget && document.getElementById(toggle.dataset.renameTarget));
            if (!wrap) {
                return;
            }
            wrap.classList.add("editing");
            // Not every rename-form has a "name" input (e.g. the Datum-only form for a
            // Leistung) - fall back to whichever input is first.
            const input = wrap.querySelector(".rename-form input[name='name']") || wrap.querySelector(".rename-form input");
            if (input) {
                input.focus();
                input.select();
            }
            return;
        }
        const cancel = ev.target.closest(".rename-cancel");
        if (cancel) {
            ev.preventDefault();
            const wrap = cancel.closest(".rename-wrap");
            resetInput(wrap);
            wrap.classList.remove("editing");
        }
    });

    document.addEventListener("keydown", function (ev) {
        if (ev.key !== "Escape") {
            return;
        }
        const wrap = ev.target.closest(".rename-wrap.editing");
        if (wrap) {
            resetInput(wrap);
            wrap.classList.remove("editing");
        }
    });
})();

/**
 * Notenschlüssel band rows (categoryList.html's "Notenschlüssel für ..." table): unlike every
 * other editable field in this app, points/gradeValue have no Ändern toggle - they're
 * always-live inputs, the same shape as a grade-grid cell, so they save the same way: on
 * blur/tab-away rather than a separate Speichern click. A band is two fields persisted
 * together in one PATCH, so blur of either field submits the row's current values, not just
 * whichever one changed. There's no hx-patch on the form (autosave replaces it entirely), so
 * Enter - the browser's default submit trigger for a single-line input - is intercepted to
 * save immediately instead of navigating anywhere.
 */
(function () {
    "use strict";

    function setState(form, state) {
        form.querySelectorAll("input").forEach(function (input) {
            input.classList.remove("state-saved", "state-error");
            if (state) {
                input.classList.add("state-" + state);
            }
        });
    }

    function clearSavedFlashSoon(form) {
        setTimeout(function () {
            setState(form, null);
        }, 1200);
    }

    function saveBandForm(form) {
        const pointsInput = form.querySelector("input[name='points']");
        const gradeValueInput = form.querySelector("input[name='gradeValue']");
        const key = pointsInput.value + "|" + gradeValueInput.value;
        if (key === form.dataset.lastSaved) {
            return;
        }

        const body = new URLSearchParams();
        body.set("points", pointsInput.value);
        body.set("gradeValue", gradeValueInput.value);

        fetch(form.dataset.saveUrl, {
            method: "PATCH",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: body.toString()
        })
            .then(function (resp) {
                if (!resp.ok) {
                    throw new Error("HTTP " + resp.status);
                }
                form.dataset.lastSaved = key;
                setState(form, "saved");
                clearSavedFlashSoon(form);
            })
            .catch(function () {
                setState(form, "error");
            });
    }

    document.addEventListener("focusout", function (ev) {
        const form = ev.target.closest(".band-form");
        if (form) {
            saveBandForm(form);
        }
    });

    document.addEventListener("submit", function (ev) {
        const form = ev.target.closest(".band-form");
        if (form) {
            ev.preventDefault();
            saveBandForm(form);
        }
    });
})();

/**
 * CSV file picker (ClassPage/detail.html's Schülerliste import): the native <input
 * type="file"> is transparent and stretched over a styled ".btn secondary" label so it lines
 * up with the "Vorschau anzeigen" submit button - see the ".file-field" CSS. The filename
 * text next to it is the only thing that still needs JS, since a transparent input shows no
 * feedback about what (if anything) was picked.
 */
(function () {
    "use strict";

    document.addEventListener("change", function (ev) {
        const input = ev.target.closest(".file-field input[type='file']");
        if (!input) return;
        const filename = input.closest(".file-field").querySelector(".filename");
        if (!filename) return;
        filename.textContent = input.files && input.files.length > 0
            ? input.files[0].name
            : "Keine Datei ausgewählt";
    });
})();

/**
 * Halbjahr grade-display settings form (fragments/halfYearGradeDisplay.html): the tendency
 * threshold input is valid alongside either "Ganze Noten" (WHOLE) or "Halbe Noten" (HALF) - see
 * HalfYearGradeDisplayService, which reuses the exact same threshold for both: in HALF it
 * refines a would-be suffix into the neighboring half-grade once the raw average is close
 * enough to it.
 *
 * The number itself is a raw deviation from a whole grade step (e.g. 0.1, not a percentage), which
 * isn't obvious from a bare number once the placeholder is gone (i.e. as soon as a value is
 * entered) - a live "Beispiel bei Note 3: ..." line (recomputed on every keystroke and mode
 * change, always anchored at grade 3 since the band width is identical around every whole grade)
 * spells out concretely what the number means instead of leaving it as an abstract value - and
 * the wording differs by mode, since "outside the plain zone" means something different in each.
 *
 * Runs after the generic "Inline rename" listeners above (registered earlier in this file, so
 * document click listeners fire in that order), so the cancel-button resync below sees the
 * select's value already restored from data-original.
 */
(function () {
    "use strict";

    function germanDecimal(value) {
        return value.toFixed(2).replace(".", ",");
    }

    function updateTendencyExample(input) {
        const form = input.closest("form");
        const example = form && form.querySelector(".tendency-example");
        if (!example) return;

        if (input.value.trim() === "") {
            example.textContent = "";
            return;
        }
        const band = parseFloat(input.value.replace(",", "."));
        if (isNaN(band) || band < 0) {
            example.textContent = "";
            return;
        }
        const select = form.querySelector('select[name="halfYearGradeDisplay"]');
        const isHalf = select && select.value === "HALF";
        const outside = isHalf ? "sonst 2,5 bzw. 3,5 (oder 3+ / 3-, falls auch das zu weit weg ist)"
            : "sonst 3+ / 3-";
        example.textContent = "Beispiel bei Note 3: " + germanDecimal(3 - band) + "–"
            + germanDecimal(3 + band) + " ohne Tendenz, " + outside;
    }

    function syncTendencyInput(select) {
        const form = select.closest("form");
        const input = form && form.querySelector('input[name="tendencyThreshold"]');
        if (input) {
            updateTendencyExample(input);
        }
    }

    document.addEventListener("input", function (ev) {
        const input = ev.target.closest('.half-year-grade-display-form input[name="tendencyThreshold"]');
        if (input) {
            updateTendencyExample(input);
        }
    });

    document.addEventListener("change", function (ev) {
        const select = ev.target.closest('.half-year-grade-display-form select[name="halfYearGradeDisplay"]');
        if (select) {
            syncTendencyInput(select);
        }
    });

    document.addEventListener("click", function (ev) {
        const toggle = ev.target.closest(".rename-toggle");
        if (toggle) {
            const input = toggle.closest(".rename-wrap")
                .querySelector('.half-year-grade-display-form input[name="tendencyThreshold"]');
            if (input) {
                updateTendencyExample(input);
            }
            return;
        }
        const cancel = ev.target.closest(".half-year-grade-display-form .rename-cancel");
        if (!cancel) return;
        const select = cancel.closest("form").querySelector('select[name="halfYearGradeDisplay"]');
        if (select) {
            syncTendencyInput(select);
        }
    });
})();

/**
 * Add/edit a Leistung straight from the grade table: "+" in a category header opens the dialog
 * empty, clicking a column header opens it filled in. Saving is a plain form POST; the server
 * redirects back with ?focus=<assessmentId>, and the cursor lands in that column's first cell.
 */
(function () {
    "use strict";

    function today() {
        var d = new Date();
        var pad = function (n) { return String(n).padStart(2, "0"); };
        return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
    }

    function initAssessmentDialog(dialog) {
        var form = dialog.querySelector("form");
        var baseUrl = dialog.dataset.baseUrl;
        var title = dialog.querySelector(".assessment-dialog-title");
        var categoryLabel = dialog.querySelector(".assessment-dialog-category");
        var deleteButton = dialog.querySelector(".assessment-dialog-delete");
        var pointsWarning = dialog.querySelector(".assessment-dialog-points-warning");
        var bandsEditor = dialog.querySelector(".assessment-dialog-bands");
        var bandRows = bandsEditor.querySelector("tbody");
        var bandRowTemplate = dialog.querySelector(".band-row-template");
        var preview = dialog.querySelector(".assessment-dialog-preview");
        var rounding = dialog.querySelector(".assessment-dialog-rounding");
        var errorBox = dialog.querySelector(".assessment-dialog-error");
        var pointsBox = form.elements.pointsBased;
        var originalPointsBased = null;
        var previewUrl = null;
        var previewTimer = null;

        function addBandRow(points, grade) {
            var row = bandRowTemplate.content.firstElementChild.cloneNode(true);
            row.querySelector('[name="bandPoints"]').value = points || "";
            row.querySelector('[name="bandGrades"]').value = grade || "";
            bandRows.appendChild(row);
        }

        // Hidden inputs would still be submitted (and checked), so the editor's are disabled while hidden.
        function showBandsEditor(show) {
            bandsEditor.hidden = !show;
            bandsEditor.querySelectorAll("input").forEach(function (input) { input.disabled = !show; });
            if (show) {
                schedulePreview();
            }
        }

        function schedulePreview() {
            clearTimeout(previewTimer);
            previewTimer = setTimeout(loadPreview, 250);
        }

        function loadPreview() {
            if (!previewUrl || bandsEditor.hidden) return;
            var body = new URLSearchParams();
            var complete = true;
            bandRows.querySelectorAll("tr").forEach(function (row) {
                var points = row.querySelector('[name="bandPoints"]').value;
                var grade = row.querySelector('[name="bandGrades"]').value;
                if (points === "" || grade === "") complete = false;
                body.append("bandPoints", points);
                body.append("bandGrades", grade);
            });
            if (!complete) return;
            fetch(previewUrl, {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: body.toString()
            })
                .then(function (resp) { return resp.ok ? resp.json() : null; })
                .then(function (data) {
                    if (!data) {
                        preview.textContent = "Notenschlüssel unvollständig oder ungültig.";
                    } else if (data.average === null) {
                        preview.textContent = "Notenspiegel: noch keine Punkte eingetragen.";
                    } else {
                        preview.textContent = "Notenspiegel: " + data.distribution
                            .map(function (d) { return d.grade + ": " + d.count; })
                            .join(" · ") + " · Ø " + data.average;
                    }
                });
        }

        function open(data) {
            var editing = Boolean(data.assessmentId);
            form.reset();
            form.action = editing ? baseUrl + "/" + data.assessmentId : baseUrl;
            form.elements.categoryId.value = data.categoryId || "";
            title.textContent = editing ? "Leistung bearbeiten" : "Neue Leistung";
            categoryLabel.textContent = "Kategorie: " + data.categoryName;
            form.elements.name.value = data.name || "";
            form.elements.date.value = editing || data.date !== undefined ? (data.date || "") : today();
            form.elements.factor.value = data.factor || "1.0";
            pointsBox.checked = data.pointsBased === "true";
            form.elements.roundingMode.value = data.roundingMode || "IN_FAVOR_OF_STUDENT";
            originalPointsBased = editing ? pointsBox.checked : null;
            deleteButton.hidden = !editing;
            deleteButton.formAction = editing ? baseUrl + "/" + data.assessmentId + "/delete" : "";
            deleteButton.dataset.name = data.name || "";
            previewUrl = editing ? baseUrl + "/" + data.assessmentId + "/band-preview" : null;
            bandRows.innerHTML = "";
            preview.textContent = "";
            (data.bands || "").split(";").filter(Boolean).forEach(function (band) {
                var parts = band.split(":");
                addBandRow(parts[0], parts[1]);
            });
            showBandsEditor(editing && pointsBox.checked);
            rounding.hidden = !pointsBox.checked;
            errorBox.hidden = true;
            pointsWarning.hidden = true;
            dialog.showModal();
            form.elements.name.focus();
        }

        document.addEventListener("click", function (ev) {
            var add = ev.target.closest(".column-add");
            if (add) {
                // In the half-year view each region's "+" brings a date inside that region ("" = Ohne Datum).
                open({ categoryId: add.dataset.categoryId, categoryName: add.dataset.categoryName, date: add.dataset.date });
                return;
            }
            var edit = ev.target.closest(".column-edit");
            if (edit) {
                open(edit.dataset);
            }
        });

        pointsBox.addEventListener("change", function () {
            rounding.hidden = !pointsBox.checked;
            pointsWarning.hidden = originalPointsBased === null || pointsBox.checked === originalPointsBased;
            // Only an existing points-based Leistung has bands to edit; a new switch gets default bands.
            showBandsEditor(originalPointsBased === true && pointsBox.checked);
        });

        dialog.querySelector(".assessment-dialog-add-band").addEventListener("click", function () {
            addBandRow("", "");
            showBandsEditor(true);
            bandRows.lastElementChild.querySelector("input").focus();
        });

        bandsEditor.addEventListener("click", function (ev) {
            var remove = ev.target.closest(".band-remove");
            if (remove && bandRows.children.length > 1) {
                remove.closest("tr").remove();
                schedulePreview();
            }
        });

        bandsEditor.addEventListener("input", schedulePreview);

        deleteButton.addEventListener("click", function (ev) {
            var message = "Leistung '" + deleteButton.dataset.name + "' wirklich löschen? Das löscht auch alle zugehörigen Noten.";
            if (!window.confirm(message)) {
                ev.preventDefault();
            }
        });

        // Saved in the background so a refused save shows up in the dialog instead of a bare error
        // page; on success the server's redirect target (the grid, focused on the column) is loaded.
        form.addEventListener("submit", function (ev) {
            ev.preventDefault();
            var url = (ev.submitter && ev.submitter.getAttribute("formaction")) || form.action;
            fetch(url, {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: new URLSearchParams(new FormData(form)).toString()
            })
                .then(function (resp) {
                    if (resp.ok) {
                        window.location.href = resp.url;
                        return;
                    }
                    errorBox.textContent = resp.status === 404
                        ? "Diese Leistung gibt es nicht mehr. Bitte laden Sie die Seite neu."
                        : "Nicht gespeichert. Bitte prüfen Sie die Eingaben.";
                    errorBox.hidden = false;
                })
                .catch(function () {
                    errorBox.textContent = "Keine Verbindung zum Server. Bitte versuchen Sie es noch einmal.";
                    errorBox.hidden = false;
                });
        });

        dialog.querySelector(".assessment-dialog-cancel").addEventListener("click", function () {
            dialog.close();
        });
    }

    function focusRequestedColumn() {
        var params = new URLSearchParams(window.location.search);
        var assessmentId = params.get("focus");
        if (!assessmentId) {
            return;
        }
        // Drop the parameter so a later reload doesn't jump back to this column.
        window.history.replaceState(null, "", window.location.pathname);
        var input = document.querySelector(
            '.grade-grid-root input[data-row="0"][data-assessment-id="' + CSS.escape(assessmentId) + '"]');
        if (input) {
            input.scrollIntoView({ block: "nearest", inline: "center" });
            input.focus();
            input.select();
        }
    }

    document.addEventListener("DOMContentLoaded", function () {
        var dialog = document.getElementById("assessment-dialog");
        if (dialog) {
            initAssessmentDialog(dialog);
        }
        focusRequestedColumn();
    });
})();

/** Fach header: switching keeps the current tab (Notenerfassung, Leistungen & Gewichtung, Lehrkräfte). */
document.addEventListener("change", function (ev) {
    const select = ev.target.closest(".subject-switcher select");
    if (select) {
        window.location.href = "/subjects/" + select.value + (select.dataset.tabPath || "");
    }
});

/**
 * "Fehler melden" in the header of every page. Sends only the path; the server reduces it to the
 * kind of page (ids removed) before storing it.
 */
(function () {
    "use strict";

    document.addEventListener("DOMContentLoaded", function () {
        var dialog = document.getElementById("feedback-dialog");
        var openButton = document.querySelector(".feedback-open");
        if (!dialog || !openButton) {
            return;
        }
        var form = dialog.querySelector("form");
        var sendButton = dialog.querySelector(".feedback-send");
        var thanks = dialog.querySelector(".feedback-thanks");
        var errorBox = dialog.querySelector(".feedback-error");

        openButton.addEventListener("click", function () {
            form.reset();
            thanks.hidden = true;
            errorBox.hidden = true;
            sendButton.hidden = false;
            dialog.showModal();
            form.elements.message.focus();
        });

        dialog.querySelector(".feedback-close").addEventListener("click", function () {
            dialog.close();
        });

        form.addEventListener("submit", function (ev) {
            ev.preventDefault();
            var body = new URLSearchParams();
            body.set("page", window.location.pathname);
            body.set("message", form.elements.message.value);
            fetch("/feedback", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: body.toString()
            })
                .then(function (resp) {
                    if (!resp.ok) {
                        throw new Error("HTTP " + resp.status);
                    }
                    form.reset();
                    thanks.hidden = false;
                    errorBox.hidden = true;
                    sendButton.hidden = true;
                })
                .catch(function () {
                    errorBox.textContent = "Nicht gesendet. Bitte versuchen Sie es noch einmal.";
                    errorBox.hidden = false;
                });
        });
    });
})();
