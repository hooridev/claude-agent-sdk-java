package dev.hoori.agents.claude.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON schema validator for SDK MCP tool arguments.
 *
 * <p>shortcut: supports only {@code type}, {@code properties}, {@code required}, {@code items},
 * {@code enum} and {@code anyOf} (the Python SDK uses the full {@code jsonschema} package); add
 * keywords here if tools need them, or validate inside the handler.
 */
public final class JsonSchemaValidator {

    private JsonSchemaValidator() {
    }

    /**
     * Validates {@code instance} against {@code schema}.
     *
     * @return {@code null} if valid, else a human-readable error message
     */
    public static String validate(Object instance, Map<String, Object> schema) {
        return validate(instance, schema, "$");
    }

    @SuppressWarnings("unchecked")
    private static String validate(Object instance, Map<String, Object> schema, String path) {
        if (schema == null) {
            return null;
        }
        String typeError = checkType(instance, schema.get("type"), path);
        if (typeError != null) {
            return typeError;
        }
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(instance)) {
            return path + ": " + Json.write(instance) + " is not one of " + Json.write(allowed);
        }
        if (schema.get("anyOf") instanceof List<?> options) {
            boolean matched = options.stream()
                    .anyMatch(option -> option instanceof Map<?, ?> m
                            && validate(instance, (Map<String, Object>) m, path) == null);
            if (!matched) {
                return path + ": " + Json.write(instance) + " does not match any of the allowed schemas";
            }
        }
        if (instance instanceof Map<?, ?> object) {
            if (schema.get("required") instanceof List<?> required) {
                for (Object key : required) {
                    if (!object.containsKey(key)) {
                        return path + ": '" + key + "' is a required property";
                    }
                }
            }
            if (schema.get("properties") instanceof Map<?, ?> properties) {
                for (Map.Entry<?, ?> property : properties.entrySet()) {
                    if (object.containsKey(property.getKey()) && property.getValue() instanceof Map<?, ?> sub) {
                        String error = validate(object.get(property.getKey()), (Map<String, Object>) sub,
                                path + "." + property.getKey());
                        if (error != null) {
                            return error;
                        }
                    }
                }
            }
        }
        if (instance instanceof List<?> array && schema.get("items") instanceof Map<?, ?> items) {
            for (int i = 0; i < array.size(); i++) {
                String error = validate(array.get(i), (Map<String, Object>) items, path + "[" + i + "]");
                if (error != null) {
                    return error;
                }
            }
        }
        return null;
    }

    private static String checkType(Object instance, Object type, String path) {
        if (type == null) {
            return null;
        }
        List<?> types = type instanceof List<?> l ? l : List.of(type);
        for (Object t : types) {
            if (matchesType(instance, String.valueOf(t))) {
                return null;
            }
        }
        return path + ": " + Json.write(instance) + " is not of type " + Json.write(type);
    }

    private static boolean matchesType(Object instance, String type) {
        return switch (type) {
            case "string" -> instance instanceof String;
            case "number" -> instance instanceof Number;
            case "integer" -> isInteger(instance);
            case "boolean" -> instance instanceof Boolean;
            case "object" -> instance instanceof Map;
            case "array" -> instance instanceof List;
            case "null" -> instance == null;
            default -> true;
        };
    }

    private static boolean isInteger(Object instance) {
        return switch (instance) {
            case Integer _, Long _, Short _, Byte _, BigInteger _ -> true;
            case BigDecimal d -> d.stripTrailingZeros().scale() <= 0;
            case Double d -> d == Math.rint(d) && !d.isInfinite();
            case Float f -> f == Math.rint(f) && !f.isInfinite();
            case null, default -> false;
        };
    }
}
