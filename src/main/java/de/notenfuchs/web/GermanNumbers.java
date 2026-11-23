package de.notenfuchs.web;

import io.quarkus.qute.EngineBuilder;
import io.quarkus.qute.TemplateExtension;
import io.quarkus.qute.WithPriority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import java.math.BigDecimal;

/**
 * Numbers on pages show with a decimal comma and no trailing zeros ("2,5", "50"). Every
 * {@code BigDecimal} a template prints gets this format, so no page can forget it; inputs and
 * data attributes that the browser reads as numbers use {@code {value.plain}} instead.
 */
@Singleton
public class GermanNumbers {

    public static String display(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** For {@code <input type="number">} and data attributes: browsers only read the dot. */
    @TemplateExtension
    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    void registerResultMapper(@Observes EngineBuilder builder) {
        builder.addResultMapper(new io.quarkus.qute.ResultMapper() {
            // Only the first applicable mapper runs; this one must beat the HTML escaper (a number needs no escaping).
            @Override
            public int getPriority() {
                return WithPriority.DEFAULT_PRIORITY + 10;
            }

            @Override
            public boolean appliesTo(io.quarkus.qute.TemplateNode.Origin origin, Object result) {
                return result instanceof BigDecimal;
            }

            @Override
            public String map(Object result, io.quarkus.qute.Expression expression) {
                return display((BigDecimal) result);
            }
        });
    }
}
