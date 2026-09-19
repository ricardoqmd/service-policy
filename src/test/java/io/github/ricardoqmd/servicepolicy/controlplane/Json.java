package io.github.ricardoqmd.servicepolicy.controlplane;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Parses a JSON object literal of a test into the untyped document shape the mappers take. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
        // static helper
    }

    static Map<String, Object> parse(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
