package dev.hoori.agents.claude.mcp;

import java.util.Map;

import dev.hoori.agents.claude.internal.Json;

/**
 * MCP tool annotations.
 *
 * @param maxResultSizeChars Claude Code extension: maximum result size before it is truncated
 */
public record ToolAnnotations(
        String title,
        Boolean readOnlyHint,
        Boolean destructiveHint,
        Boolean idempotentHint,
        Boolean openWorldHint,
        Integer maxResultSizeChars) {

    public static ToolAnnotations readOnly() {
        return new ToolAnnotations(null, true, null, null, null, null);
    }

    public Map<String, Object> toWire() {
        return Json.obj("title", title, "readOnlyHint", readOnlyHint, "destructiveHint", destructiveHint,
                "idempotentHint", idempotentHint, "openWorldHint", openWorldHint,
                "maxResultSizeChars", maxResultSizeChars);
    }
}
