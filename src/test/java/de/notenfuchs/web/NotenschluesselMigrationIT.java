package de.notenfuchs.web;

import de.notenfuchs.domain.Assessment;
import de.notenfuchs.domain.PointsGradeBand;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fresh database gets the demo class (V3) with an old key (60 → 1, 20 → 6) before V13 runs, so
 * it shows what an update does to existing points Leistungen: they slide as before, nothing changes.
 */
@QuarkusTest
class NotenschluesselMigrationIT {

    @Test
    void oldKeysKeepSliding() {
        Assessment assessment = Assessment.find(
                "name = 'Klassenarbeit 2 (Punkte)' and category.subject.schoolClass.name = 'Demo-Klasse 8b'").firstResult();
        assertTrue(assessment.pointsSliding);
        assertEquals(0, new BigDecimal("60").compareTo(assessment.maxPoints));
        assertEquals(2, PointsGradeBand.count("assessment.id", assessment.id));
    }
}
