package dev.hoori.agents.claude.message;

import java.util.List;
import java.util.Map;

/**
 * Final message of a turn, with cost and usage information.
 *
 * @param modelUsage per-model usage keyed by model id (camelCase keys as emitted by the CLI)
 */
public record ResultMessage(
        String subtype,
        long durationMs,
        long durationApiMs,
        boolean isError,
        int numTurns,
        String sessionId,
        String stopReason,
        Double totalCostUsd,
        Map<String, Object> usage,
        String result,
        Object structuredOutput,
        Map<String, Object> modelUsage,
        List<Object> permissionDenials,
        DeferredToolUse deferredToolUse,
        List<String> errors,
        Integer apiErrorStatus,
        String uuid,
        String terminalReason,
        Map<String, Object> origin,
        String userMessageUuid,
        List<String> userMessageUuids) implements Message {
}
