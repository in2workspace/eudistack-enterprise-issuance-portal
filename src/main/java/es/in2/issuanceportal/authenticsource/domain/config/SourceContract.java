package es.in2.issuanceportal.authenticsource.domain.config;

import es.in2.issuanceportal.authenticsource.domain.MismatchedField;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The names a source uses for what the port carries, and the equivalence of its values (design.md AD-4).
 * A field without a name is not sent, or not read. A name may be a path with dots, {@code holder.given_name},
 * for a source that nests it. The messages of a wrong contract name the field, never a value.
 *
 * @param lookupPath     path of the search, after the source's {@code url}
 * @param disputePath    path of the notice of data not correct; none means the source does not support it
 * @param request        names of the holder's data in the search: {@code given_name}, {@code family_names},
 *                       {@code identifier} (required) and {@code identifier_type}
 * @param disputeRequest names in the notice: the same as {@code request} plus {@code reference}
 * @param response       names in the answer: {@code requests}, {@code reference} and {@code status} (required),
 *                       {@code reason}, {@code mismatched_fields}, {@code renewal}, {@code requested_by},
 *                       {@code requested_at}, {@code display_identifier}, {@code organization} and {@code data}
 * @param values         the source's values for {@code reason}, {@code status} and {@code mismatched_fields},
 *                       each to the port's. A group left out means the source already uses the port's values
 */
public record SourceContract(String lookupPath, String disputePath, Map<String, String> request,
                             Map<String, String> disputeRequest, Map<String, String> response,
                             Map<String, Map<String, String>> values) {

    private static final String IDENTIFIER = "identifier";
    private static final String REFERENCE = "reference";
    private static final String REASON = "reason";
    private static final String STATUS = "status";
    private static final String DISPUTE_REQUEST = "contract.dispute_request";

    private static final Set<String> HOLDER_NAMES = Set.of("given_name", "family_names", IDENTIFIER,
            "identifier_type");
    private static final Set<String> DISPUTE_NAMES = Set.of("given_name", "family_names", IDENTIFIER,
            "identifier_type", REFERENCE);
    private static final Set<String> RESPONSE_NAMES = Set.of("requests", REFERENCE, STATUS, REASON,
            "mismatched_fields", "renewal", "requested_by", "requested_at", "display_identifier", "organization",
            "data");
    private static final Set<String> VALUE_GROUPS = Set.of(REASON, STATUS, "mismatched_fields");

    public SourceContract {
        requirePath(lookupPath, "contract.lookup_path");
        disputePath = disputePath == null || disputePath.isBlank() ? null : disputePath;
        if (disputePath != null) {
            requirePath(disputePath, "contract.dispute_path");
        }
        request = names(request, HOLDER_NAMES, "contract.request");
        require(request, IDENTIFIER, "contract.request");
        disputeRequest = names(disputeRequest, DISPUTE_NAMES, DISPUTE_REQUEST);
        if (disputePath != null) {
            require(disputeRequest, IDENTIFIER, DISPUTE_REQUEST);
            require(disputeRequest, REFERENCE, DISPUTE_REQUEST);
        }
        response = names(response, RESPONSE_NAMES, "contract.response");
        for (String required : new String[] {"requests", REFERENCE, STATUS}) {
            require(response, required, "contract.response");
        }
        values = valueGroups(values);
    }

    public boolean supportsDispute() {
        return disputePath != null;
    }

    public Optional<String> responseName(String field) {
        return Optional.ofNullable(response.get(field));
    }

    /** The port's value for the source's, or empty when there is none; the identity when the group is absent. */
    public Optional<String> translate(String group, String sourceValue) {
        Map<String, String> equivalences = values.get(group);
        return equivalences == null ? Optional.of(sourceValue) : Optional.ofNullable(equivalences.get(sourceValue));
    }

    private static void requirePath(String path, String field) {
        if (path == null || !path.startsWith("/")) {
            throw new InvalidSourceConfigException(field + " must be a path starting with /");
        }
    }

    private static void require(Map<String, String> names, String name, String field) {
        if (!names.containsKey(name)) {
            throw new InvalidSourceConfigException(field + "." + name + " is required");
        }
    }

    private static Map<String, String> names(Map<String, String> names, Set<String> allowed, String field) {
        Map<String, String> checked = new LinkedHashMap<>();
        if (names != null) {
            names.forEach((name, value) -> {
                if (!allowed.contains(name)) {
                    throw new InvalidSourceConfigException(field + " has an unknown field");
                }
                if (value == null || value.isBlank()) {
                    throw new InvalidSourceConfigException(field + "." + name + " is empty");
                }
                checked.put(name, value);
            });
        }
        return Collections.unmodifiableMap(checked);
    }

    private static Map<String, Map<String, String>> valueGroups(Map<String, Map<String, String>> values) {
        Map<String, Map<String, String>> checked = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((group, equivalences) -> {
                if (!VALUE_GROUPS.contains(group)) {
                    throw new InvalidSourceConfigException("contract.values has an unknown group");
                }
                checked.put(group, equivalences(group, equivalences));
            });
        }
        return Collections.unmodifiableMap(checked);
    }

    private static Map<String, String> equivalences(String group, Map<String, String> equivalences) {
        if (equivalences == null || equivalences.isEmpty()) {
            throw new InvalidSourceConfigException("contract.values." + group + " is empty");
        }
        Map<String, String> checked = new LinkedHashMap<>();
        equivalences.forEach((source, port) -> {
            if (!isPortValue(group, port)) {
                throw new InvalidSourceConfigException("contract.values." + group + " has an unknown value");
            }
            checked.put(source, port);
        });
        return Collections.unmodifiableMap(checked);
    }

    private static boolean isPortValue(String group, String value) {
        if (value == null) {
            return false;
        }
        return switch (group) {
            case REASON -> Set.of(NotConfirmedReason.NOT_FOUND.code(), NotConfirmedReason.NO_REQUESTS.code())
                    .contains(value);
            case STATUS -> RequestStatus.fromCode(value).filter(status -> status != RequestStatus.UNDER_REVIEW)
                    .isPresent();
            default -> MismatchedField.fromCode(value).isPresent();
        };
    }

    @Override
    public String toString() {
        return "SourceContract[redacted]";
    }
}
