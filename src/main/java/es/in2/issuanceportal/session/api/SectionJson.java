package es.in2.issuanceportal.session.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.stream.Collectors;

/**
 * The serialisation of the section contracts, the same for the module that writes a section and the
 * one that reads it. Errors carry the path of the field, never the content.
 */
final class SectionJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private SectionJson() {
    }

    static String write(Object document) {
        try {
            return MAPPER.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A section contract always serialises", e);
        }
    }

    static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new InvalidSectionException("$");
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonMappingException e) {
            throw new InvalidSectionException(path(e), withoutContent(e));
        } catch (JsonProcessingException e) {
            throw new InvalidSectionException("$", withoutContent(e));
        }
    }

    private static String path(JsonMappingException e) {
        String path = e.getPath().stream()
                .map(reference -> reference.getFieldName() != null
                        ? reference.getFieldName()
                        : "[" + reference.getIndex() + "]")
                .collect(Collectors.joining("."));
        return path.isEmpty() ? "$" : path;
    }

    /** Jackson messages may quote the content; keep only the kind of failure. */
    private static Throwable withoutContent(JsonProcessingException e) {
        return new IllegalArgumentException(e.getClass().getSimpleName());
    }
}
