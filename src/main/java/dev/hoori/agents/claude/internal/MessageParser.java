package dev.hoori.agents.claude.internal;

import static dev.hoori.agents.claude.internal.Json.integer;
import static dev.hoori.agents.claude.internal.Json.longValue;
import static dev.hoori.agents.claude.internal.Json.number;
import static dev.hoori.agents.claude.internal.Json.object;
import static dev.hoori.agents.claude.internal.Json.string;
import static dev.hoori.agents.claude.internal.Json.stringList;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.exception.MessageParseException;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.ContentBlock;
import dev.hoori.agents.claude.message.ConversationResetMessage;
import dev.hoori.agents.claude.message.DeferredToolUse;
import dev.hoori.agents.claude.message.GenericSystemMessage;
import dev.hoori.agents.claude.message.HookEventMessage;
import dev.hoori.agents.claude.message.Message;
import dev.hoori.agents.claude.message.MirrorErrorMessage;
import dev.hoori.agents.claude.message.RateLimitEvent;
import dev.hoori.agents.claude.message.RateLimitInfo;
import dev.hoori.agents.claude.message.ResultMessage;
import dev.hoori.agents.claude.message.ServerToolResultBlock;
import dev.hoori.agents.claude.message.ServerToolUseBlock;
import dev.hoori.agents.claude.message.StreamEvent;
import dev.hoori.agents.claude.message.SystemMessage;
import dev.hoori.agents.claude.message.TaskNotificationMessage;
import dev.hoori.agents.claude.message.TaskProgressMessage;
import dev.hoori.agents.claude.message.TaskStartedMessage;
import dev.hoori.agents.claude.message.TaskUpdatedMessage;
import dev.hoori.agents.claude.message.TextBlock;
import dev.hoori.agents.claude.message.ThinkingBlock;
import dev.hoori.agents.claude.message.ToolResultBlock;
import dev.hoori.agents.claude.message.ToolUseBlock;
import dev.hoori.agents.claude.message.UserMessage;

/** Parses raw CLI messages into typed {@link Message}s (Python: {@code message_parser.parse_message}). */
public final class MessageParser {

    private static final System.Logger LOG = System.getLogger(MessageParser.class.getName());

    private MessageParser() {
    }

    /**
     * Parses one raw message.
     *
     * @return the typed message, or {@code null} for unknown message types (forward compatible)
     * @throws MessageParseException if a known message type lacks a required field
     */
    public static Message parse(Map<String, Object> data) {
        if (data == null) {
            throw new MessageParseException("Invalid message data (null)", null);
        }
        String type = string(data, "type");
        if (type == null || type.isEmpty()) {
            throw new MessageParseException("Message missing 'type' field", data);
        }
        Reader r = new Reader(type, data);
        return switch (type) {
            case "user" -> parseUser(r);
            case "assistant" -> parseAssistant(r);
            case "system" -> parseSystem(r);
            case "result" -> parseResult(r);
            case "stream_event" -> new StreamEvent(r.reqString("uuid"), r.reqString("session_id"),
                    r.reqObject("event"), string(data, "parent_tool_use_id"), string(data, "user_message_uuid"),
                    stringList(data, "user_message_uuids"));
            case "rate_limit_event" -> parseRateLimit(r);
            case "conversation_reset" -> new ConversationResetMessage(r.reqString("new_conversation_id"),
                    r.reqString("uuid"), r.reqString("session_id"));
            default -> {
                LOG.log(Level.DEBUG, "Skipping unknown message type: {0}", type);
                yield null;
            }
        };
    }

    private static Map<String, Object> origin(Map<String, Object> data) {
        Map<String, Object> origin = object(data, "origin");
        return origin != null && origin.get("kind") instanceof String ? origin : null;
    }

    private static UserMessage parseUser(Reader r) {
        Map<String, Object> message = r.reqObject("message");
        Object content = r.req(message, "content");
        Map<String, Object> data = r.data;
        if (content instanceof List<?> list) {
            List<ContentBlock> blocks = new ArrayList<>();
            for (Object raw : list) {
                ContentBlock block = parseBlock(r, raw, false);
                if (block != null) {
                    blocks.add(block);
                }
            }
            return new UserMessage(null, blocks, string(data, "uuid"), string(data, "parent_tool_use_id"),
                    object(data, "tool_use_result"), origin(data));
        }
        return new UserMessage(String.valueOf(content), List.of(), string(data, "uuid"),
                string(data, "parent_tool_use_id"), object(data, "tool_use_result"), origin(data));
    }

    private static AssistantMessage parseAssistant(Reader r) {
        Map<String, Object> message = r.reqObject("message");
        if (!(r.req(message, "content") instanceof List<?> list)) {
            throw new MessageParseException("Invalid assistant content (expected list)", r.data);
        }
        List<ContentBlock> blocks = new ArrayList<>();
        for (Object raw : list) {
            ContentBlock block = parseBlock(r, raw, true);
            if (block != null) {
                blocks.add(block);
            }
        }
        Map<String, Object> data = r.data;
        return new AssistantMessage(blocks, (String) r.req(message, "model"), string(data, "parent_tool_use_id"),
                string(data, "error"), object(message, "usage"), string(message, "id"),
                string(message, "stop_reason"), string(data, "session_id"), string(data, "uuid"),
                string(data, "user_message_uuid"), stringList(data, "user_message_uuids"));
    }

