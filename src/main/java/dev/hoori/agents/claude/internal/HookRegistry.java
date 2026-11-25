package dev.hoori.agents.claude.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.hoori.agents.claude.hook.HookCallback;
import dev.hoori.agents.claude.hook.HookEvent;
import dev.hoori.agents.claude.hook.HookFailureMode;
import dev.hoori.agents.claude.hook.HookMatcher;

/** Assigns callback ids to hook callbacks and builds the {@code hooks} field of the initialize request. */
final class HookRegistry {

    private final Map<String, HookCallback> callbacks = new ConcurrentHashMap<>();
    private final Map<String, Object> initializeConfig = new LinkedHashMap<>();
    private final boolean blocksOnFailure;

    HookRegistry(Map<HookEvent, List<HookMatcher>> hooks) {
        int nextId = 0;
        boolean blocks = false;
        for (Map.Entry<HookEvent, List<HookMatcher>> entry : hooks.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            List<Map<String, Object>> matchers = new ArrayList<>();
            for (HookMatcher matcher : entry.getValue()) {
                List<String> ids = new ArrayList<>();
                for (HookCallback callback : matcher.hooks()) {
                    String id = "hook_" + nextId++;
                    callbacks.put(id, callback);
                    ids.add(id);
                }
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("matcher", matcher.matcher());
                config.put("hookCallbackIds", ids);
                if (matcher.timeout() != null) {
                    config.put("timeout", matcher.timeout());
                }
                if (matcher.onFailure() != null) {
                    config.put("onFailure", matcher.onFailure().wireValue());
                    blocks |= matcher.onFailure() == HookFailureMode.BLOCK;
                }
                matchers.add(config);
            }
            initializeConfig.put(entry.getKey().wireValue(), matchers);
        }
        this.blocksOnFailure = blocks;
    }

    boolean isEmpty() {
        return callbacks.isEmpty() && initializeConfig.isEmpty();
    }

    /** The {@code hooks} value of the initialize request, or {@code null} if no hooks are configured. */
    Map<String, Object> initializeConfig() {
        return initializeConfig.isEmpty() ? null : initializeConfig;
    }

    /** Whether any matcher asked for {@code onFailure: block}. */
    boolean blocksOnFailure() {
        return blocksOnFailure;
    }

    HookCallback callback(String id) {
        return callbacks.get(id);
    }
}
