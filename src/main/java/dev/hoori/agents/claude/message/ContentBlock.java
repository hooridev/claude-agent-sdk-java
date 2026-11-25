package dev.hoori.agents.claude.message;

/** A block of content inside a user or assistant message. */
public sealed interface ContentBlock
        permits TextBlock, ThinkingBlock, ToolUseBlock, ToolResultBlock, ServerToolUseBlock, ServerToolResultBlock {
}
