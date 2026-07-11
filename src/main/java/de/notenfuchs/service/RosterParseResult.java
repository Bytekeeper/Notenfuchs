package de.notenfuchs.service;

import java.util.List;

/**
 * Result of {@link CsvRosterService#parseDetailed(byte[])}: the parsed names plus how many
 * blank lines were skipped, so the import preview page can show that count alongside the
 * new/duplicate breakdown.
 */
public record RosterParseResult(List<String> names, int blankLinesSkipped) {
}
