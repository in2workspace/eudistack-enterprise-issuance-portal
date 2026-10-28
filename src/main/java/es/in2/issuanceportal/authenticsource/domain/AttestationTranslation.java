package es.in2.issuanceportal.authenticsource.domain;

import es.in2.issuanceportal.authenticsource.domain.config.AttributeMapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates the source's attributes into the attestation's claims, generalising {@code transform} and
 * {@code putIfPresent} of {@code MockDataAcquisitionAdapter} in {@code eudistack-enterprise-backend}: the
 * mapping comes from the tenant's configuration instead of four fixed fields.
 */
public final class AttestationTranslation {

    private AttestationTranslation() {
    }

    /** Each mapped attribute the source returned with a value, under its claim name, in mapping order. */
    public static Map<String, Object> translate(List<AttributeMapping> mapping, SourceRecord record) {
        Map<String, Object> claims = new LinkedHashMap<>();
        for (AttributeMapping entry : mapping) {
            Object value = record.attributes().get(entry.attribute());
            if (value != null) {
                claims.put(entry.claim(), value);
            }
        }
        return Collections.unmodifiableMap(claims);
    }
}
