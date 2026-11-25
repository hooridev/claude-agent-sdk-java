package dev.hoori.agents.claude;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.hoori.agents.claude.hook.HookEvent;
import dev.hoori.agents.claude.hook.HookMatcher;
import dev.hoori.agents.claude.mcp.McpServerConfig;
import dev.hoori.agents.claude.options.AgentDefinition;
import dev.hoori.agents.claude.options.EffortLevel;
import dev.hoori.agents.claude.options.PermissionMode;
import dev.hoori.agents.claude.options.PluginConfig;
import dev.hoori.agents.claude.options.SessionStoreFlushMode;
import dev.hoori.agents.claude.options.SettingSource;
import dev.hoori.agents.claude.options.Skills;
import dev.hoori.agents.claude.options.SystemPrompt;
import dev.hoori.agents.claude.options.ThinkingConfig;
import dev.hoori.agents.claude.options.Tools;
import dev.hoori.agents.claude.permission.CanUseTool;
import dev.hoori.agents.claude.session.SessionStore;

/**
 * Options for {@link ClaudeAgent#query} and {@link ClaudeSdkClient} (Python: {@code ClaudeAgentOptions}).
 * Immutable; create with {@link #builder()} and derive variants with {@link #toBuilder()}.
 *
 * <p>Every accessor documents the CLI flag or protocol field it maps to. Options that the CLI
 * does not know are ignored by it, so newer options may need a newer Claude Code.
 */
public final class ClaudeAgentOptions {

    private final Tools tools;
    private final List<String> allowedTools;
    private final SystemPrompt systemPrompt;
    private final Map<String, McpServerConfig> mcpServers;
    private final String mcpConfig;
    private final boolean strictMcpConfig;
    private final PermissionMode permissionMode;
    private final boolean continueConversation;
    private final String resume;
    private final String sessionId;
    private final Integer maxTurns;
    private final Double maxBudgetUsd;
    private final List<String> disallowedTools;
    private final String model;
    private final String fallbackModel;
    private final List<String> betas;
    private final String permissionPromptToolName;
    private final Path cwd;
    private final Path cliPath;
    private final String settings;
    private final List<Path> addDirs;
    private final Map<String, String> env;
    private final Map<String, String> extraArgs;
    private final Integer maxBufferSize;
    private final Consumer<String> stderr;
    private final CanUseTool canUseTool;
    private final Map<HookEvent, List<HookMatcher>> hooks;
    private final boolean includePartialMessages;
    private final boolean includeHookEvents;
    private final boolean forwardSubagentText;
    private final boolean verbatimPrompts;
    private final boolean forkSession;
    private final String resumeSessionAt;
    private final String resumeDropsTurn;
    private final Map<String, AgentDefinition> agents;
    private final List<SettingSource> settingSources;
    private final Skills skills;
    private final Map<String, Object> sandbox;
    private final List<PluginConfig> plugins;
    private final Integer maxThinkingTokens;
    private final ThinkingConfig thinking;
    private final EffortLevel effort;
    private final Map<String, Object> outputFormat;
    private final boolean enableFileCheckpointing;
    private final SessionStore sessionStore;
    private final SessionStoreFlushMode sessionStoreFlush;
    private final int loadTimeoutMs;
    private final Integer taskBudget;
    private final String clientApp;

