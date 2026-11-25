package dev.hoori.agents.claude.message;

/** Extended-thinking content block. */
public record ThinkingBlock(String thinking, String signature) implements ContentBlock {
}
