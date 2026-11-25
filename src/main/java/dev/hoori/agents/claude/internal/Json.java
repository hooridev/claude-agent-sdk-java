package dev.hoori.agents.claude.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON helpers shared by the whole SDK.
 *
 * <p>The SDK represents free-form JSON the way the Python SDK uses {@code dict}: as
 * {@code Map<String, Object>} trees of {@code String}, {@code Number}, {@code Boolean},
 * {@code List}, {@code Map} and {@code null}. This keeps Jackson out of the public API.
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private Json() {
    }

    /** Serializes any map/list/scalar tree to compact JSON. */
    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /**
     * Parses a JSON object.
     *
     * @throws JacksonException if {@code json} is not a JSON object
     */
    public static Map<String, Object> parseObject(String json) {
        return MAPPER.readValue(json, MAP_TYPE);
    }

    /** Parses any JSON value (object, array or scalar). */
    public static Object parse(String json) {
        return MAPPER.readValue(json, Object.class);
    }

    /** Returns a mutable deep copy of a JSON-like tree. */
    public static Map<String, Object> deepCopy(Map<String, ?> source) {
        return parseObject(write(source));
    }

    // ---------------------------------------------------------------- typed accessors

    public static String string(Map<String, ?> map, String key) {
        return map != null && map.get(key) instanceof String s ? s : null;
    }

    public static Boolean bool(Map<String, ?> map, String key) {
        return map != null && map.get(key) instanceof Boolean b ? b : null;
    }

    public static boolean isTrue(Map<String, ?> map, String key) {
        return Boolean.TRUE.equals(bool(map, key));
    }

    public static Integer integer(Map<String, ?> map, String key) {
        return map != null && map.get(key) instanceof Number n ? n.intValue() : null;
    }

    public static Long longValue(Map<String, ?> map, String key) {
        return map != null && map.get(key) instanceof Number n ? n.longValue() : null;
    }

    public static Double number(Map<String, ?> map, String key) {
        return map != null && map.get(key) instanceof Number n ? n.doubleValue() : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, ?> map, String key) {
        return map != null && map.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    public static List<Object> list(Map<String, ?> map, String key) {
        return map != null && map.get(key) instanceof List<?> l ? new ArrayList<>(l) : null;
    }

    /** Returns the string elements of a list field, or {@code null} if the field is absent. */
    public static List<String> stringList(Map<String, ?> map, String key) {
        List<Object> raw = list(map, key);
        if (raw == null) {
            return null;
        }
        List<String> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof String s) {
                result.add(s);
            }
        }
        return List.copyOf(result);
    }

    /** Returns the object elements of a list field (non-objects are skipped), or {@code null}. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objectList(Map<String, ?> map, String key) {
        List<Object> raw = list(map, key);
        if (raw == null) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof Map<?, ?> m) {
                result.add((Map<String, Object>) m);
            }
        }
        return result;
    }

    /** Builds a mutable, insertion-ordered map from alternating key/value pairs, skipping null values. */
    public static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must have an even length");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }
}
