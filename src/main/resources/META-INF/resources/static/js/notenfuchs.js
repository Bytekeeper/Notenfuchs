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

            if (input.dataset.lastSaved === undefined) {
                input.dataset.lastSaved = "";
            }
            if (normalized === input.dataset.lastSaved) {
                // nothing changed since last save/load
                return;
            }

            setState(input, "saving");

            const body = new URLSearchParams();
            body.set("studentId", studentId);
            body.set("assessmentId", assessmentId);
            body.set("value", normalized);

            fetch(saveUrl, {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: body.toString()
            })
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
                    updateAverageRow(studentId, data);
                    updateAssessmentAverage(data);
                    updateDerivedGrade(input, data);
                })
                .catch(function (err) {
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
            updateAverageScope(studentId, "jahr", data.rawAverage, data.finalGrade);
            updateAverageScope(studentId, "h1", data.h1RawAverage, data.h1FinalGrade);
            updateAverageScope(studentId, "h2", data.h2RawAverage, data.h2FinalGrade);
        }

        function updateAverageScope(studentId, scope, rawAverage, finalGrade) {
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
                finalCell.textContent = finalGrade != null ? finalGrade : "–";
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

            if (input.dataset.lastSaved === undefined) {
                input.dataset.lastSaved = "";
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

            fetch(saveUrl, {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: body.toString()
            })
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
            const wrap = toggle.closest(".rename-wrap");
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
