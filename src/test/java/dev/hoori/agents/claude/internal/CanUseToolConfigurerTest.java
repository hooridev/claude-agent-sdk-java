package dev.hoori.agents.claude.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.options.PermissionMode;
import dev.hoori.agents.claude.options.Skills;
import dev.hoori.agents.claude.permission.PermissionResult;

class CanUseToolConfigurerTest {

    @Test
    void wholeToolEntries() {
        assertEquals("Read", CanUseToolConfigurer.wholeToolAllowed("Read"));
        assertEquals("Read", CanUseToolConfigurer.wholeToolAllowed("Read()"));
        assertEquals("Read", CanUseToolConfigurer.wholeToolAllowed("Read(*)"));
        assertNull(CanUseToolConfigurer.wholeToolAllowed("Bash(ls:*)"));
        assertNull(CanUseToolConfigurer.wholeToolAllowed("(x)"));
        assertNull(CanUseToolConfigurer.wholeToolAllowed(" "));
    }

    @Test
    void shadowingWarnings() {
        var base = ClaudeAgentOptions.builder().canUseTool((t, i, c) -> PermissionResult.allow());
        assertNull(CanUseToolConfigurer.shadowedWarning(base.allowedTools("Bash(git:*)").build()));
        assertTrue(CanUseToolConfigurer.shadowedWarning(base.allowedTools("Read", "Read()").build())
                .contains("for: Read."));
        assertTrue(CanUseToolConfigurer.shadowedWarning(base.allowedTools().skills(Skills.all()).build())
                .contains("Skill"));
        assertTrue(CanUseToolConfigurer.shadowedWarning(base.permissionMode(PermissionMode.BYPASS_PERMISSIONS).build())
                .contains("bypassPermissions"));
        assertEquals("stdio", CanUseToolConfigurer.configure(base.permissionMode(null).build()).permissionPromptToolName());
    }
}
