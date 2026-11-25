package dev.hoori.agents.claude.permission;

import java.util.List;
import java.util.Map;

/** The answer of a {@link CanUseTool} callback. */
public sealed interface PermissionResult {

    /** Allow the call with its original input. */
    static Allow allow() {
        return new Allow(null, null);
    }

    /** Allow the call with a modified input. */
    static Allow allow(Map<String, Object> updatedInput) {
        return new Allow(updatedInput, null);
    }

    /** Deny the call; {@code message} is shown to the model. */
    static Deny deny(String message) {
        return new Deny(message, false);
    }

    /** Deny the call and optionally interrupt the whole turn. */
    static Deny deny(String message, boolean interrupt) {
        return new Deny(message, interrupt);
    }

    /**
     * Allow result.
     *
     * @param updatedInput       replacement input, or {@code null} to keep the original
     * @param updatedPermissions permission updates to apply with this allow. If any cannot be sent, none
     *                           are sent (a warning is logged) and the call is still allowed.
     */
    record Allow(Map<String, Object> updatedInput, List<PermissionUpdate> updatedPermissions)
            implements PermissionResult {

        public Allow withUpdatedPermissions(PermissionUpdate... updates) {
            return new Allow(updatedInput, List.of(updates));
        }
    }

    /** Deny result. */
    record Deny(String message, boolean interrupt) implements PermissionResult {
        public Deny {
            message = message == null ? "" : message;
        }
    }
}
