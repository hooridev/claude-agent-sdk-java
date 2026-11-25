import dev.hoori.agents.claude.ClaudeAgent;
import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.MessageStream;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.Message;
import dev.hoori.agents.claude.options.AgentDefinition;

/** Programmatic subagents (port of agents.py). */
public class Agents {

    public static void main(String[] args) {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .agent("code-reviewer", AgentDefinition.builder(
                                "Reviews code for best practices and potential issues",
                                "You are a code reviewer. Analyze code for bugs, performance issues, security "
                                        + "vulnerabilities, and adherence to best practices.")
                        .tools("Read", "Grep").model("sonnet").build())
                .agent("doc-writer", AgentDefinition.builder(
                                "Writes comprehensive documentation",
                                "You are a technical documentation expert. Write clear documentation with examples.")
                        .tools("Read", "Write", "Edit").model("sonnet").build())
                .build();

        try (MessageStream messages = ClaudeAgent.query(
                "Use the code-reviewer agent to review the file examples/Agents.java", options)) {
            for (Message message : messages) {
                if (message instanceof AssistantMessage assistant && !assistant.text().isEmpty()) {
                    System.out.println("Claude: " + assistant.text());
                }
            }
        }
    }
}
