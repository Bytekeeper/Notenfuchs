package de.notenfuchs.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain JUnit 5 unit tests for {@link CsvRosterService}. Like {@link GradeServiceTest},
 * deliberately does NOT use {@code @QuarkusTest} - this service is pure byte[]/String
 * manipulation with no DB involvement.
 */
class CsvRosterServiceTest {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final CsvRosterService service = new CsvRosterService();

    @Test
    void parse_semicolonDelimitedWithHeader_readsFirstColumnOnly() {
        byte[] content = utf8("Name;Klasse\r\nAnna Meyer;5a\r\nBen Müller;5b\r\n");

        List<String> names = service.parse(content);

        assertEquals(List.of("Anna Meyer", "Ben Müller"), names);
    }

    @Test
    void parse_commaDelimitedWithHeader() {
        byte[] content = utf8("Name,Kommentar\nAnna Meyer,ok\nBen Müller,ok\n");

        List<String> names = service.parse(content);

        assertEquals(List.of("Anna Meyer", "Ben Müller"), names);
    }

    @Test
    void parse_utf8WithBom_decodesUmlauts() {
        byte[] text = utf8("Name\r\nMüller\r\nGräßlin\r\nSchön\r\n");
        byte[] content = new byte[UTF8_BOM.length + text.length];
        System.arraycopy(UTF8_BOM, 0, content, 0, UTF8_BOM.length);
        System.arraycopy(text, 0, content, UTF8_BOM.length, text.length);

        List<String> names = service.parse(content);

        assertEquals(List.of("Müller", "Gräßlin", "Schön"), names);
    }

    @Test
    void parse_windows1252_fallsBackWhenBytesAreNotValidUtf8() {
        // ä/ö/ü/ß encode to single bytes (0xE4/0xF6/0xFC/0xDF) in Windows-1252 that are not
        // valid standalone UTF-8 sequences, so strict UTF-8 decoding fails and the service
        // must fall back to Windows-1252 - what German Excel writes by default.
        byte[] content = "Name\r\nMüller\r\nGräßlin\r\nSchön\r\n".getBytes(WINDOWS_1252);

        List<String> names = service.parse(content);

        assertEquals(List.of("Müller", "Gräßlin", "Schön"), names);
    }

    @Test
    void parse_crlfLineEndings() {
        byte[] content = utf8("Name\r\nAnna Meyer\r\nBen Müller\r\n");

        assertEquals(List.of("Anna Meyer", "Ben Müller"), service.parse(content));
    }

    @Test
    void parse_lfLineEndings() {
        byte[] content = utf8("Name\nAnna Meyer\nBen Müller\n");

        assertEquals(List.of("Anna Meyer", "Ben Müller"), service.parse(content));
    }

    @Test
    void parse_vornameNachnameHeader_realWorldExportFile_combinesIntoFullNames() {
        // src/test/resources/roster/schueler-vorname-nachname.csv is a real school-admin
        // export shape: comma-delimited, UTF-8, "Vorname,Nachname,Alter,Klasse,Geburtsdatum"
        // header. Previously this fell through to the header-less path (no bare "Name"
        // column), gluing the header and every row into one garbage "name" per line.
        byte[] content = resource("roster/schueler-vorname-nachname.csv");

        List<String> names = service.parse(content);

        assertEquals(List.of(
                "Leon Müller", "Sarah Schmidt", "Tim Weber", "Emma Wagner", "Lukas Hoffmann",
                "Anna Schäfer", "Jonas Krämer", "Julia Zimmermann", "Felix Schulz", "Lisa Fischer",
                "Marius König", "Clara Wolf", "Benjamin Becker", "Sophie Richter", "Elias Groß"
        ), names);
    }

    @Test
    void parse_vornameNachnameHeader_columnOrderAndExtraColumnsDontMatter() {
        // Nachname before Vorname, plus an unrelated leading column - the pair is found by
        // name, not position, and the combined name is always "Vorname Nachname" regardless
        // of the file's column order.
        byte[] content = utf8("Klasse;Nachname;Vorname\r\n8a;Meyer;Anna\r\n9b;Müller;Ben\r\n");

        assertEquals(List.of("Anna Meyer", "Ben Müller"), service.parse(content));
    }

    @Test
    void parse_vornameNachnameHeader_windows1252WithUmlauts() {
        byte[] content = "Vorname;Nachname\r\nJörg;Grüßgott\r\nAnjaß;König\r\n".getBytes(WINDOWS_1252);

        assertEquals(List.of("Jörg Grüßgott", "Anjaß König"), service.parse(content));
    }

