package de.notenfuchs.web;

/**
 * Shared filename handling for the {@code Content-Disposition} header of file downloads
 * (.xlsx grid export, roster CSV export) - browsers that ignore the RFC 5987
 * {@code filename*=UTF-8''...} fallback still need a plain ASCII {@code filename="..."}.
 */
final class DownloadFilenames {

    private DownloadFilenames() {
    }

    /** Strips German umlauts/eszett and any other non-ASCII-safe character for the plain filename attribute. */
    static String sanitize(String raw) {
        String transliterated = raw
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                .replace("ß", "ss");
        return transliterated.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }
}
