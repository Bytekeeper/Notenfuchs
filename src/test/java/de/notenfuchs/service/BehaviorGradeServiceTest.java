package de.notenfuchs.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain JUnit 5 unit tests, like {@link GradeServiceTest}: no {@code @QuarkusTest}, no database. */
class BehaviorGradeServiceTest {

    private final BehaviorGradeService service = new BehaviorGradeService();

    @Test
    void average_plainMeanOfValues() {
        BigDecimal result = service.average(List.of(
                new BigDecimal("2"), new BigDecimal("3"), new BigDecimal("4")));
        assertEquals(new BigDecimal("3.00"), result);
    }

    @Test
    void average_roundsToTwoDecimalsHalfUp() {
        BigDecimal result = service.average(List.of(new BigDecimal("1"), new BigDecimal("2")));
        assertEquals(new BigDecimal("1.50"), result);

        BigDecimal rounded = service.average(List.of(
                new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("2")));
        // (1+1+2)/3 = 1.3333... -> half-up to 1.33
        assertEquals(new BigDecimal("1.33"), rounded);
    }

    @Test
    void average_emptyOrNull_returnsNull() {
        assertNull(service.average(List.of()));
        assertNull(service.average(null));
    }

    @Test
    void average_singleValue_returnsThatValueScaled() {
        assertEquals(new BigDecimal("2.00"), service.average(List.of(new BigDecimal("2"))));
    }

    @Test
    void isBorderline_exampleRange_2_4_to_2_6_isTrue() {
        assertTrue(service.isBorderline(new BigDecimal("2.40")));
        assertTrue(service.isBorderline(new BigDecimal("2.50")));
        assertTrue(service.isBorderline(new BigDecimal("2.60")));
    }

    @Test
    void isBorderline_justOutsideRange_isFalse() {
        assertFalse(service.isBorderline(new BigDecimal("2.39")));
        assertFalse(service.isBorderline(new BigDecimal("2.61")));
    }

    @Test
    void isBorderline_appliesAtEveryWholeNumberBoundary_notJustAround2() {
        assertTrue(service.isBorderline(new BigDecimal("1.50")));
        assertTrue(service.isBorderline(new BigDecimal("5.45")));
        assertFalse(service.isBorderline(new BigDecimal("5.00")));
        assertFalse(service.isBorderline(new BigDecimal("1.00")));
    }

    @Test
    void isBorderline_null_isFalse() {
        assertFalse(service.isBorderline(null));
    }
}