    @Test
    void parse_vornameNachnameHeader_quotedFieldContainingTheDelimiter() {
        byte[] content = utf8("Vorname;Nachname\r\nAnna;\"Meyer; Schulz\"\r\nBen;Müller\r\n");

        assertEquals(List.of("Anna Meyer; Schulz", "Ben Müller"), service.parse(content));
    }

    @Test
    void parse_headerAbsent_treatsEveryLineAsAName() {
        byte[] content = utf8("Anna Meyer\nBen Müller\n");

        assertEquals(List.of("Anna Meyer", "Ben Müller"), service.parse(content));
    }

    @Test
    void parse_headerNameIsCaseInsensitive() {
        byte[] content = utf8("name\nAnna Meyer\n");

        assertEquals(List.of("Anna Meyer"), service.parse(content));
    }

    @Test
    void parse_headerAbsent_doesNotSplitOnAnUnquotedCommaWithinAName() {
        // Regression test: a headerless file has no declared column structure, so an
        // unquoted comma written as part of a "Nachname, Vorname" style name (common when a
        // teacher hand-types a roster rather than exporting one from Excel) must not be
        // mistaken for a delimiter and split the given name off.
        byte[] content = utf8("Meyer, Anna\nMüller, Ben\n");

        assertEquals(List.of("Meyer, Anna", "Müller, Ben"), service.parse(content));
    }

    @Test
    void parse_quotedFieldContainingTheDelimiter_isNotSplit() {
        byte[] content = utf8("Name;Klasse\n\"Meyer; Anna\";5a\nBen Müller;5b\n");

        List<String> names = service.parse(content);

        assertEquals(List.of("Meyer; Anna", "Ben Müller"), names);
    }

    @Test
    void parse_blankLinesAreSkipped() {
        byte[] content = utf8("Name\nAnna Meyer\n\nBen Müller\n   \n");

        assertEquals(List.of("Anna Meyer", "Ben Müller"), service.parse(content));
    }

    @Test
    void parseDetailed_countsSkippedBlankLines() {
        byte[] content = utf8("Name\nAnna Meyer\n\nBen Müller\n   \n");

        RosterParseResult result = service.parseDetailed(content);

        assertEquals(List.of("Anna Meyer", "Ben Müller"), result.names());
        assertEquals(2, result.blankLinesSkipped());
    }

    @Test
    void parse_trimsWhitespaceAroundNames() {
        byte[] content = utf8("Name\n  Anna Meyer  \n\tBen Müller\t\n");

        assertEquals(List.of("Anna Meyer", "Ben Müller"), service.parse(content));
    }

    @Test
    void format_writesUtf8BomAndSemicolonDelimitedNameColumn() {
        byte[] content = service.format(List.of("Anna Meyer", "Ben Müller"));

        assertEquals((byte) 0xEF, content[0]);
        assertEquals((byte) 0xBB, content[1]);
        assertEquals((byte) 0xBF, content[2]);

        String withoutBom = new String(content, UTF8_BOM.length, content.length - UTF8_BOM.length,
                StandardCharsets.UTF_8);
        assertEquals("Name\r\nAnna Meyer\r\nBen Müller\r\n", withoutBom);
    }

    @Test
    void roundTrip_exportThenImport_yieldsSameNames() {
        List<String> original = List.of("Anna Meyer", "Ben Müller-Schön", "Meyer; Anna");

        byte[] exported = service.format(original);
        List<String> reimported = service.parse(exported);

        assertEquals(original, reimported);
    }

    @Test
    void format_escapesLeadingFormulaTriggerCharacters_toPreventCsvInjection() {
        // Student names are free-text (CLAUDE.md). A name starting with =/+/-/@ would
        // otherwise be interpreted as a formula by Excel - the exact application this export
        // targets - so it must be neutralized with a leading single quote.
        byte[] content = service.format(List.of("=1+1", "+Anna", "-Ben", "@Meyer", "Normal Name"));

        String withoutBom = new String(content, UTF8_BOM.length, content.length - UTF8_BOM.length,
                StandardCharsets.UTF_8);
        assertEquals("Name\r\n'=1+1\r\n'+Anna\r\n'-Ben\r\n'@Meyer\r\nNormal Name\r\n", withoutBom);
    }

    @Test
    void roundTrip_formulaTriggerCharacterNames_areRecoveredExactly() {
        List<String> original = List.of("=1+1", "+Anna", "-Ben", "@Meyer");

        byte[] exported = service.format(original);
        List<String> reimported = service.parse(exported);

        assertEquals(original, reimported);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] resource(String classpathPath) {
        try (InputStream in = CsvRosterServiceTest.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalArgumentException("Test resource not found: " + classpathPath);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