    @SuppressWarnings("unchecked")
    private static ContentBlock parseBlock(Reader r, Object raw, boolean assistant) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new MessageParseException("Invalid content block (expected object)", r.data);
        }
        Map<String, Object> block = (Map<String, Object>) m;
        String blockType = (String) r.req(block, "type");
        return switch (blockType) {
            case "text" -> new TextBlock((String) r.req(block, "text"));
            case "tool_use" -> new ToolUseBlock((String) r.req(block, "id"), (String) r.req(block, "name"),
                    (Map<String, Object>) r.req(block, "input"));
            case "tool_result" -> new ToolResultBlock((String) r.req(block, "tool_use_id"), block.get("content"),
                    Json.bool(block, "is_error"));
            case "thinking" -> assistant
                    ? new ThinkingBlock((String) r.req(block, "thinking"), (String) r.req(block, "signature"))
                    : null;
            case "server_tool_use" -> assistant
                    ? new ServerToolUseBlock((String) r.req(block, "id"), (String) r.req(block, "name"),
                    (Map<String, Object>) r.req(block, "input"))
                    : null;
            case "advisor_tool_result" -> assistant
                    ? new ServerToolResultBlock((String) r.req(block, "tool_use_id"),
                    (Map<String, Object>) r.req(block, "content"))
                    : null;
            default -> null;
        };
    }

    private static SystemMessage parseSystem(Reader r) {
        Map<String, Object> data = r.data;
        String subtype = r.reqString("subtype");
        return switch (subtype) {
            case "hook_started", "hook_response" -> new HookEventMessage(subtype, data, hookEventName(data),
                    string(data, "session_id"), string(data, "uuid"));
            case "task_started" -> new TaskStartedMessage(subtype, data, r.reqString("task_id"),
                    r.reqString("description"), r.reqString("uuid"), r.reqString("session_id"),
                    string(data, "tool_use_id"), string(data, "task_type"));
            case "task_progress" -> new TaskProgressMessage(subtype, data, r.reqString("task_id"),
                    r.reqString("description"), r.reqObject("usage"), r.reqString("uuid"),
                    r.reqString("session_id"), string(data, "tool_use_id"), string(data, "last_tool_name"));
            case "task_notification" -> new TaskNotificationMessage(subtype, data, r.reqString("task_id"),
                    r.reqString("status"), r.reqString("output_file"), r.reqString("summary"),
                    r.reqString("uuid"), r.reqString("session_id"), string(data, "tool_use_id"),
                    object(data, "usage"));
            case "task_updated" -> {
                // Parsed defensively: a lifecycle event must never fail to parse.
                Map<String, Object> patch = object(data, "patch");
                patch = patch == null ? Map.of() : patch;
                String taskId = string(data, "task_id");
                yield new TaskUpdatedMessage(subtype, data, taskId == null ? "" : taskId, patch,
                        string(patch, "status"), string(data, "session_id"), string(data, "uuid"));
            }
            case "mirror_error" -> {
                String error = string(data, "error");
                yield new MirrorErrorMessage(subtype, data, object(data, "key"), error == null ? "" : error);
            }
            default -> new GenericSystemMessage(subtype, data);
        };
    }

    private static String hookEventName(Map<String, Object> data) {
        for (String key : List.of("hook_event", "hook_name", "hook_event_name")) {
            String value = string(data, key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static ResultMessage parseResult(Reader r) {
        Map<String, Object> data = r.data;
        Map<String, Object> deferred = object(data, "deferred_tool_use");
        DeferredToolUse deferredToolUse = deferred == null ? null
                : new DeferredToolUse((String) r.req(deferred, "id"), (String) r.req(deferred, "name"),
                object(deferred, "input"));
        return new ResultMessage(
                r.reqString("subtype"),
                ((Number) r.req(data, "duration_ms")).longValue(),
                ((Number) r.req(data, "duration_api_ms")).longValue(),
                (Boolean) r.req(data, "is_error"),
                ((Number) r.req(data, "num_turns")).intValue(),
                r.reqString("session_id"),
                string(data, "stop_reason"),
                number(data, "total_cost_usd"),
                object(data, "usage"),
                string(data, "result"),
                data.get("structured_output"),
                object(data, "modelUsage"),
                Json.list(data, "permission_denials"),
                deferredToolUse,
                stringList(data, "errors"),
                integer(data, "api_error_status"),
                string(data, "uuid"),
                string(data, "terminal_reason"),
                origin(data),
                string(data, "user_message_uuid"),
                stringList(data, "user_message_uuids"));
    }

    private static RateLimitEvent parseRateLimit(Reader r) {
        Map<String, Object> info = r.reqObject("rate_limit_info");
        RateLimitInfo rateLimitInfo = new RateLimitInfo(
                (String) r.req(info, "status"),
                longValue(info, "resetsAt"),
                string(info, "rateLimitType"),
                number(info, "utilization"),
                string(info, "overageStatus"),
                longValue(info, "overageResetsAt"),
                string(info, "overageDisabledReason"),
                info);
        return new RateLimitEvent(rateLimitInfo, r.reqString("uuid"), r.reqString("session_id"));
    }

    /** Required-field access that reports the message type, like Python's {@code KeyError} handling. */
    private record Reader(String type, Map<String, Object> data) {

        Object req(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (value == null) {
                throw new MessageParseException(
                        "Missing required field in " + type + " message: '" + key + "'", data);
            }
            return value;
        }

        String reqString(String key) {
            return String.valueOf(req(data, key));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> reqObject(String key) {
            if (!(req(data, key) instanceof Map<?, ?> m)) {
                throw new MessageParseException("Field '" + key + "' in " + type + " message is not an object", data);
            }
            return (Map<String, Object>) m;
        }
    }
}
