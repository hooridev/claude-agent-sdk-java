package dev.hoori.agents.claude.options;

import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;

/**
 * A programmatically defined subagent, sent to the CLI with the initialize request.
 *
 * @param description     when the agent should be used
 * @param prompt          the agent's system prompt
 * @param tools           allowed tools, or {@code null} to inherit
 * @param disallowedTools tools the agent may not use
 * @param model           model alias ({@code sonnet}, {@code opus}, {@code haiku}, {@code inherit}) or full id
 * @param skills          skills the agent may use
 * @param memory          {@code user}, {@code project} or {@code local}
 * @param mcpServers      server names ({@code String}) or inline {@code Map<String, Object>} configs
 * @param initialPrompt   prompt the agent starts with
 * @param maxTurns        maximum turns of the agent
 * @param background      whether the agent runs in the background
 * @param effort          {@link EffortLevel} or an {@link Integer} budget
 * @param permissionMode  permission mode of the agent
 */
public record AgentDefinition(
        String description,
        String prompt,
        List<String> tools,
        List<String> disallowedTools,
        String model,
        List<String> skills,
        String memory,
        List<Object> mcpServers,
        String initialPrompt,
        Integer maxTurns,
        Boolean background,
        Object effort,
        PermissionMode permissionMode) {

    public static Builder builder(String description, String prompt) {
        return new Builder(description, prompt);
    }

    /** The wire form: camelCase keys, {@code null} fields omitted. */
    public Map<String, Object> toWire() {
        return Json.obj(
                "description", description,
                "prompt", prompt,
                "tools", tools,
                "disallowedTools", disallowedTools,
                "model", model,
                "skills", skills,
                "memory", memory,
                "mcpServers", mcpServers,
                "initialPrompt", initialPrompt,
                "maxTurns", maxTurns,
                "background", background,
                "effort", effort instanceof EffortLevel level ? level.wireValue() : effort,
                "permissionMode", permissionMode == null ? null : permissionMode.wireValue());
    }

    /** Builder for {@link AgentDefinition}. */
    public static final class Builder {
        private final String description;
        private final String prompt;
        private List<String> tools;
        private List<String> disallowedTools;
        private String model;
        private List<String> skills;
        private String memory;
        private List<Object> mcpServers;
        private String initialPrompt;
        private Integer maxTurns;
        private Boolean background;
        private Object effort;
        private PermissionMode permissionMode;

        private Builder(String description, String prompt) {
            this.description = description;
            this.prompt = prompt;
        }

        public Builder tools(String... tools) {
            this.tools = List.of(tools);
            return this;
        }

        public Builder disallowedTools(String... tools) {
            this.disallowedTools = List.of(tools);
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder skills(String... skills) {
            this.skills = List.of(skills);
            return this;
        }

        public Builder memory(String memory) {
            this.memory = memory;
            return this;
        }

        public Builder mcpServers(List<Object> mcpServers) {
            this.mcpServers = List.copyOf(mcpServers);
            return this;
        }

        public Builder initialPrompt(String initialPrompt) {
            this.initialPrompt = initialPrompt;
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder background(boolean background) {
            this.background = background;
            return this;
        }

        public Builder effort(EffortLevel effort) {
            this.effort = effort;
            return this;
        }

        public Builder effort(int effortBudget) {
            this.effort = effortBudget;
            return this;
        }

        public Builder permissionMode(PermissionMode permissionMode) {
            this.permissionMode = permissionMode;
            return this;
        }

        public AgentDefinition build() {
            return new AgentDefinition(description, prompt, tools, disallowedTools, model, skills, memory,
                    mcpServers, initialPrompt, maxTurns, background, effort, permissionMode);
        }
    }
}
