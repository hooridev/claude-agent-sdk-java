package dev.hoori.agents.claude.internal;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.permission.CanUseTool;
import dev.hoori.agents.claude.permission.PermissionResult;
import dev.hoori.agents.claude.permission.PermissionUpdate;
import dev.hoori.agents.claude.permission.ToolPermissionContext;

/** Answers {@code can_use_tool} requests with the user's {@link CanUseTool} callback. */
final class PermissionRequestHandler implements ControlRequestHandler {

    private static final System.Logger LOG = System.getLogger(PermissionRequestHandler.class.getName());

    private final CanUseTool canUseTool;

    PermissionRequestHandler(CanUseTool canUseTool) {
        this.canUseTool = canUseTool;
    }

    @Override
    public String subtype() {
        return "can_use_tool";
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> request) throws Exception {
        if (canUseTool == null) {
            throw new IllegalStateException("canUseTool callback is not provided");
        }
        Map<String, Object> originalInput = Json.object(request, "input");
        ToolPermissionContext context = new ToolPermissionContext(
                suggestions(request),
                Json.string(request, "tool_use_id"),
                Json.string(request, "agent_id"),
                Json.string(request, "blocked_path"),
                Json.string(request, "decision_reason"),
                Json.string(request, "title"),
                Json.string(request, "display_name"),
                Json.string(request, "description"));
        PermissionResult result = canUseTool.check(Json.string(request, "tool_name"),
                originalInput == null ? Map.of() : originalInput, context);
        Map<String, Object> response = new LinkedHashMap<>();
        switch (result) {
            case PermissionResult.Allow allow -> {
                response.put("behavior", "allow");
                response.put("updatedInput", allow.updatedInput() != null ? allow.updatedInput() : originalInput);
                if (allow.updatedPermissions() != null) {
                    List<Map<String, Object>> updates = updatesToWire(allow.updatedPermissions());
                    if (updates != null) {
                        response.put("updatedPermissions", updates);
                    }
                }
            }
            case PermissionResult.Deny deny -> {
                response.put("behavior", "deny");
                response.put("message", deny.message());
                if (deny.interrupt()) {
                    response.put("interrupt", true);
                }
            }
            case null -> throw new IllegalStateException(
                    "Tool permission callback must return a PermissionResult, got null");
        }
        return response;
    }

    private static List<PermissionUpdate> suggestions(Map<String, Object> request) {
        List<PermissionUpdate> result = new ArrayList<>();
        List<Map<String, Object>> raw = Json.objectList(request, "permission_suggestions");
        if (raw != null) {
            for (Map<String, Object> suggestion : raw) {
                result.add(PermissionUpdate.fromWire(suggestion));
            }
        }
        return result;
    }

    /** All or nothing: the update that failed may be a restriction the others were meant to come with. */
    private static List<Map<String, Object>> updatesToWire(List<PermissionUpdate> updates) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (int i = 0; i < updates.size(); i++) {
            try {
                wire.add(updates.get(i).toWire());
            } catch (IllegalArgumentException e) {
                LOG.log(Level.WARNING, "The canUseTool callback allowed the tool call, but none of its permission "
                        + "updates were applied, because updatedPermissions[{0}] cannot be sent: {1}", i,
                        e.getMessage());
                return null;
            }
        }
        return wire;
    }
}
