package dev.hoori.agents.claude.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;

/**
 * Result of a tool call.
 *
 * @param content content items: {@code {"type":"text","text":...}}, {@code {"type":"image","data":...,"mimeType":...}},
 *                {@code resource_link} or {@code resource} (text resources only)
 * @param isError whether the call failed
 */
public record ToolResult(List<Map<String, Object>> content, boolean isError) {

    public ToolResult {
        content = List.copyOf(content);
    }

    public static ToolResult text(String text) {
        return new ToolResult(List.of(Json.obj("type", "text", "text", text)), false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(List.of(Json.obj("type", "text", "text", message)), true);
    }

    public static ToolResult image(String base64Data, String mimeType) {
        return new ToolResult(List.of(Json.obj("type", "image", "data", base64Data, "mimeType", mimeType)), false);
    }

    /** Returns a copy with one more content item. */
    public ToolResult with(Map<String, Object> item) {
        List<Map<String, Object>> items = new ArrayList<>(content);
        items.add(item);
        return new ToolResult(items, isError);
    }
}
