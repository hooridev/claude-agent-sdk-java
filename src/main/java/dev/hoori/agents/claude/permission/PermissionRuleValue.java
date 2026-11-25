package dev.hoori.agents.claude.permission;

/**
 * A permission rule.
 *
 * @param toolName    the tool the rule is for
 * @param ruleContent what the rule matches within the tool (e.g. {@code npm test} for Bash);
 *                    {@code null} or empty makes a rule covering every use of the tool
 */
public record PermissionRuleValue(String toolName, String ruleContent) {

    public static PermissionRuleValue of(String toolName) {
        return new PermissionRuleValue(toolName, null);
    }

    public static PermissionRuleValue of(String toolName, String ruleContent) {
        return new PermissionRuleValue(toolName, ruleContent);
    }
}
