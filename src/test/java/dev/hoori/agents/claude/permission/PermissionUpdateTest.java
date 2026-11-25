package dev.hoori.agents.claude.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hoori.agents.claude.options.PermissionMode;

class PermissionUpdateTest {

    @Test
    void roundTripsThroughTheWireFormat() {
        PermissionUpdate update = PermissionUpdate.addRules(PermissionBehavior.DENY,
                PermissionRuleValue.of("Bash", "rm -rf"), PermissionRuleValue.of("Write"))
                .withDestination(PermissionUpdateDestination.PROJECT_SETTINGS);
        Map<String, Object> wire = update.toWire();
        assertEquals(Map.of("type", "addRules", "destination", "projectSettings", "behavior", "deny",
                "rules", List.of(Map.of("toolName", "Bash", "ruleContent", "rm -rf"), Map.of("toolName", "Write"))),
                wire);
        assertEquals(update, PermissionUpdate.fromWire(wire));
        assertEquals(Map.of("type", "setMode", "destination", "session", "mode", "plan"),
                PermissionUpdate.setMode(PermissionMode.PLAN).toWire());
    }

    @Test
    void incompleteUpdatesFailFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new PermissionUpdate("addRules", List.of(), null, null, null, null).toWire());
        assertThrows(IllegalArgumentException.class,
                () -> new PermissionUpdate("setMode", null, null, null, null, null).toWire());
        assertThrows(IllegalArgumentException.class,
                () -> new PermissionUpdate("addDirectories", null, null, null, null, null).toWire());
        assertThrows(IllegalArgumentException.class,
                () -> new PermissionUpdate("future", null, null, null, null, null).toWire());
    }
}