    private ClaudeAgentOptions(Builder b) {
        this.tools = b.tools;
        this.allowedTools = List.copyOf(b.allowedTools);
        this.systemPrompt = b.systemPrompt;
        this.mcpServers = Collections.unmodifiableMap(new LinkedHashMap<>(b.mcpServers));
        this.mcpConfig = b.mcpConfig;
        this.strictMcpConfig = b.strictMcpConfig;
        this.permissionMode = b.permissionMode;
        this.continueConversation = b.continueConversation;
        this.resume = b.resume;
        this.sessionId = b.sessionId;
        this.maxTurns = b.maxTurns;
        this.maxBudgetUsd = b.maxBudgetUsd;
        this.disallowedTools = List.copyOf(b.disallowedTools);
        this.model = b.model;
        this.fallbackModel = b.fallbackModel;
        this.betas = List.copyOf(b.betas);
        this.permissionPromptToolName = b.permissionPromptToolName;
        this.cwd = b.cwd;
        this.cliPath = b.cliPath;
        this.settings = b.settings;
        this.addDirs = List.copyOf(b.addDirs);
        this.env = Collections.unmodifiableMap(new LinkedHashMap<>(b.env));
        this.extraArgs = Collections.unmodifiableMap(new LinkedHashMap<>(b.extraArgs));
        this.maxBufferSize = b.maxBufferSize;
        this.stderr = b.stderr;
        this.canUseTool = b.canUseTool;
        Map<HookEvent, List<HookMatcher>> hookCopy = new LinkedHashMap<>();
        b.hooks.forEach((event, matchers) -> hookCopy.put(event, List.copyOf(matchers)));
        this.hooks = Collections.unmodifiableMap(hookCopy);
        this.includePartialMessages = b.includePartialMessages;
        this.includeHookEvents = b.includeHookEvents;
        this.forwardSubagentText = b.forwardSubagentText;
        this.verbatimPrompts = b.verbatimPrompts;
        this.forkSession = b.forkSession;
        this.resumeSessionAt = b.resumeSessionAt;
        this.resumeDropsTurn = b.resumeDropsTurn;
        this.agents = Collections.unmodifiableMap(new LinkedHashMap<>(b.agents));
        this.settingSources = b.settingSources == null ? null : List.copyOf(b.settingSources);
        this.skills = b.skills;
        this.sandbox = b.sandbox == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(b.sandbox));
        this.plugins = List.copyOf(b.plugins);
        this.maxThinkingTokens = b.maxThinkingTokens;
        this.thinking = b.thinking;
        this.effort = b.effort;
        this.outputFormat = b.outputFormat;
        this.enableFileCheckpointing = b.enableFileCheckpointing;
        this.sessionStore = b.sessionStore;
        this.sessionStoreFlush = b.sessionStoreFlush;
        this.loadTimeoutMs = b.loadTimeoutMs;
        this.taskBudget = b.taskBudget;
        this.clientApp = b.clientApp;
    }

    /** Default options. */
    public static ClaudeAgentOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder initialized with these options. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Base set of built-in tools ({@code --tools}); {@code null} keeps the CLI default. */
    public Tools tools() {
        return tools;
    }

    /** Tools that run without a permission prompt ({@code --allowedTools}). */
    public List<String> allowedTools() {
        return allowedTools;
    }

    /** System prompt; {@code null} means an empty system prompt. */
    public SystemPrompt systemPrompt() {
        return systemPrompt;
    }

    /** MCP servers by name ({@code --mcp-config}); SDK servers are served in-process. */
    public Map<String, McpServerConfig> mcpServers() {
        return mcpServers;
    }

    /** A path to, or JSON string of, an MCP config file; used instead of {@link #mcpServers()}. */
    public String mcpConfig() {
        return mcpConfig;
    }

    /** {@code --strict-mcp-config}. */
    public boolean strictMcpConfig() {
        return strictMcpConfig;
    }

    /** {@code --permission-mode}. */
    public PermissionMode permissionMode() {
        return permissionMode;
    }

    /** Continue the most recent conversation ({@code --continue}). */
    public boolean continueConversation() {
        return continueConversation;
    }

    /** Session id (or title) to resume ({@code --resume}). */
    public String resume() {
        return resume;
    }

    /** Use this id for the new session ({@code --session-id}). */
    public String sessionId() {
        return sessionId;
    }

    /** {@code --max-turns}. */
    public Integer maxTurns() {
        return maxTurns;
    }

    /** {@code --max-budget-usd}. */
    public Double maxBudgetUsd() {
        return maxBudgetUsd;
    }

    /** {@code --disallowedTools}. */
    public List<String> disallowedTools() {
        return disallowedTools;
    }

    /** {@code --model}. */
    public String model() {
        return model;
    }

    /** {@code --fallback-model}. */
    public String fallbackModel() {
        return fallbackModel;
    }

    /** {@code --betas}, e.g. {@code context-1m-2025-08-07}. */
    public List<String> betas() {
        return betas;
    }

    /** {@code --permission-prompt-tool}; mutually exclusive with {@link #canUseTool()}. */
    public String permissionPromptToolName() {
        return permissionPromptToolName;
    }

    /** Working directory of the CLI process. */
    public Path cwd() {
        return cwd;
    }

    /** Path of the {@code claude} executable; {@code null} searches PATH and common install locations. */
    public Path cliPath() {
        return cliPath;
    }

    /** {@code --settings}: a settings file path or JSON string. */
    public String settings() {
        return settings;
    }

    /** {@code --add-dir} for each entry. */
    public List<Path> addDirs() {
        return addDirs;
    }

    /** Extra environment variables for the CLI process. */
    public Map<String, String> env() {
        return env;
    }

    /** Arbitrary extra CLI flags; a {@code null} value is a boolean flag. */
    public Map<String, String> extraArgs() {
        return extraArgs;
    }

    /** Maximum size of a single stdout message in characters (default 1 MiB). */
    public Integer maxBufferSize() {
        return maxBufferSize;
    }

    /** Receives each stderr line of the CLI; if {@code null}, stderr is inherited. */
    public Consumer<String> stderr() {
        return stderr;
    }

    /** Permission callback for tool calls. */
    public CanUseTool canUseTool() {
        return canUseTool;
    }

    /** In-process hook callbacks by event. */
    public Map<HookEvent, List<HookMatcher>> hooks() {
        return hooks;
    }

    /** {@code --include-partial-messages}: emit {@code StreamEvent}s. */
    public boolean includePartialMessages() {
        return includePartialMessages;
    }

    /** {@code --include-hook-events}: emit {@code HookEventMessage}s. */
    public boolean includeHookEvents() {
        return includeHookEvents;
    }

    /** Forward subagent text and thinking blocks, not just tool use/results. */
    public boolean forwardSubagentText() {
        return forwardSubagentText;
    }

    /** Deliver prompts as written: no {@code @path} expansion, no slash command dispatch. */
    public boolean verbatimPrompts() {
        return verbatimPrompts;
    }

    /** {@code --fork-session}: resume into a new session id. */
    public boolean forkSession() {
        return forkSession;
    }

    /** {@code --resume-session-at}: resume up to this message uuid. */
    public String resumeSessionAt() {
        return resumeSessionAt;
    }

    /** {@code --resume-drops-turn}. */
    public String resumeDropsTurn() {
        return resumeDropsTurn;
    }

    /** Programmatic subagents, sent with the initialize request. */
    public Map<String, AgentDefinition> agents() {
        return agents;
    }

    /** {@code --setting-sources}; {@code null} keeps the CLI default. */
    public List<SettingSource> settingSources() {
        return settingSources;
    }

    /** Skill allow list. */
    public Skills skills() {
        return skills;
    }

    /** Sandbox settings (merged into {@code --settings} as {@code sandbox}). */
    public Map<String, Object> sandbox() {
        return sandbox;
    }

    /** Plugins ({@code --plugin-dir}). */
    public List<PluginConfig> plugins() {
        return plugins;
    }

    /** Deprecated in favour of {@link #thinking()}: {@code --max-thinking-tokens}. */
    public Integer maxThinkingTokens() {
        return maxThinkingTokens;
    }

    /** Thinking configuration; takes precedence over {@link #maxThinkingTokens()}. */
    public ThinkingConfig thinking() {
        return thinking;
    }

    /** {@code --effort}. */
    public EffortLevel effort() {
        return effort;
    }

    /** Structured output, e.g. {@code {"type":"json_schema","schema":{...}}} ({@code --json-schema}). */
    public Map<String, Object> outputFormat() {
        return outputFormat;
    }

    /** Enable file checkpointing (needed for {@code rewindFiles}). */
    public boolean enableFileCheckpointing() {
        return enableFileCheckpointing;
    }

    /** Mirror transcripts to this store ({@code --session-mirror}) and resume from it. */
    public SessionStore sessionStore() {
        return sessionStore;
    }

    /** When mirrored entries are flushed to the store. */
    public SessionStoreFlushMode sessionStoreFlush() {
        return sessionStoreFlush;
    }

    /** Timeout for loading a session from the store on resume, in milliseconds. */
    public int loadTimeoutMs() {
        return loadTimeoutMs;
    }

    /** API-side task budget in tokens ({@code --task-budget}). */
    public Integer taskBudget() {
        return taskBudget;
    }

    /** Your application's name (and version), reported to Anthropic as the client app. */
    public String clientApp() {
        return clientApp;
    }

    /** Builder for {@link ClaudeAgentOptions}. All collection setters replace the current value. */
    public static final class Builder {
        private Tools tools;
        private List<String> allowedTools = new ArrayList<>();
        private SystemPrompt systemPrompt;
        private Map<String, McpServerConfig> mcpServers = new LinkedHashMap<>();
        private String mcpConfig;
        private boolean strictMcpConfig;
        private PermissionMode permissionMode;
        private boolean continueConversation;
        private String resume;
        private String sessionId;
        private Integer maxTurns;
        private Double maxBudgetUsd;
        private List<String> disallowedTools = new ArrayList<>();
        private String model;
        private String fallbackModel;
        private List<String> betas = new ArrayList<>();
        private String permissionPromptToolName;
        private Path cwd;
        private Path cliPath;
        private String settings;
        private List<Path> addDirs = new ArrayList<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private Map<String, String> extraArgs = new LinkedHashMap<>();
        private Integer maxBufferSize;
        private Consumer<String> stderr;
        private CanUseTool canUseTool;
        private Map<HookEvent, List<HookMatcher>> hooks = new LinkedHashMap<>();
        private boolean includePartialMessages;
        private boolean includeHookEvents;
        private boolean forwardSubagentText;
        private boolean verbatimPrompts;
        private boolean forkSession;
        private String resumeSessionAt;
        private String resumeDropsTurn;
        private Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        private List<SettingSource> settingSources;
        private Skills skills;
        private Map<String, Object> sandbox;
        private List<PluginConfig> plugins = new ArrayList<>();
        private Integer maxThinkingTokens;
        private ThinkingConfig thinking;
        private EffortLevel effort;
        private Map<String, Object> outputFormat;
        private boolean enableFileCheckpointing;
        private SessionStore sessionStore;
        private SessionStoreFlushMode sessionStoreFlush = SessionStoreFlushMode.BATCHED;
        private int loadTimeoutMs = 60_000;
        private Integer taskBudget;
        private String clientApp;

        private Builder() {
        }

        private Builder(ClaudeAgentOptions o) {
            tools = o.tools;
            allowedTools = new ArrayList<>(o.allowedTools);
            systemPrompt = o.systemPrompt;
            mcpServers = new LinkedHashMap<>(o.mcpServers);
            mcpConfig = o.mcpConfig;
            strictMcpConfig = o.strictMcpConfig;
            permissionMode = o.permissionMode;
            continueConversation = o.continueConversation;
            resume = o.resume;
            sessionId = o.sessionId;
            maxTurns = o.maxTurns;
            maxBudgetUsd = o.maxBudgetUsd;
            disallowedTools = new ArrayList<>(o.disallowedTools);
            model = o.model;
            fallbackModel = o.fallbackModel;
            betas = new ArrayList<>(o.betas);
            permissionPromptToolName = o.permissionPromptToolName;
            cwd = o.cwd;
            cliPath = o.cliPath;
            settings = o.settings;
            addDirs = new ArrayList<>(o.addDirs);
            env = new LinkedHashMap<>(o.env);
            extraArgs = new LinkedHashMap<>(o.extraArgs);
            maxBufferSize = o.maxBufferSize;
            stderr = o.stderr;
            canUseTool = o.canUseTool;
            hooks = new LinkedHashMap<>(o.hooks);
            includePartialMessages = o.includePartialMessages;
            includeHookEvents = o.includeHookEvents;
            forwardSubagentText = o.forwardSubagentText;
            verbatimPrompts = o.verbatimPrompts;
            forkSession = o.forkSession;
            resumeSessionAt = o.resumeSessionAt;
            resumeDropsTurn = o.resumeDropsTurn;
            agents = new LinkedHashMap<>(o.agents);
            settingSources = o.settingSources == null ? null : new ArrayList<>(o.settingSources);
            skills = o.skills;
            sandbox = o.sandbox;
            plugins = new ArrayList<>(o.plugins);
            maxThinkingTokens = o.maxThinkingTokens;
            thinking = o.thinking;
            effort = o.effort;
            outputFormat = o.outputFormat;
            enableFileCheckpointing = o.enableFileCheckpointing;
            sessionStore = o.sessionStore;
            sessionStoreFlush = o.sessionStoreFlush;
            loadTimeoutMs = o.loadTimeoutMs;
            taskBudget = o.taskBudget;
            clientApp = o.clientApp;
        }

        public Builder tools(Tools tools) {
            this.tools = tools;
            return this;
        }

        public Builder allowedTools(String... tools) {
            this.allowedTools = new ArrayList<>(List.of(tools));
            return this;
        }

        public Builder allowedTools(List<String> tools) {
            this.allowedTools = new ArrayList<>(tools);
            return this;
        }

        public Builder systemPrompt(String prompt) {
            this.systemPrompt = prompt == null ? null : SystemPrompt.text(prompt);
            return this;
        }

        public Builder systemPrompt(SystemPrompt prompt) {
            this.systemPrompt = prompt;
            return this;
        }

        public Builder mcpServers(Map<String, McpServerConfig> servers) {
            this.mcpServers = new LinkedHashMap<>(servers);
            return this;
        }

        /** Adds one MCP server. */
        public Builder mcpServer(String name, McpServerConfig config) {
            this.mcpServers.put(name, config);
            return this;
        }

        /** An MCP config file path or JSON string; cannot be combined with {@link #mcpServer}. */
        public Builder mcpConfig(String pathOrJson) {
            this.mcpConfig = pathOrJson;
            return this;
        }

        public Builder mcpConfig(Path path) {
            this.mcpConfig = path == null ? null : path.toString();
            return this;
        }

        public Builder strictMcpConfig(boolean value) {
            this.strictMcpConfig = value;
            return this;
        }

        public Builder permissionMode(PermissionMode mode) {
            this.permissionMode = mode;
            return this;
        }

        public Builder continueConversation(boolean value) {
            this.continueConversation = value;
            return this;
        }

        public Builder resume(String sessionIdOrTitle) {
            this.resume = sessionIdOrTitle;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder maxTurns(Integer maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder maxBudgetUsd(Double maxBudgetUsd) {
            this.maxBudgetUsd = maxBudgetUsd;
            return this;
        }

        public Builder disallowedTools(String... tools) {
            this.disallowedTools = new ArrayList<>(List.of(tools));
            return this;
        }

        public Builder disallowedTools(List<String> tools) {
            this.disallowedTools = new ArrayList<>(tools);
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder fallbackModel(String model) {
            this.fallbackModel = model;
            return this;
        }

        public Builder betas(String... betas) {
            this.betas = new ArrayList<>(List.of(betas));
            return this;
        }

        public Builder permissionPromptToolName(String name) {
            this.permissionPromptToolName = name;
            return this;
        }

        public Builder cwd(Path cwd) {
            this.cwd = cwd;
            return this;
        }

        public Builder cwd(String cwd) {
            this.cwd = cwd == null ? null : Path.of(cwd);
            return this;
        }

        public Builder cliPath(Path cliPath) {
            this.cliPath = cliPath;
            return this;
        }

        public Builder cliPath(String cliPath) {
            this.cliPath = cliPath == null ? null : Path.of(cliPath);
            return this;
        }

        public Builder settings(String pathOrJson) {
            this.settings = pathOrJson;
            return this;
        }

        public Builder addDirs(Path... dirs) {
            this.addDirs = new ArrayList<>(List.of(dirs));
            return this;
        }

        public Builder env(Map<String, String> env) {
            this.env = new LinkedHashMap<>(env);
            return this;
        }

        /** Adds one environment variable. */
        public Builder env(String key, String value) {
            this.env.put(key, value);
            return this;
        }

        /** Adds an extra CLI flag with a value ({@code --flag value}). */
        public Builder extraArg(String flag, String value) {
            this.extraArgs.put(flag, value);
            return this;
        }

        /** Adds an extra boolean CLI flag ({@code --flag}). */
        public Builder extraFlag(String flag) {
            this.extraArgs.put(flag, null);
            return this;
        }

        public Builder maxBufferSize(Integer chars) {
            this.maxBufferSize = chars;
            return this;
        }

        public Builder stderr(Consumer<String> stderr) {
            this.stderr = stderr;
            return this;
        }

        public Builder canUseTool(CanUseTool canUseTool) {
            this.canUseTool = canUseTool;
            return this;
        }

        public Builder hooks(Map<HookEvent, List<HookMatcher>> hooks) {
            this.hooks = new LinkedHashMap<>(hooks);
            return this;
        }

        /** Adds hook matchers for an event. */
        public Builder hook(HookEvent event, HookMatcher... matchers) {
            List<HookMatcher> list = new ArrayList<>(this.hooks.getOrDefault(event, List.of()));
            list.addAll(List.of(matchers));
            this.hooks.put(event, list);
            return this;
        }

        public Builder includePartialMessages(boolean value) {
            this.includePartialMessages = value;
            return this;
        }

        public Builder includeHookEvents(boolean value) {
            this.includeHookEvents = value;
            return this;
        }

        public Builder forwardSubagentText(boolean value) {
            this.forwardSubagentText = value;
            return this;
        }

        public Builder verbatimPrompts(boolean value) {
            this.verbatimPrompts = value;
            return this;
        }

        public Builder forkSession(boolean value) {
            this.forkSession = value;
            return this;
        }

        public Builder resumeSessionAt(String messageUuid) {
            this.resumeSessionAt = messageUuid;
            return this;
        }

        public Builder resumeDropsTurn(String value) {
            this.resumeDropsTurn = value;
            return this;
        }

        public Builder agents(Map<String, AgentDefinition> agents) {
            this.agents = new LinkedHashMap<>(agents);
            return this;
        }

        /** Adds one subagent definition. */
        public Builder agent(String name, AgentDefinition agent) {
            this.agents.put(name, agent);
            return this;
        }

        public Builder settingSources(SettingSource... sources) {
            this.settingSources = new ArrayList<>(List.of(sources));
            return this;
        }

        public Builder skills(Skills skills) {
            this.skills = skills;
            return this;
        }

        public Builder sandbox(Map<String, Object> sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        public Builder plugins(PluginConfig... plugins) {
            this.plugins = new ArrayList<>(List.of(plugins));
            return this;
        }

        public Builder maxThinkingTokens(Integer tokens) {
            this.maxThinkingTokens = tokens;
            return this;
        }

        public Builder thinking(ThinkingConfig thinking) {
            this.thinking = thinking;
            return this;
        }

        public Builder effort(EffortLevel effort) {
            this.effort = effort;
            return this;
        }

        public Builder outputFormat(Map<String, Object> outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        /** Shorthand for {@code outputFormat({"type":"json_schema","schema":schema})}. */
        public Builder jsonSchemaOutput(Map<String, Object> schema) {
            this.outputFormat = Map.of("type", "json_schema", "schema", schema);
            return this;
        }

        public Builder enableFileCheckpointing(boolean value) {
            this.enableFileCheckpointing = value;
            return this;
        }

        public Builder sessionStore(SessionStore store) {
            this.sessionStore = store;
            return this;
        }

        public Builder sessionStoreFlush(SessionStoreFlushMode mode) {
            this.sessionStoreFlush = mode;
            return this;
        }

        public Builder loadTimeoutMs(int millis) {
            this.loadTimeoutMs = millis;
            return this;
        }

        public Builder taskBudget(Integer totalTokens) {
            this.taskBudget = totalTokens;
            return this;
        }

        public Builder clientApp(String clientApp) {
            this.clientApp = clientApp;
            return this;
        }

        /**
         * Builds the options.
         *
         * @throws IllegalStateException if {@code mcpConfig} is combined with {@code mcpServers}
         */
        public ClaudeAgentOptions build() {
            if (mcpConfig != null && !mcpServers.isEmpty()) {
                throw new IllegalStateException("mcpConfig and mcpServers cannot be combined; use one of them");
            }
            return new ClaudeAgentOptions(this);
        }
    }
}
