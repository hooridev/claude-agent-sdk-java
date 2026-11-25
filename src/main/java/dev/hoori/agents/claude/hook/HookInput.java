package dev.hoori.agents.claude.hook;

import java.util.Collections;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;

/**
 * The input of a hook callback. Wraps the raw object so fields of newer CLI versions stay
 * reachable via {@link #get(String)}; typed accessors return {@code null} when a field is absent
 * or does not apply to the event.
 */
public record HookInput(Map<String, Object> raw) {

    public HookInput {
        raw = raw == null ? Map.of() : Collections.unmodifiableMap(raw);
    }

    /** Any field by its wire name. */
    public Object get(String key) {
        return raw.get(key);
    }

    /** The event name, e.g. {@code PreToolUse}. */
    public String hookEventName() {
        return Json.string(raw, "hook_event_name");
    }

    public String sessionId() {
        return Json.string(raw, "session_id");
    }

    public String transcriptPath() {
        return Json.string(raw, "transcript_path");
    }

    public String cwd() {
        return Json.string(raw, "cwd");
    }

    public String permissionMode() {
        return Json.string(raw, "permission_mode");
    }

    /** Sub-agent id when the hook fires inside a sub-agent. */
    public String agentId() {
        return Json.string(raw, "agent_id");
    }

    public String agentType() {
        return Json.string(raw, "agent_type");
    }

    // Tool events (PreToolUse, PostToolUse, PostToolUseFailure, PermissionRequest)

    public String toolName() {
        return Json.string(raw, "tool_name");
    }

    public Map<String, Object> toolInput() {
        return Json.object(raw, "tool_input");
    }

    public String toolUseId() {
        return Json.string(raw, "tool_use_id");
    }

    /** PostToolUse: the tool's response (any JSON value). */
    public Object toolResponse() {
        return raw.get("tool_response");
    }

    /** PostToolUseFailure: the error text. */
    public String error() {
        return Json.string(raw, "error");
    }

    // UserPromptSubmit

    public String prompt() {
        return Json.string(raw, "prompt");
    }

    // Stop / SubagentStop

    public Boolean stopHookActive() {
        return Json.bool(raw, "stop_hook_active");
    }

    public String lastAssistantMessage() {
        return Json.string(raw, "last_assistant_message");
    }

    // Notification

    public String message() {
        return Json.string(raw, "message");
    }

    // PreCompact: "manual" or "auto"

    public String trigger() {
        return Json.string(raw, "trigger");
    }
}
