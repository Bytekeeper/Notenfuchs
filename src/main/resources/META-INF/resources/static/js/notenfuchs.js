/**
 * Grade grid and Verhaltensnoten grid: spreadsheet-style keys and autosave. Every cell is an
 * <input class="grade-input"> with data-row/data-col (its position) and the ids its save sends.
 * Tab/Shift+Tab move along the rows, Enter/Shift+Enter and the arrow keys move up/down/sideways,
 * Escape restores the value the cell had on focus. Leaving a changed cell saves it. Ctrl+Z takes back
 * the last saved change by saving the old value again; the history lives as long as the page.
 */
(function () {
    "use strict";

    function initGrid(root, grid) {
        if (root.dataset.gridInitialized === "1") {
            return;
        }
        root.dataset.gridInitialized = "1";

        const maxRow = parseInt(root.dataset.maxRow || "0", 10);
        const maxCol = parseInt(root.dataset.maxCol || "0", 10);

        // One save at a time, request and page update together: every answer carries averages
        // over a whole column, so an answer handled out of order would show stale averages.
        let saveChain = Promise.resolve();
        const undoStack = [];
        const undoNote = document.createElement("p");
        undoNote.className = "hint undo-note";
        undoNote.setAttribute("role", "status");
        undoNote.hidden = true;
        root.closest(".grid-wrapper").before(undoNote);

        function cellAt(row, col) {
            return root.querySelector('.grade-input[data-row="' + row + '"][data-col="' + col + '"]');
        }

        function focusCell(input) {
            if (input) {
                input.focus();
                input.select();
            }
        }

        // An empty category (or a Halbjahr slot without Leistungen) has no input; skip it, or
        // Tab would stop there and the cell just edited would never blur and save.
        function nextCell(row, col, dir, wrapRows) {
            let r = row;
            let c = col;
            for (let i = 0; i < (maxRow + 1) * (maxCol + 1); i++) {
                c += dir;
                if (c < 0 || c > maxCol) {
                    if (!wrapRows) {
                        return null;
                    }
                    c = c < 0 ? maxCol : 0;
                    r += dir;
                }
                if (r < 0 || r > maxRow) {
                    return null;
                }
                const cell = cellAt(r, c);
                if (cell) {
                    return cell;
                }
            }
            return null;
        }

        function normalizeValue(raw) {
            // German decimals: "2,3" -> "2.3"
            return raw.trim().replace(",", ".");
        }

        function setState(input, state) {
            input.classList.remove("state-saved", "state-error", "state-saving");
            if (state) {
                input.classList.add("state-" + state);
            }
        }

        function saveCell(input, isUndo) {
            const normalized = normalizeValue(input.value);
            // Start from what the page was rendered with, so clearing a pre-filled cell is a change.
            if (input.dataset.lastSaved === undefined) {
                input.dataset.lastSaved = normalizeValue(input.defaultValue);
                input.dataset.lastShown = input.defaultValue;
            }
            if (normalized === input.dataset.lastSaved) {
                return;
            }
            const before = input.dataset.lastShown;
            setState(input, "saving");
            const body = grid.saveFields(input);
            body.set("value", normalized);

            saveChain = saveChain.then(function () {
                return fetch(root.dataset.saveUrl, {
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
                        setState(input, "saved");
                        setTimeout(function () {
                            input.classList.remove("state-saved");
                        }, 1200);
                        // The server may write it differently ("2,3" comes back as "2-"); compare
                        // against that, or the next blur would save the same grade again.
                        input.dataset.lastSaved = normalized;
                        if (typeof data.displayValue === "string") {
                            input.value = data.displayValue;
                            input.dataset.lastSaved = normalizeValue(data.displayValue);
                        }
                        input.dataset.lastShown = input.value;
                        grid.afterSave(root, input, data);
                        if (isUndo) {
                            showUndoNote(input);
                        } else {
                            undoStack.push({ input: input, value: before });
                        }
                    })
                    .catch(function (err) {
                        setState(input, "error");
                        input.title = err.message || "Fehler beim Speichern";
                    });
            });
        }

        function showUndoNote(input) {
            const row = input.closest("tr");
            const name = (row.querySelector(".student-cell-inner > span") || row.querySelector(".student-cell")).textContent;
            const value = document.createElement("span");
            value.className = "private";
            value.textContent = input.value.trim() ? "„" + input.value.trim() + "“" : "leer";
            undoNote.replaceChildren("Zurückgenommen: " + name + " wieder ", value);
            undoNote.hidden = false;
        }

        // Typing in a cell keeps the browser's own Ctrl+Z; so do other fields on the page.
        document.addEventListener("keydown", function (ev) {
            if (!(ev.ctrlKey || ev.metaKey) || ev.shiftKey || ev.altKey || ev.key.toLowerCase() !== "z") {
                return;
            }
            const active = document.activeElement;
            const inGrid = active && active.classList.contains("grade-input") && root.contains(active);
            if (inGrid ? active.value !== active.dataset.valueOnFocus : active && active !== document.body) {
                return;
            }
            const last = undoStack.pop();
            if (last) {
                ev.preventDefault();
                last.input.value = last.value;
                last.input.scrollIntoView({ block: "nearest", inline: "nearest" });
                saveCell(last.input, true);
            }
        });

        root.addEventListener("focusin", function (ev) {
            const input = ev.target;
            if (input.classList.contains("grade-input")) {
                input.dataset.valueOnFocus = input.value;
                setState(input, null);
            }
        });

        root.addEventListener("focusout", function (ev) {
            if (ev.target.classList.contains("grade-input")) {
                saveCell(ev.target);
            }
        });

        root.addEventListener("keydown", function (ev) {
            const input = ev.target;
            if (!input.classList.contains("grade-input")) {
                return;
            }
            const row = parseInt(input.dataset.row, 10);
            const col = parseInt(input.dataset.col, 10);
            const back = ev.shiftKey ? -1 : 1;

            if (ev.key === "Tab") {
                ev.preventDefault();
                focusCell(nextCell(row, col, back, true));
            } else if (ev.key === "Enter") {
                ev.preventDefault();
                focusCell(cellAt(row + back, col));
            } else if (ev.key === "ArrowDown") {
                ev.preventDefault();
                focusCell(cellAt(row + 1, col));
            } else if (ev.key === "ArrowUp") {
                ev.preventDefault();
                focusCell(cellAt(row - 1, col));
            } else if (ev.key === "ArrowRight" && input.selectionStart === input.value.length) {
                focusCell(nextCell(row, col, 1, false));
            } else if (ev.key === "ArrowLeft" && input.selectionStart === 0) {
                focusCell(nextCell(row, col, -1, false));
            } else if (ev.key === "Escape") {
                input.value = input.dataset.valueOnFocus || "";
            }
        });
    }

    function setText(root, selector, value) {
        const el = root.querySelector(selector);
        if (el) {
            el.textContent = value != null ? value : "–";
        }
    }

    const gradeGrid = {
        saveFields: function (input) {
            return new URLSearchParams({ studentId: input.dataset.studentId, assessmentId: input.dataset.assessmentId });
        },
        afterSave: function (root, input, data) {
            // Each average cell has a data-scope: "jahr" always, "h1"/"h2" in the Halbjahr view.
            // Only H1/H2 show a tendency or half grade; "Jahr" is always a whole grade.
            const student = '[data-student-id="' + input.dataset.studentId + '"]';
            [["jahr", data.rawAverage, data.finalGrade],
                ["h1", data.h1RawAverage, data.h1DisplayLabel],
                ["h2", data.h2RawAverage, data.h2DisplayLabel]].forEach(function (scope) {
                setText(root, ".average-raw" + student + '[data-scope="' + scope[0] + '"]', scope[1]);
                setText(root, ".average-final" + student + '[data-scope="' + scope[0] + '"]', scope[2]);
            });
            const assessment = '[data-assessment-id="' + data.assessmentId + '"]';
            setText(root, "tfoot .assessment-average-raw" + assessment, data.assessmentRawAverage);
            setText(root, "tfoot .assessment-average-final" + assessment, data.assessmentFinalGrade);

            if (input.classList.contains("points-input")) {
                const display = data.derivedGradeDisplay || "";
                input.parentElement.querySelector(".derived-grade").textContent = display;
                input.parentElement.querySelector(".derived-grade-arrow").textContent = display ? "→" : "";
            }
            const cell = input.closest("td.grade-cell");
            const value = input.value.trim();
            cell.classList.toggle("grade-cell-absent", /^[eu]|[eu]$/i.test(value));
            // Only cells of a Leistung whose date has come carry data-due (see OpenGrades#isDue).
            if (cell.dataset.due === "true") {
                cell.classList.toggle("grade-cell-open", value === "");
            }
        }
    };

    const behaviorGrid = {
        saveFields: function (input) {
            return new URLSearchParams({ studentId: input.dataset.studentId, subjectId: input.dataset.subjectId });
        },
        afterSave: function (root, input, data) {
            input.closest("td").classList.toggle("grade-cell-open", input.value.trim() === "");
            const average = root.querySelector('.average-cell[data-student-id="' + input.dataset.studentId + '"]');
            if (average) {
                setText(average, ".behavior-average-raw", data.studentRawAverage);
                average.classList.toggle("borderline", !!data.studentBorderline);
            }
            const subject = '[data-subject-id="' + data.subjectId + '"]';
            setText(root, "tfoot .behavior-subject-average-raw" + subject, data.subjectRawAverage);
            setText(root, "tfoot .behavior-subject-average-final" + subject, data.subjectFinalGrade);
        }
    };

    function scan() {
        document.querySelectorAll(".grade-grid-root").forEach(function (root) { initGrid(root, gradeGrid); });
        document.querySelectorAll(".behavior-grid-root").forEach(function (root) { initGrid(root, behaviorGrid); });
    }

    document.addEventListener("DOMContentLoaded", scan);
    document.body.addEventListener("htmx:afterSettle", scan);
})();

