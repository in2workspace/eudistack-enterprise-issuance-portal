package es.in2.issuanceportal.authenticsource.infrastructure.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAdapter;
import es.in2.issuanceportal.authenticsource.application.port.SourceSecrets;
import es.in2.issuanceportal.authenticsource.domain.Holder;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.MismatchedField;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import es.in2.issuanceportal.authenticsource.domain.SourceRequest;
import es.in2.issuanceportal.authenticsource.domain.SourceRequests;
import es.in2.issuanceportal.authenticsource.domain.config.SourceContract;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenProvider;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The adapter for sources that offer a contract of requests (design.md AD-4): one search, one notice of data
 * not correct, with the names and values the tenant's {@code contract} gives. HTTPS only, no redirections, and
 * the configured timeout on every call. A source's value is copied as it gives it: lists stay lists.
 *
 * <p>Every unexpected answer is thrown, and the service turns it into an unavailable source. What is logged
 * is the kind of failure and the HTTP status: never a body, the identity, a reference nor a credential.
 */
@Slf4j
@Component
class HttpAuthenticSourceAdapter implements AuthenticSourceAdapter {

    static final String TYPE = SourceSettings.HTTP_ADAPTER;
    /** More than a few requests with their data is not a source of this contract. */
    private static final int MAX_BODY_BYTES = 1 << 20;
    private static final String PATH_SEPARATOR = "\\.";
    private static final TypeReference<Map<String, Object>> DATA = new TypeReference<>() {
    };

    /** The source's answer is not what its contract says. Carries only which expectation failed. */
    static final class SourceCallException extends RuntimeException {

        SourceCallException(String expectation) {
            super(expectation, null, false, false);
        }
    }

    private final ObjectMapper json;
    private final SourceSecrets secrets;
    private final SystemTokenProvider systemTokens;
    private final SSLContext sslContext;
    private final Map<Duration, RestClient> clients = new ConcurrentHashMap<>();

    @Autowired
    HttpAuthenticSourceAdapter(ObjectMapper json, SourceSecrets secrets, SystemTokenProvider systemTokens) {
        this(json, secrets, systemTokens, null);
    }

    /** @param sslContext what the calls trust; {@code null} is the JVM's, the only one production uses */
    HttpAuthenticSourceAdapter(ObjectMapper json, SourceSecrets secrets, SystemTokenProvider systemTokens,
                               SSLContext sslContext) {
        this.json = json;
        this.secrets = secrets;
        this.systemTokens = systemTokens;
        this.sslContext = sslContext;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<IdentifierType> supportedIdentifierTypes() {
        return Set.of(IdentifierType.PERSONAL_ID, IdentifierType.COLEGIADO);
    }

    @Override
    public SourceRequests lookup(TenantId tenant, String attestationType, Holder holder, SourceSettings settings,
                                 Duration timeout) {
        SourceContract contract = settings.contract();
        Map<String, Object> body = body(contract.request(), holder, null);
        byte[] answer = post(tenant, settings, contract.lookupPath(), body, timeout, true);
        return translate(parse(answer), contract);
    }

    @Override
    public boolean supportsDispute(SourceSettings settings) {
        return settings.contract() != null && settings.contract().supportsDispute();
    }

    @Override
    public void dispute(TenantId tenant, String attestationType, Holder holder, String sourceRef,
                        SourceSettings settings, Duration timeout) {
        SourceContract contract = settings.contract();
        post(tenant, settings, contract.disputePath(), body(contract.disputeRequest(), holder, sourceRef), timeout,
                false);
    }

    private static Map<String, Object> body(Map<String, String> names, Holder holder, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, names, "given_name", holder.givenName());
        put(body, names, "family_names", holder.familyNames());
        put(body, names, "identifier", holder.identifier().value());
        put(body, names, "identifier_type", holder.identifier().type().code());
        put(body, names, "reference", reference);
        return body;
    }

