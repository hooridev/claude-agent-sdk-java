import java.util.List;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.ClaudeSdkClient;
import dev.hoori.agents.claude.hook.HookEvent;
import dev.hoori.agents.claude.hook.HookMatcher;
import dev.hoori.agents.claude.hook.HookOutput;
import dev.hoori.agents.claude.hook.HookSpecificOutput;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.Message;

/** In-process hooks (port of hooks.py): block dangerous Bash commands, add context after tools. */
public class Hooks {

    private static final List<String> BLOCKED = List.of("rm -rf", "foo.sh");

    public static void main(String[] args) {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools("Bash")
                .hook(HookEvent.PRE_TOOL_USE, HookMatcher.of("Bash", (input, toolUseId, context) -> {
                    String command = String.valueOf(input.toolInput().get("command"));
                    for (String pattern : BLOCKED) {
                        if (command.contains(pattern)) {
                            System.out.println("Blocked: " + command);
                            return HookOutput.builder().hookSpecificOutput(HookSpecificOutput.preToolUse("deny",
                                    "Command contains invalid pattern: " + pattern)).build();
                        }
                    }
                    return HookOutput.empty();
                }))
                .hook(HookEvent.POST_TOOL_USE, HookMatcher.of((input, toolUseId, context) ->
                        HookOutput.builder().hookSpecificOutput(HookSpecificOutput.postToolUse(
                                "Remember to double-check the output of " + input.toolName())).build()))
                .build();

        try (ClaudeSdkClient client = new ClaudeSdkClient(options)) {
            client.connect();
            client.query("Run the bash command: ./foo.sh --help");
            print(client);
            client.query("Run the bash command: echo 'Hello from hooks example!'");
            print(client);
        }
    }

    private static void print(ClaudeSdkClient client) {
        for (Message message : client.receiveResponse()) {
            if (message instanceof AssistantMessage assistant && !assistant.text().isEmpty()) {
                System.out.println("Claude: " + assistant.text());
            }
        }
    }
}
