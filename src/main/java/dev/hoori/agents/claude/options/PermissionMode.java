package dev.hoori.agents.claude.options;

/** How Claude Code asks for permission before running tools. */
public enum PermissionMode implements WireEnum {
    /** CLI prompts for dangerous tools. */
    DEFAULT("default"),
    /** Auto-accept file edits. */
    ACCEPT_EDITS("acceptEdits"),
    /** Plan-only mode, no tool execution. */
    PLAN("plan"),
    /** Allow all tools (use with caution). */
    BYPASS_PERMISSIONS("bypassPermissions"),
    /** Deny anything not pre-approved by allow rules. */
    DONT_ASK("dontAsk"),
    /** A model classifier approves or denies each tool call. */
    AUTO("auto");

    private final String wireValue;

    PermissionMode(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }
}
