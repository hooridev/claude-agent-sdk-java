package dev.hoori.agents.claude.permission;

import dev.hoori.agents.claude.options.WireEnum;

/** Where a permission update applies and is kept. */
public enum PermissionUpdateDestination implements WireEnum {
    USER_SETTINGS("userSettings"),
    PROJECT_SETTINGS("projectSettings"),
    LOCAL_SETTINGS("localSettings"),
    /** In memory until the session ends (default). */
    SESSION("session");

    private final String wireValue;

    PermissionUpdateDestination(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }
}
