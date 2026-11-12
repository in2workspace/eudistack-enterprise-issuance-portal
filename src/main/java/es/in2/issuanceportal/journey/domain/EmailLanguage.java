package es.in2.issuanceportal.journey.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The languages the service has a code email for (design.md §3.6), one for each language some tenant of the
 * portal offers. Spanish is the default.
 */
public enum EmailLanguage {

    ES("es"),
    EN("en");

    private final String code;

    EmailLanguage(String code) {
        this.code = code;
    }

    /** The language tag, which is also the key of the tenant's texts. */
    public String code() {
        return code;
    }

    /**
     * Picks the language from an {@code Accept-Language} header: the first one in order of preference that the
     * service has, by its primary subtag ({@code en-GB} is English). A header that is missing, malformed or
     * asks only for other languages gives Spanish.
     */
    public static EmailLanguage fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return ES;
        }
        List<Range> ranges = new ArrayList<>();
        for (String part : header.split(",")) {
            Range.parse(part).ifPresent(ranges::add);
        }
        // A stable sort keeps the order written among the ranges of the same weight.
        ranges.sort(Comparator.comparingDouble(Range::weight).reversed());
        for (Range range : ranges) {
            for (EmailLanguage language : values()) {
                if (range.primary().equals(language.code)) {
                    return language;
                }
            }
        }
        return ES;
    }

    private record Range(String primary, double weight) {

        static Optional<Range> parse(String part) {
            String[] fields = part.strip().split(";", -1);
            String tag = fields[0].strip().toLowerCase(Locale.ROOT);
            if (tag.isEmpty() || tag.equals("*")) {
                return Optional.empty();
            }
            double weight = 1.0;
            for (int i = 1; i < fields.length; i++) {
                String parameter = fields[i].strip().toLowerCase(Locale.ROOT);
                if (parameter.startsWith("q=")) {
                    try {
                        weight = Double.parseDouble(parameter.substring(2));
                    } catch (NumberFormatException _) {
                        return Optional.empty();
                    }
                }
            }
            if (weight <= 0) {
                return Optional.empty();
            }
            int dash = tag.indexOf('-');
            return Optional.of(new Range(dash < 0 ? tag : tag.substring(0, dash), weight));
        }
    }
}
