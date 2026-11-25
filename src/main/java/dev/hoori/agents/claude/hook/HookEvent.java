package dev.hoori.agents.claude.hook;

import dev.hoori.agents.claude.options.WireEnum;

/** Hook events an SDK callback can be registered for. */
public enum HookEvent implements WireEnum {
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    POST_TOOL_USE_FAILURE("PostToolUseFailure"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    STOP("Stop"),
    SUBAGENT_STOP("SubagentStop"),
    PRE_COMPACT("PreCompact"),
    NOTIFICATION("Notification"),
    SUBAGENT_START("SubagentStart"),
    PERMISSION_REQUEST("PermissionRequest");

    private final String wireValue;

    HookEvent(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }
}
