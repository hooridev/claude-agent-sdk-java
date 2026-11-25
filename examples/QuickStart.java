import dev.hoori.agents.claude.ClaudeAgent;
import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.MessageStream;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.Message;
import dev.hoori.agents.claude.message.ResultMessage;
import dev.hoori.agents.claude.message.TextBlock;

/** One-shot queries (port of the Python quick_start.py). */
public class QuickStart {

    public static void main(String[] args) {
        // Basic query with default options.
        print(ClaudeAgent.query("What is 2 + 2?"));

        // With options: a custom system prompt and a turn limit.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt("You are a helpful assistant that explains things simply.")
                .maxTurns(1)
                .build();
        print(ClaudeAgent.query("Explain what Java is in one sentence.", options));

        // With tools: allow file writes in the current directory.
        ClaudeAgentOptions withTools = ClaudeAgentOptions.builder()
                .allowedTools("Read", "Write")
                .systemPrompt("You are a helpful file assistant.")
                .build();
        print(ClaudeAgent.query("Create a file called hello.txt with 'Hello, World!' in it", withTools));
    }

    private static void print(MessageStream messages) {
        try (messages) {
            for (Message message : messages) {
                switch (message) {
                    case AssistantMessage assistant -> assistant.content().forEach(block -> {
                        if (block instanceof TextBlock(String text)) {
                            System.out.println("Claude: " + text);
                        }
                    });
                    case ResultMessage result when result.totalCostUsd() != null ->
                            System.out.printf("Cost: $%.4f%n", result.totalCostUsd());
                    default -> {
                    }
                }
            }
        }
        System.out.println();
    }
}