/**
 * "Ändern": a ".rename-wrap" switches between its ".rename-view" and ".rename-form" with the
 * "editing" class. Listeners sit on the document, so they survive htmx swaps of the lists.
 */
(function () {
    "use strict";

    function resetInput(wrap) {
        const display = wrap.querySelector(".rename-display, .rename-view a");
        const input = wrap.querySelector(".rename-form input[name='name']");
        if (input && display) {
            input.value = display.textContent.trim();
        }
        // Other fields can't be read back from the display text, so they carry data-original.
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
            // Not every form has a "name" input (e.g. the Stichtag).
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
 * "Importieren" (Schülerliste): the button opens the hidden file picker, and picking a file
 * submits its form, so the teacher lands on the preview without a second click.
 */
(function () {
    "use strict";

    document.addEventListener("click", function (ev) {
        const button = ev.target.closest("[data-pick-file]");
        if (button) {
            document.getElementById(button.dataset.pickFile).click();
        }
    });

    document.addEventListener("change", function (ev) {
        const input = ev.target.closest(".file-import input[type='file']");
        if (input && input.files.length > 0) {
            input.form.submit();
        }
    });
})();

/**
 * Halbjahr display settings: a bare tendency threshold (e.g. 0.1) says little, so a live
 * "Beispiel bei Note 3: …" line spells it out for the chosen mode (see HalfYearGradeDisplayService).
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

    function showInitialExample() {
        const input = document.querySelector('.half-year-grade-display-form input[name="tendencyThreshold"]');
        if (input) {
            updateTendencyExample(input);
        }
    }
    document.addEventListener("DOMContentLoaded", showInitialExample);
    document.addEventListener("htmx:afterSettle", showInitialExample);
})();

/**
 * Leistung dialog, on the grade grid and on "Leistungen & Gewichtung": ".column-add" opens it
 * empty, ".column-edit" filled in from its data attributes. After saving in the grid, the server
 * redirects with ?focus=<assessmentId> and the cursor lands in that column.
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

        // Saved in the background so a refused save shows in the dialog, not as an error page.
        form.addEventListener("submit", function (ev) {
            ev.preventDefault();
            var deleting = ev.submitter === deleteButton;
            var switchingMode = originalPointsBased !== null && pointsBox.checked !== originalPointsBased;
            if (!deleting && switchingMode && !window.confirm("Leistung '" + form.elements.name.value
                    + "' auf " + (pointsBox.checked ? "Punkte" : "Noten") + " umstellen? Das löscht alle bisher"
                    + " eingetragenen " + (pointsBox.checked ? "Noten" : "Punkte") + " dieser Leistung;"
                    + " Abwesenheitsvermerke bleiben.")) {
                return;
            }
            var url = (ev.submitter && ev.submitter.getAttribute("formaction")) || form.action;
            var buttons = form.querySelectorAll('button:not([type="button"])');
            buttons.forEach(function (button) { button.disabled = true; });
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
                    buttons.forEach(function (button) { button.disabled = false; });
                    return resp.text().then(function (message) {
                        errorBox.textContent = resp.status === 404
                            ? "Diese Leistung gibt es nicht mehr. Bitte laden Sie die Seite neu."
                            : resp.status === 400 && message ? message : "Nicht gespeichert. Bitte prüfen Sie die Eingaben.";
                        errorBox.hidden = false;
                    });
                })
                .catch(function () {
                    buttons.forEach(function (button) { button.disabled = false; });
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

/**
 * Add panels ("+ Schüler hinzufügen" etc.): typing a list of entries should need no mouse,
 * so opening a panel and every successful add put the cursor back into its first field.
 */
(function () {
    "use strict";

    function focusFirstField(container) {
        const field = container.querySelector("input:not([type='hidden']), select");
        if (field) {
            field.focus();
        }
    }

    // Focus moves happen a moment after the click or the response. Only move the cursor if it
    // is still where that left it, never out of a field the teacher has started typing in.
    function cursorIsFree(allowedPlace) {
        const active = document.activeElement;
        return !active || active === document.body || (allowedPlace && allowedPlace.contains(active));
    }

    // Only a click opens with focus: panels the server renders open must not scroll the page.
    document.addEventListener("click", function (ev) {
        const summary = ev.target.closest("details.add-panel > summary");
        if (summary && !summary.parentElement.open) {
            requestAnimationFrame(function () {
                if (cursorIsFree(summary)) {
                    focusFirstField(summary.parentElement);
                }
            });
        }
    });

    document.addEventListener("htmx:afterRequest", function (ev) {
        const form = ev.target;
        if (form.matches("details.add-panel form") && ev.detail.successful) {
            form.reset();
            if (cursorIsFree(form)) {
                focusFirstField(form);
            }
        }
    });

    // A panel inside the list fragment is re-rendered closed by the swap; reopen the one the
    // teacher just added from, so the next entry needs no click either.
    let panelToReopen = null;
    document.addEventListener("htmx:beforeSwap", function (ev) {
        const panel = ev.detail.requestConfig && ev.detail.requestConfig.elt.closest("details.add-panel[id]");
        panelToReopen = panel && ev.detail.target.contains(panel) ? panel.id : null;
    });
    document.addEventListener("htmx:afterSettle", function () {
        const panel = panelToReopen && document.getElementById(panelToReopen);
        panelToReopen = null;
        if (panel) {
            panel.open = true;
            if (cursorIsFree(null)) {
                focusFirstField(panel);
            }
        }
    });
})();

// ---------------------------------------------------------------------------
// Feedback for every htmx request: the sending button is disabled while it runs (no double
// adds), and a refused request shows the server's sentence below what sent it.
// ---------------------------------------------------------------------------
(function () {
    "use strict";

    // Data pages print (docs/ui-concept.md); inline onclick handlers are avoided everywhere.
    document.addEventListener("click", function (ev) {
        if (ev.target.closest("[data-print]")) {
            window.print();
        }
    });

    function sendingButtons(elt) {
        if (elt.matches("form")) {
            return Array.from(elt.querySelectorAll('button:not([type="button"]), input[type="submit"]'));
        }
        return elt.matches("button") ? [elt] : [];
    }

    function errorAnchor(elt) {
        return elt.closest("form") || elt;
    }

    document.addEventListener("htmx:beforeRequest", function (ev) {
        const elt = ev.detail.elt;
        const old = errorAnchor(elt).nextElementSibling;
        if (old && old.classList.contains("request-error")) {
            old.remove();
        }
        sendingButtons(elt).forEach(function (button) { button.disabled = true; });
    });

    document.addEventListener("htmx:afterRequest", function (ev) {
        sendingButtons(ev.detail.elt).forEach(function (button) { button.disabled = false; });
        if (ev.detail.successful) {
            return;
        }
        const xhr = ev.detail.xhr;
        let message;
        if (!xhr || xhr.status === 0) {
            message = "Keine Verbindung zum Server. Bitte versuchen Sie es noch einmal.";
        } else if (xhr.status === 404) {
            message = "Das gibt es nicht mehr. Bitte laden Sie die Seite neu.";
        } else if (xhr.status === 400 && xhr.responseText) {
            message = xhr.responseText;
        } else {
            message = "Das hat nicht geklappt. Bitte laden Sie die Seite neu und versuchen Sie es noch einmal.";
        }
        const box = document.createElement("p");
        box.className = "hint warning request-error";
        box.setAttribute("role", "alert");
        box.textContent = message;
        const anchor = errorAnchor(ev.detail.elt);
        if (anchor.isConnected) {
            anchor.after(box);
        }
    });
})();

/**
 * "Noten verbergen" in the header: hides every value marked `.private` on screen (privacy.js applies
 * the saved choice before the page paints). A click shows one value; a grade cell shows only while
 * it has focus, so a saved grade disappears again when the teacher moves on.
 */
(function () {
    "use strict";

    document.addEventListener("click", function (ev) {
        const root = document.documentElement;
        if (ev.target.closest(".privacy-toggle")) {
            const on = root.classList.toggle("privacy-on");
            try {
                localStorage.setItem("notenfuchs.privacy", on ? "on" : "off");
            } catch (e) {
                // Storage blocked: the choice holds for this page only.
            }
        }
        if (!root.classList.contains("privacy-on")) {
            return;
        }
        let shown = ev.target.closest(".private");
        if (shown && (shown.matches("input") || shown.querySelector("input"))) {
            shown = null;
        }
        document.querySelectorAll(".private.revealed").forEach(function (el) {
            if (el !== shown) {
                el.classList.remove("revealed");
            }
        });
        if (shown) {
            shown.classList.add("revealed");
        }
    });
})();
