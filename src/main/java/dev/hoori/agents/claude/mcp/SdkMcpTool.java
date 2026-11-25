package dev.hoori.agents.claude.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.hoori.agents.claude.internal.Json;

/**
 * A tool served by an {@link SdkMcpServer} (Python: the {@code @tool} decorator).
 *
 * <pre>{@code
 * SdkMcpTool add = SdkMcpTool.builder("add", "Add two numbers")
 *         .param("a", "number")
 *         .param("b", "number")
 *         .handler(args -> ToolResult.text(String.valueOf(
 *                 ((Number) args.get("a")).doubleValue() + ((Number) args.get("b")).doubleValue())))
 *         .build();
 * }</pre>
 *
 * @param inputSchema JSON schema of the arguments
 */
public record SdkMcpTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        ToolHandler handler,
        ToolAnnotations annotations) {

    public SdkMcpTool {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handler, "handler");
        inputSchema = inputSchema == null ? Json.obj("type", "object", "properties", Map.of()) : inputSchema;
    }

    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    /** The {@code tools/list} entry of this tool. */
    Map<String, Object> toWire() {
        Map<String, Object> wire = Json.obj("name", name, "description", description, "inputSchema", inputSchema);
        if (annotations != null) {
            wire.put("annotations", annotations.toWire());
            if (annotations.maxResultSizeChars() != null) {
                wire.put("_meta", Map.of("anthropic/maxResultSizeChars", annotations.maxResultSizeChars()));
            }
        }
        return wire;
    }

    /** Builder for {@link SdkMcpTool}. */
    public static final class Builder {
        private final String name;
        private final String description;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();
        private Map<String, Object> inputSchema;
        private ToolHandler handler;
        private ToolAnnotations annotations;

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        /** A required parameter of a JSON schema type ({@code string}, {@code number}, {@code integer}, ...). */
        public Builder param(String paramName, String jsonType) {
            return param(paramName, jsonType, null);
        }

        /** A required parameter with a description. */
        public Builder param(String paramName, String jsonType, String paramDescription) {
            properties.put(paramName, Json.obj("type", jsonType, "description", paramDescription));
            required.add(paramName);
            return this;
        }

        /** An optional parameter. */
        public Builder optionalParam(String paramName, String jsonType, String paramDescription) {
            properties.put(paramName, Json.obj("type", jsonType, "description", paramDescription));
            return this;
        }

        /** A complete JSON schema; replaces any {@code param} calls. */
        public Builder inputSchema(Map<String, Object> schema) {
            this.inputSchema = schema;
            return this;
        }

        public Builder handler(ToolHandler handler) {
            this.handler = handler;
            return this;
        }

        public Builder annotations(ToolAnnotations annotations) {
            this.annotations = annotations;
            return this;
        }

        public SdkMcpTool build() {
            Map<String, Object> schema = inputSchema != null ? inputSchema
                    : Json.obj("type", "object", "properties", properties, "required", List.copyOf(required));
            return new SdkMcpTool(name, description, schema, handler, annotations);
        }
    }
}
