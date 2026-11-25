package dev.hoori.agents.claude.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hoori.agents.claude.exception.MessageParseException;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.ConversationResetMessage;
import dev.hoori.agents.claude.message.GenericSystemMessage;
import dev.hoori.agents.claude.message.HookEventMessage;
import dev.hoori.agents.claude.message.RateLimitEvent;
import dev.hoori.agents.claude.message.ResultMessage;
import dev.hoori.agents.claude.message.ServerToolResultBlock;
import dev.hoori.agents.claude.message.ServerToolUseBlock;
import dev.hoori.agents.claude.message.StreamEvent;
import dev.hoori.agents.claude.message.TaskNotificationMessage;
import dev.hoori.agents.claude.message.TaskStartedMessage;
import dev.hoori.agents.claude.message.TaskUpdatedMessage;
import dev.hoori.agents.claude.message.TextBlock;
import dev.hoori.agents.claude.message.ThinkingBlock;
import dev.hoori.agents.claude.message.ToolResultBlock;
import dev.hoori.agents.claude.message.ToolUseBlock;
import dev.hoori.agents.claude.message.UserMessage;

class MessageParserTest {

    private static Object parse(String json) {
        return MessageParser.parse(Json.parseObject(json));
    }

    @Test
    void userMessageWithStringContent() {
        UserMessage message = (UserMessage) parse("""
                {"type":"user","uuid":"u1","message":{"role":"user","content":"hello"},
                 "origin":{"kind":"human"}}""");
        assertTrue(message.isText());
        assertEquals("hello", message.text());
        assertEquals("u1", message.uuid());
        assertEquals(Map.of("kind", "human"), message.origin());
    }

    @Test
    void userMessageWithBlocksIgnoresOriginWithoutKind() {
        UserMessage message = (UserMessage) parse("""
                {"type":"user","parent_tool_use_id":"p","origin":{"x":1},
                 "message":{"content":[{"type":"text","text":"hi"},
                   {"type":"tool_result","tool_use_id":"t1","content":"out","is_error":false},
                   {"type":"image","source":{}}]}}""");
        assertEquals(2, message.content().size());
        assertEquals(new TextBlock("hi"), message.content().get(0));
        ToolResultBlock result = (ToolResultBlock) message.content().get(1);
        assertEquals("out", result.contentText());
        assertEquals(false, result.isError());
        assertNull(message.origin());
        assertEquals("p", message.parentToolUseId());
    }

    @Test
    void assistantMessageWithAllBlockTypes() {
        AssistantMessage message = (AssistantMessage) parse("""
                {"type":"assistant","session_id":"s","error":"rate_limit",
                 "message":{"id":"m1","model":"claude","stop_reason":"end_turn","usage":{"input_tokens":3},
                  "content":[{"type":"thinking","thinking":"hmm","signature":"sig"},
                   {"type":"text","text":"a"},{"type":"text","text":"b"},
                   {"type":"tool_use","id":"t","name":"Bash","input":{"command":"ls"}},
                   {"type":"server_tool_use","id":"st","name":"web_search","input":{"q":"x"}},
                   {"type":"advisor_tool_result","tool_use_id":"st","content":{"type":"r"}}]}}""");
        assertEquals("ab", message.text());
        assertEquals("rate_limit", message.error());
        assertEquals("m1", message.messageId());
        assertEquals(new ThinkingBlock("hmm", "sig"), message.content().get(0));
        assertEquals(new ToolUseBlock("t", "Bash", Map.of("command", "ls")), message.content().get(3));
        assertInstanceOf(ServerToolUseBlock.class, message.content().get(4));
        assertInstanceOf(ServerToolResultBlock.class, message.content().get(5));
    }

    @Test
    void assistantMessageWithoutModelFails() {
        MessageParseException e = assertThrows(MessageParseException.class,
                () -> parse("{\"type\":\"assistant\",\"message\":{\"content\":[]}}"));
        assertTrue(e.getMessage().contains("'model'"));
        assertEquals("assistant", e.data().get("type"));
    }

    @Test
    void systemSubtypes() {
        assertInstanceOf(GenericSystemMessage.class, parse("{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[]}"));
        TaskStartedMessage started = (TaskStartedMessage) parse("""
                {"type":"system","subtype":"task_started","task_id":"t","description":"d","uuid":"u",
                 "session_id":"s","task_type":"local_agent"}""");
        assertEquals("local_agent", started.taskType());
        TaskNotificationMessage done = (TaskNotificationMessage) parse("""
                {"type":"system","subtype":"task_notification","task_id":"t","status":"completed",
                 "output_file":"/o","summary":"ok","uuid":"u","session_id":"s"}""");
        assertEquals("completed", done.status());
        TaskUpdatedMessage updated = (TaskUpdatedMessage) parse(
                "{\"type\":\"system\",\"subtype\":\"task_updated\",\"patch\":{\"status\":\"killed\"}}");
        assertEquals("", updated.taskId());
        assertTrue(updated.isTerminal());
        HookEventMessage hook = (HookEventMessage) parse(
                "{\"type\":\"system\",\"subtype\":\"hook_started\",\"hook_name\":\"PreToolUse\"}");
        assertEquals("PreToolUse", hook.hookEventName());
        assertThrows(MessageParseException.class,
                () -> parse("{\"type\":\"system\",\"subtype\":\"task_started\",\"task_id\":\"t\"}"));
    }

    @Test
    void resultMessage() {
        ResultMessage result = (ResultMessage) parse("""
                {"type":"result","subtype":"success","duration_ms":12,"duration_api_ms":7,"is_error":false,
                 "num_turns":2,"session_id":"s","total_cost_usd":0.25,"result":"done",
                 "structured_output":{"a":1},"modelUsage":{"m":{"inputTokens":1}},
                 "deferred_tool_use":{"id":"d","name":"Bash","input":{}},"api_error_status":529}""");
        assertEquals(12, result.durationMs());
        assertEquals(0.25, result.totalCostUsd());
        assertEquals(Map.of("a", 1), result.structuredOutput());
        assertEquals("Bash", result.deferredToolUse().name());
        assertEquals(529, result.apiErrorStatus());
    }

    @Test
    void otherMessageTypes() {
        StreamEvent event = (StreamEvent) parse(
                "{\"type\":\"stream_event\",\"uuid\":\"u\",\"session_id\":\"s\",\"event\":{\"type\":\"x\"}}");
        assertEquals(Map.of("type", "x"), event.event());
        RateLimitEvent rate = (RateLimitEvent) parse("""
                {"type":"rate_limit_event","uuid":"u","session_id":"s",
                 "rate_limit_info":{"status":"allowed_warning","resetsAt":1700000000,"utilization":0.9}}""");
        assertEquals(1700000000L, rate.rateLimitInfo().resetsAt());
        assertEquals(0.9, rate.rateLimitInfo().utilization());
        ConversationResetMessage reset = (ConversationResetMessage) parse(
                "{\"type\":\"conversation_reset\",\"new_conversation_id\":\"n\",\"uuid\":\"u\",\"session_id\":\"s\"}");
        assertEquals("n", reset.newConversationId());
    }

    @Test
    void unknownTypesAreSkippedAndMissingTypeFails() {
        assertNull(parse("{\"type\":\"future_type\"}"));
        assertThrows(MessageParseException.class, () -> parse("{\"x\":1}"));
        assertEquals(List.of("a"), ((ResultMessage) parse("""
                {"type":"result","subtype":"s","duration_ms":1,"duration_api_ms":1,"is_error":true,
                 "num_turns":1,"session_id":"s","errors":["a",3]}""")).errors());
    }
}
