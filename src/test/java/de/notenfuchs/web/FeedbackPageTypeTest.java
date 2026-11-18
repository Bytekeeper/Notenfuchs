package de.notenfuchs.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedbackPageTypeTest {

    @Test
    void idsQueryAndFragmentAreRemoved() {
        assertEquals("/subjects/{id}/grid", FeedbackResource.pageType("/subjects/42/grid?focus=7#x"));
        assertEquals("/subjects/{id}/grid/students/{id}", FeedbackResource.pageType("/subjects/42/grid/students/1234"));
        assertEquals("/classes", FeedbackResource.pageType("/classes"));
    }

    @Test
    void anythingThatIsNotAPlainPathIsStoredAsUnknown() {
        assertEquals("unbekannt", FeedbackResource.pageType(null));
        assertEquals("unbekannt", FeedbackResource.pageType("https://example.com/classes"));
        assertEquals("unbekannt", FeedbackResource.pageType("/klassen/7b Müller"));
    }
}
