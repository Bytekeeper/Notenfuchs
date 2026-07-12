package de.notenfuchs.service;

import de.siegmar.fastcsv.reader.CsvParseException;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure, DB-free CSV (de)serialization for a class roster (its list of {@code Student} names) -
 * kept as free of Panache/entity concerns as {@link GradeService}, so it's unit-testable
 * without a database. The web layer ({@code ClassUiResource}) is expected to load/persist
 * entities and call this service to turn bytes into names and back.
 *
 * <p>Tolerant of the two real-world CSV dialects German Excel produces: comma-delimited
 * UTF-8, and semicolon-delimited Windows-1252 (umlauts break under UTF-8 in older Excel
 * locales). {@link #parse} sniffs both; {@link #format} always writes the more
 * Excel-friendly dialect (semicolon delimiter, UTF-8 with a BOM) so a re-opened export
 * shows umlauts correctly without a manual encoding prompt.
 */
public class CsvRosterService {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String HEADER_NAME = "Name";
    private static final String HEADER_VORNAME = "Vorname";
    private static final String HEADER_NACHNAME = "Nachname";

    /** Leading characters Excel/Sheets interpret as the start of a formula - see {@link #escapeFormulaTrigger}. */
    private static final String FORMULA_TRIGGER_CHARS = "=+-@\t\r";

    /**
     * Parses roster CSV bytes into student names, in file order. Convenience wrapper over
     * {@link #parseDetailed(byte[])} for callers that don't need the blank-line count.
     */
    public List<String> parse(byte[] content) {
        return parseDetailed(content).names();
    }

    /**
     * Parses roster CSV bytes into student names, in file order, plus how many blank lines
     * were skipped (used by the import preview page's "N blank skipped" count).
     *
     * <p>Decoding tries UTF-8 first (after stripping a BOM, if present); if the bytes
     * contain a sequence that isn't valid UTF-8, falls back to Windows-1252, which is what
     * German Excel writes by default. Blank lines and surrounding whitespace are dropped/trimmed.
     *
     * <p>A first row consisting of just a "Name" header (case-insensitive, optionally followed
     * by further columns) is recognized and dropped, using only its first column as the name.
     * A first row with separate "Vorname"/"Nachname" columns (case-insensitive, any position,
     * any further columns such as "Alter"/"Klasse"/"Geburtsdatum" ignored) is also recognized;
     * the two columns are joined with a space to form the name. Either way the delimiter
     * (';' vs ',') is sniffed from the header line and used to split every following row into
     * columns. Without a recognizable header, delimiter-based splitting is skipped entirely and
     * every line is taken as-is as one name - a bare, single-column file (the documented input
     * shape) never risks having a literal character in a name, e.g. the comma in "Meyer, Anna",
     * misread as a column separator.
     */
    public RosterParseResult parseDetailed(byte[] content) {
        String text = decode(content);
        List<String> lines = splitLines(text);
        if (lines.isEmpty()) {
            return new RosterParseResult(List.of(), 0);
        }

        String headerLine = lines.get(0);
        char delimiter = sniffDelimiter(headerLine);
        HeaderColumns header = detectHeader(headerLine.split(String.valueOf(delimiter), -1));
        return header != null ? parseWithHeader(text, delimiter, header) : parseHeaderless(lines);
    }

    /** Column layout of a recognized header row - either a single name column, or separate first-/last-name columns. */
    private record HeaderColumns(int nameIndex, int vornameIndex, int nachnameIndex) {
        static HeaderColumns singleName(int index) {
            return new HeaderColumns(index, -1, -1);
        }

        static HeaderColumns firstLast(int vornameIndex, int nachnameIndex) {
            return new HeaderColumns(-1, vornameIndex, nachnameIndex);
        }

        boolean isFirstLast() {
            return vornameIndex >= 0 && nachnameIndex >= 0;
        }
    }

    private static HeaderColumns detectHeader(String[] cells) {
        int vornameIndex = indexOfIgnoreCase(cells, HEADER_VORNAME);
        int nachnameIndex = indexOfIgnoreCase(cells, HEADER_NACHNAME);
        if (vornameIndex >= 0 && nachnameIndex >= 0) {
            return HeaderColumns.firstLast(vornameIndex, nachnameIndex);
        }
        if (HEADER_NAME.equalsIgnoreCase(cells[0].trim())) {
            return HeaderColumns.singleName(0);
        }
        return null;
    }

    private static int indexOfIgnoreCase(String[] cells, String target) {
        for (int i = 0; i < cells.length; i++) {
            if (target.equalsIgnoreCase(cells[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    private RosterParseResult parseWithHeader(String text, char delimiter, HeaderColumns header) {
        List<String> names = new ArrayList<>();
        int blankLinesSkipped = 0;
        // skipEmptyLines(false) so a genuinely empty line surfaces as a record too (rather
        // than being silently dropped by the lexer), letting the loop below count it the
        // same way as a whitespace-only line - one blank-detection path instead of two.
        try (CsvReader<CsvRecord> reader = CsvReader.builder()
                .fieldSeparator(delimiter)
                .skipEmptyLines(false)
                .ofCsvRecord(text)) {
            boolean first = true;
            for (CsvRecord record : reader) {
                if (first) {
                    // Already identified as the header row by detectHeader(); just drop it.
                    first = false;
                    continue;
                }
                String name = header.isFirstLast()
                        ? joinFirstLast(record, header.vornameIndex(), header.nachnameIndex())
                        : field(record, header.nameIndex());
                if (name.isEmpty()) {
                    blankLinesSkipped++;
                    continue;
                }
                names.add(name);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (CsvParseException e) {
            throw new IllegalArgumentException("Die Datei ist keine gültige CSV-Datei: " + e.getMessage(), e);
        }
        return new RosterParseResult(names, blankLinesSkipped);
    }

    private static String joinFirstLast(CsvRecord record, int vornameIndex, int nachnameIndex) {
        String vorname = field(record, vornameIndex);
        String nachname = field(record, nachnameIndex);
        if (vorname.isEmpty()) {
            return nachname;
        }
        if (nachname.isEmpty()) {
            return vorname;
        }
        return vorname + " " + nachname;
    }

    private static String field(CsvRecord record, int index) {
        String value = record.getFieldCount() > index ? record.getField(index) : "";
        return unescapeFormulaTrigger(value.trim());
    }

    private RosterParseResult parseHeaderless(List<String> lines) {
        List<String> names = new ArrayList<>();
        int blankLinesSkipped = 0;
        for (String line : lines) {
            String name = unescapeFormulaTrigger(line.trim());
            if (name.isEmpty()) {
                blankLinesSkipped++;
                continue;
            }
            names.add(name);
        }
        return new RosterParseResult(names, blankLinesSkipped);
    }

    /**
     * Formats student names as roster CSV bytes: UTF-8 with a BOM and a semicolon
     * delimiter, so German Excel opens umlauts correctly without a manual encoding step.
     * Single "Name" header column, one row per name.
     *
     * <p>A name starting with a formula-trigger character ({@code =+-@}, tab or CR) is
     * prefixed with a single quote (undone by {@link #parseDetailed} on re-import) so Excel
     * treats it as literal text instead of executing it as a formula - student names are
     * free-text by design, so this export must not blindly trust their first character.
     */
    public byte[] format(List<String> names) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(UTF8_BOM);
            try (CsvWriter writer = CsvWriter.builder()
                    .fieldSeparator(';')
                    .lineDelimiter(LineDelimiter.CRLF)
                    .build(out, StandardCharsets.UTF_8)) {
                writer.writeRecord(HEADER_NAME);
                for (String name : names) {
                    writer.writeRecord(escapeFormulaTrigger(name));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String escapeFormulaTrigger(String name) {
        if (!name.isEmpty() && FORMULA_TRIGGER_CHARS.indexOf(name.charAt(0)) >= 0) {
            return "'" + name;
        }
        return name;
    }

    private static String unescapeFormulaTrigger(String name) {
        if (name.length() >= 2 && name.charAt(0) == '\'' && FORMULA_TRIGGER_CHARS.indexOf(name.charAt(1)) >= 0) {
            return name.substring(1);
        }
        return name;
    }

    private static String decode(byte[] content) {
        byte[] withoutBom = stripBom(content);
        CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return strictUtf8.decode(ByteBuffer.wrap(withoutBom)).toString();
        } catch (CharacterCodingException e) {
            return new String(withoutBom, WINDOWS_1252);
        }
    }

    private static byte[] stripBom(byte[] content) {
        if (content.length >= UTF8_BOM.length
                && content[0] == UTF8_BOM[0] && content[1] == UTF8_BOM[1] && content[2] == UTF8_BOM[2]) {
            return Arrays.copyOfRange(content, UTF8_BOM.length, content.length);
        }
        return content;
    }

    /** Splits text into lines on CRLF/CR/LF, dropping one trailing empty line from a final line terminator. */
    private static List<String> splitLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r\n|\r|\n", -1)));
        if (lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * Counts ';' vs ',' on the header line and picks whichever is more frequent. Ties
     * (including a single-column header with neither, e.g. just "Name") default to ';' -
     * both because that's the more common German-Excel dialect and because it matches what
     * {@link #format} itself writes, so a single-column round trip stays self-consistent.
     */
    private static char sniffDelimiter(String headerLine) {
        long semicolons = headerLine.chars().filter(c -> c == ';').count();
        long commas = headerLine.chars().filter(c -> c == ',').count();
        return commas > semicolons ? ',' : ';';
    }
}
