package de.notenfuchs.security;

import de.notenfuchs.domain.Teacher;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/**
 * %dev-only convenience: seeds a {@link Teacher} row for the effective dev-mode subject (see
 * {@link CurrentUser#DEV_USER_SUBJECT}/{@link CurrentUser#DEV_USER_SUBJECT_PROPERTY}), plus one
 * synthetic second colleague, so the "add a co-owner" dropdown (see {@code
 * ClassUiResource#addClassTeacher}) has something to select locally.
 *
 * <p>Necessary because {@link TeacherDirectoryRecorder}'s {@code AuthenticationSuccessEvent}
 * never fires under %dev's permit-all HTTP policy (see that class's Javadoc) - without this, the
 * dev-mode subject would never get a directory row at all just from browsing around, and there is
 * no way to become a second real identity locally to populate one for a "colleague" either. Not
 * active under %test: ITs seed exactly the {@link Teacher} rows each test needs, explicitly (see
 * {@code ClassTeacherIT}/{@code ClassTeacherE2EIT}) - ambient startup data would only make those
 * tests more order-dependent, not less.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class DevTeacherSeeder {

    @Inject
    @ConfigProperty(name = CurrentUser.DEV_USER_SUBJECT_PROPERTY, defaultValue = CurrentUser.DEV_USER_SUBJECT)
    String devUserSubject;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        ensureTeacher(devUserSubject, null, null);
        ensureTeacher(devUserSubject + "-2", "kollege@example.test", "Kollege (Dev)");
    }

    private void ensureTeacher(String subject, String email, String displayName) {
        if (Teacher.count("subject", subject) > 0) {
            return;
        }
        Teacher teacher = new Teacher();
        teacher.subject = subject;
        teacher.email = email;
        teacher.displayName = displayName;
        teacher.firstSeenAt = Instant.now();
        teacher.lastSeenAt = Instant.now();
        teacher.persist();
    }
}
