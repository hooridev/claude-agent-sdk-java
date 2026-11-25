package dev.hoori.agents.claude.internal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonSchemaValidatorTest {

    private static final Map<String, Object> SCHEMA = Json.parseObject("""
            {"type":"object","required":["n","tags"],
             "properties":{"n":{"type":"integer"},"tags":{"type":"array","items":{"type":"string"}},
               "mode":{"enum":["a","b"]},"v":{"anyOf":[{"type":"string"},{"type":"null"}]}}}""");

    @Test
    void acceptsValidInput() {
        assertNull(JsonSchemaValidator.validate(Map.of("n", 3, "tags", List.of("x")), SCHEMA));
        assertNull(JsonSchemaValidator.validate(Json.parseObject("{\"n\":3.0,\"tags\":[],\"mode\":\"a\",\"v\":null}"),
                SCHEMA));
    }

    @Test
    void rejectsInvalidInput() {
        assertNotNull(JsonSchemaValidator.validate(Map.of("tags", List.of()), SCHEMA));
        assertNotNull(JsonSchemaValidator.validate(Map.of("n", 1.5, "tags", List.of()), SCHEMA));
        assertNotNull(JsonSchemaValidator.validate(Map.of("n", 1, "tags", List.of(1)), SCHEMA));
        assertNotNull(JsonSchemaValidator.validate(Map.of("n", 1, "tags", List.of(), "mode", "c"), SCHEMA));
        assertNotNull(JsonSchemaValidator.validate(Map.of("n", 1, "tags", List.of(), "v", 2), SCHEMA));
        assertNotNull(JsonSchemaValidator.validate("text", SCHEMA));
    }
}
