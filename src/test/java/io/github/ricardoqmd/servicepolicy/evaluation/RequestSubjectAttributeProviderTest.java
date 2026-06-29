package io.github.ricardoqmd.servicepolicy.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RequestSubjectAttributeProviderTest {

    private final RequestSubjectAttributeProvider provider = new RequestSubjectAttributeProvider();

    @Test
    void nullRequestAttributesYieldEmptyBag() {
        assertTrue(provider.attributesFor("u1", null).isEmpty());
    }

    @Test
    void returnsCallerAssertedAttributesUnchanged() {
        Map<String, Object> in = Map.of("area", "A");
        assertEquals(in, provider.attributesFor("u1", in));
    }
}
