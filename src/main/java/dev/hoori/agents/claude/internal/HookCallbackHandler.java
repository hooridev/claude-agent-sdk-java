package dev.hoori.agents.claude.internal;

import java.util.Map;

import dev.hoori.agents.claude.hook.HookCallback;
import dev.hoori.agents.claude.hook.HookContext;
import dev.hoori.agents.claude.hook.HookInput;
import dev.hoori.agents.claude.hook.HookOutput;

/** Answers {@code hook_callback} requests by running the registered callback. */
final class HookCallbackHandler implements ControlRequestHandler {

    private final HookRegistry registry;

    HookCallbackHandler(HookRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String subtype() {
        return "hook_callback";
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> request) throws Exception {
        String callbackId = Json.string(request, "callback_id");
        HookCallback callback = registry.callback(callbackId);
        if (callback == null) {
            throw new IllegalStateException("No hook callback found for ID: " + callbackId);
        }
        HookOutput output = callback.call(new HookInput(Json.object(request, "input")),
                Json.string(request, "tool_use_id"), new HookContext());
        return output == null ? Map.of() : output.fields();
    }
}
