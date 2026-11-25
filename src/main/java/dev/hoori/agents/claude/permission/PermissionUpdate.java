package dev.hoori.agents.claude.permission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.options.PermissionMode;
import dev.hoori.agents.claude.options.WireEnum;

/**
 * A change to the permission configuration, sent with an allow result or received as a suggestion.
 *
 * @param type        {@code addRules}, {@code replaceRules}, {@code removeRules}, {@code setMode},
 *                    {@code addDirectories} or {@code removeDirectories}
 * @param destination where the update applies; {@code null} is sent as {@code session}
 */
public record PermissionUpdate(
        String type,
        List<PermissionRuleValue> rules,
        PermissionBehavior behavior,
        PermissionMode mode,
        List<String> directories,
        PermissionUpdateDestination destination) {

    public static PermissionUpdate addRules(PermissionBehavior behavior, PermissionRuleValue... rules) {
        return new PermissionUpdate("addRules", List.of(rules), behavior, null, null, null);
    }

    public static PermissionUpdate replaceRules(PermissionBehavior behavior, PermissionRuleValue... rules) {
        return new PermissionUpdate("replaceRules", List.of(rules), behavior, null, null, null);
    }

    public static PermissionUpdate removeRules(PermissionBehavior behavior, PermissionRuleValue... rules) {
        return new PermissionUpdate("removeRules", List.of(rules), behavior, null, null, null);
    }

    public static PermissionUpdate setMode(PermissionMode mode) {
        return new PermissionUpdate("setMode", null, null, mode, null, null);
    }

    public static PermissionUpdate addDirectories(String... directories) {
        return new PermissionUpdate("addDirectories", null, null, null, List.of(directories), null);
    }

    public static PermissionUpdate removeDirectories(String... directories) {
        return new PermissionUpdate("removeDirectories", null, null, null, List.of(directories), null);
    }

    /** Returns a copy with the given destination. */
    public PermissionUpdate withDestination(PermissionUpdateDestination destination) {
        return new PermissionUpdate(type, rules, behavior, mode, directories, destination);
    }

    /**
     * Converts to the control protocol format.
     *
     * <p>Claude Code rejects an update lacking a field its type needs (and then ignores every update
     * in the same response), so this fails fast instead: {@code rules} and {@code behavior} for the
     * rule types, {@code mode} for {@code setMode}, {@code directories} for the directory types.
     *
     * @throws IllegalArgumentException if a required field is missing or the type is unknown
     */
    public Map<String, Object> toWire() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("destination", destination == null ? "session" : destination.wireValue());
        switch (type == null ? "" : type) {
            case "addRules", "replaceRules", "removeRules" -> {
                if (rules == null) {
                    throw new IllegalArgumentException("PermissionUpdate(type=" + type
                            + ") has no rules. Set rules to a list of PermissionRuleValue.");
                }
                if (behavior == null) {
                    throw new IllegalArgumentException("PermissionUpdate(type=" + type
                            + ") has no behavior. Set behavior to allow, deny or ask.");
                }
                List<Map<String, Object>> wireRules = new ArrayList<>();
                for (PermissionRuleValue rule : rules) {
                    // ruleContent left out when null: that is how Claude Code expects a whole-tool rule.
                    wireRules.add(Json.obj("toolName", rule.toolName(), "ruleContent", rule.ruleContent()));
                }
                result.put("rules", wireRules);
                result.put("behavior", behavior.wireValue());
            }
            case "setMode" -> {
                if (mode == null) {
                    throw new IllegalArgumentException(
                            "PermissionUpdate(type=setMode) has no mode. Set mode to a permission mode.");
                }
                result.put("mode", mode.wireValue());
            }
            case "addDirectories", "removeDirectories" -> {
                if (directories == null) {
                    throw new IllegalArgumentException("PermissionUpdate(type=" + type
                            + ") has no directories. Set directories to a list of paths.");
                }
                result.put("directories", directories);
            }
            default -> throw new IllegalArgumentException("PermissionUpdate has type " + type
                    + ", which this version of the SDK cannot send. Use one of addRules, replaceRules, "
                    + "removeRules, setMode, addDirectories or removeDirectories.");
        }
        return result;
    }

    /** Parses the control protocol format (inverse of {@link #toWire()}). Unknown enum values become {@code null}. */
    public static PermissionUpdate fromWire(Map<String, Object> data) {
        List<PermissionRuleValue> rules = null;
        List<Map<String, Object>> rawRules = Json.objectList(data, "rules");
        if (rawRules != null) {
            rules = new ArrayList<>();
            for (Map<String, Object> rule : rawRules) {
                rules.add(new PermissionRuleValue(Json.string(rule, "toolName"), Json.string(rule, "ruleContent")));
            }
        }
        return new PermissionUpdate(
                Json.string(data, "type"),
                rules,
                WireEnum.fromWire(PermissionBehavior.class, Json.string(data, "behavior")),
                WireEnum.fromWire(PermissionMode.class, Json.string(data, "mode")),
                Json.stringList(data, "directories"),
                WireEnum.fromWire(PermissionUpdateDestination.class, Json.string(data, "destination")));
    }
}
