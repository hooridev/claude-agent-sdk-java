package dev.hoori.agents.claude.permission;

import dev.hoori.agents.claude.options.WireEnum;

/** Behavior of a permission rule. */
public enum PermissionBehavior implements WireEnum {
    ALLOW("allow"), DENY("deny"), ASK("ask");

    private final String wireValue;

    PermissionBehavior(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }
}
