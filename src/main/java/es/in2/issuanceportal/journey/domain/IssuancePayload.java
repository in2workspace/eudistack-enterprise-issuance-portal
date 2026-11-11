package es.in2.issuanceportal.journey.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code payload} of the issuance request from the tenant's data placement (design.md §3.3):
 * each attestation field says whether its value is a field of the verified identity
 * ({@code identity.<field>}) or a claim translated from the authentic source
 * ({@code authentic_source.<claim>}). Nothing outside the placement goes in: no document id, email or
 * country unless the tenant placed it.
 */
public final class IssuancePayload {

    static final String IDENTITY = "identity";
    static final String AUTHENTIC_SOURCE = "authentic_source";

    private IssuancePayload() {
    }

    /**
     * @param placement    attestation field → origin of its value
     * @param identity     the verified identity by field name ({@code given_name}, {@code family_name}, ...)
     * @param sourceClaims the claims translated from the authentic source, by claim name
     * @throws IncompletePlacementException when the placement is empty, a field has an unknown origin, or the
     *                                      origin has no value
     */
    public static Map<String, Object> build(Map<String, String> placement, Map<String, String> identity,
                                            Map<String, Object> sourceClaims) {
        if (placement.isEmpty()) {
            throw new IncompletePlacementException("data_placement");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        placement.forEach((field, origin) -> payload.put(field, valueOf(field, origin, identity, sourceClaims)));
        return payload;
    }

    private static Object valueOf(String field, String origin, Map<String, String> identity,
                                  Map<String, Object> sourceClaims) {
        int dot = origin == null ? -1 : origin.indexOf('.');
        if (dot <= 0 || dot == origin.length() - 1) {
            throw new IncompletePlacementException(field);
        }
        String name = origin.substring(dot + 1);
        Object value = switch (origin.substring(0, dot)) {
            case IDENTITY -> identity.get(name);
            case AUTHENTIC_SOURCE -> sourceClaims.get(name);
            default -> null;
        };
        if (value == null) {
            throw new IncompletePlacementException(field);
        }
        return value;
    }
}
