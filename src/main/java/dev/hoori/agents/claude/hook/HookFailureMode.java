package dev.hoori.agents.claude.hook;

import dev.hoori.agents.claude.options.WireEnum;

/** What happens when a hook callback fails. */
public enum HookFailureMode implements WireEnum {
    /** A failure counts as "no decision" (default). */
    CONTINUE("continue"),
    /**
     * A callback that throws, or returns a value the CLI rejects, blocks the action. Connecting to
     * a CLI too old to honor this raises {@code CliConnectionException}.
     */
    BLOCK("block");

    private final String wireValue;

    HookFailureMode(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }
}