    /** A field without a name in the contract is not sent. A name with dots nests the value. */
    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> body, Map<String, String> names, String field, String value) {
        String name = names.get(field);
        if (name == null || value == null) {
            return;
        }
        String[] path = name.split(PATH_SEPARATOR);
        Map<String, Object> level = body;
        for (int i = 0; i < path.length - 1; i++) {
            level = (Map<String, Object>) level.computeIfAbsent(path[i], _ -> new LinkedHashMap<String, Object>());
        }
        level.put(path[path.length - 1], value);
    }

    /**
     * @param lookup {@code true} for the search, which must answer exactly 200 with a body; the notice takes any 2xx
     */
    private byte[] post(TenantId tenant, SourceSettings settings, String path, Map<String, Object> body,
                        Duration timeout, boolean lookup) {
        URI target = target(settings.url(), path);
        String bearer = bearer(tenant, settings);
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(body);
        } catch (IOException _) {
            throw new SourceCallException("request not serialisable");
        }
        return client(timeout).post().uri(target)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .body(payload)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    boolean accepted = lookup ? status == 200 : response.getStatusCode().is2xxSuccessful();
                    if (!accepted) {
                        log.warn("Authentic source of tenant {} answered HTTP {}", tenant, status);
                        throw new SourceCallException("unexpected status");
                    }
                    return lookup ? response.getBody().readNBytes(MAX_BODY_BYTES + 1) : new byte[0];
                }, true);
    }

    private static URI target(String url, String path) {
        URI base = URI.create(url);
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null) {
            throw new SourceCallException("url is not https");
        }
        return URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) + path : url + path);
    }

    private String bearer(TenantId tenant, SourceSettings settings) {
        return switch (settings.auth()) {
            case SYSTEM_TOKEN -> systemTokens.tokenFor(tenant).value();
            case SECRET -> secrets.secret(tenant, settings.secretRef());
        };
    }

    /** One client per timeout, with no redirection: a 3xx is an answer the contract does not know. */
    private RestClient client(Duration timeout) {
        return clients.computeIfAbsent(timeout, limit -> {
            HttpClient.Builder http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(limit);
            if (sslContext != null) {
                http.sslContext(sslContext);
            }
            JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http.build());
            requests.setReadTimeout(limit);
            return RestClient.builder().requestFactory(requests).build();
        });
    }

    private JsonNode parse(byte[] answer) {
        if (answer.length > MAX_BODY_BYTES) {
            throw new SourceCallException("answer too large");
        }
        try {
            return json.readTree(answer);
        } catch (IOException _) {
            throw new SourceCallException("answer is not JSON");
        }
    }

    private SourceRequests translate(JsonNode root, SourceContract contract) {
        if (root == null || !root.isObject()) {
            throw new SourceCallException("answer is not an object");
        }
        Optional<String> reason = contract.responseName("reason").flatMap(name -> text(root, name));
        if (reason.isPresent()) {
            String code = contract.translate("reason", reason.get())
                    .orElseThrow(() -> new SourceCallException("unknown reason"));
            return switch (Arrays.stream(NotConfirmedReason.values()).filter(known -> known.code().equals(code))
                    .findFirst().orElseThrow(() -> new SourceCallException("unknown reason"))) {
                case NOT_FOUND -> SourceRequests.notFound();
                case NO_REQUESTS -> SourceRequests.noRequests();
            };
        }
        JsonNode list = at(root, contract.response().get("requests"));
        if (list == null || !list.isArray()) {
            throw new SourceCallException("no list of requests");
        }
        if (list.isEmpty()) {
            return SourceRequests.noRequests();
        }
        List<SourceRequest> requests = new ArrayList<>();
        for (JsonNode item : list) {
            requests.add(request(item, contract));
        }
        return new SourceRequests.Requests(requests);
    }

    private SourceRequest request(JsonNode item, SourceContract contract) {
        if (!item.isObject()) {
            throw new SourceCallException("request is not an object");
        }
        Map<String, String> names = contract.response();
        String reference = text(item, names.get("reference"))
                .orElseThrow(() -> new SourceCallException("request without reference"));
        RequestStatus status = text(item, names.get("status"))
                .flatMap(value -> contract.translate("status", value)).flatMap(RequestStatus::fromCode)
                .filter(known -> known != RequestStatus.UNDER_REVIEW)
                .orElseThrow(() -> new SourceCallException("request without a known status"));
        return new SourceRequest(reference, status, mismatchedFields(item, contract), renewal(item, names),
                optionalText(item, names, "requested_by"), requestedAt(item, names),
                optionalText(item, names, "display_identifier"), optionalText(item, names, "organization"),
                status == RequestStatus.AVAILABLE ? data(item, names) : Map.of());
    }

    private List<MismatchedField> mismatchedFields(JsonNode item, SourceContract contract) {
        Optional<String> name = contract.responseName("mismatched_fields");
        JsonNode node = name.map(field -> at(item, field)).orElse(null);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new SourceCallException("mismatched fields are not a list");
        }
        List<MismatchedField> fields = new ArrayList<>();
        for (JsonNode field : node) {
            fields.add(scalar(field).flatMap(value -> contract.translate("mismatched_fields", value))
                    .flatMap(MismatchedField::fromCode)
                    .orElseThrow(() -> new SourceCallException("unknown mismatched field")));
        }
        return fields;
    }

    private static boolean renewal(JsonNode item, Map<String, String> names) {
        JsonNode node = at(item, names.get("renewal"));
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw new SourceCallException("renewal is not a boolean");
        }
        return node.booleanValue();
    }

    private static Instant requestedAt(JsonNode item, Map<String, String> names) {
        return text(item, names.get("requested_at")).map(value -> {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException _) {
                throw new SourceCallException("requested_at is not a date");
            }
        }).orElse(null);
    }

    private Map<String, Object> data(JsonNode item, Map<String, String> names) {
        JsonNode node = at(item, names.get("data"));
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new SourceCallException("data is not an object");
        }
        return json.convertValue(node, DATA);
    }

    private static String optionalText(JsonNode item, Map<String, String> names, String field) {
        return text(item, names.get(field)).orElse(null);
    }

    private static Optional<String> text(JsonNode node, String name) {
        return scalar(at(node, name));
    }

    private static Optional<String> scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (!node.isValueNode() || node.isBoolean()) {
            throw new SourceCallException("value is not text");
        }
        String value = node.asText();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static JsonNode at(JsonNode node, String name) {
        if (name == null) {
            return null;
        }
        JsonNode current = node;
        for (String step : name.split(PATH_SEPARATOR)) {
            current = current == null ? null : current.get(step);
        }
        return current;
    }
}
