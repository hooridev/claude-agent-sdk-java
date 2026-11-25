import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.ClaudeSdkClient;
import dev.hoori.agents.claude.mcp.McpServerConfig;
import dev.hoori.agents.claude.mcp.SdkMcpServer;
import dev.hoori.agents.claude.mcp.SdkMcpTool;
import dev.hoori.agents.claude.mcp.ToolResult;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.Message;
import dev.hoori.agents.claude.message.ToolUseBlock;

/** In-process MCP tools (port of mcp_calculator.py): no separate server process needed. */
public class McpCalculator {

    public static void main(String[] args) {
        SdkMcpServer calculator = SdkMcpServer.builder("calculator").version("2.0.0")
                .tool(binary("add", "Add two numbers", (a, b) -> a + b))
                .tool(binary("multiply", "Multiply two numbers", (a, b) -> a * b))
                .tool(SdkMcpTool.builder("divide", "Divide a by b").param("a", "number").param("b", "number")
                        .handler(in -> {
                            double b = number(in.get("b"));
                            return b == 0 ? ToolResult.error("Error: Division by zero is not allowed")
                                    : ToolResult.text(String.valueOf(number(in.get("a")) / b));
                        }).build())
                .build();

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServer("calc", McpServerConfig.sdk(calculator))
                .allowedTools("mcp__calc__add", "mcp__calc__multiply", "mcp__calc__divide")
                .build();

        try (ClaudeSdkClient client = new ClaudeSdkClient(options)) {
            client.connect();
            client.query("Calculate (12 + 30) * 2 and then divide the result by 4.");
            for (Message message : client.receiveResponse()) {
                if (message instanceof AssistantMessage assistant) {
                    assistant.content().forEach(block -> {
                        if (block instanceof ToolUseBlock use) {
                            System.out.println("tool " + use.name() + " " + use.input());
                        }
                    });
                    if (!assistant.text().isEmpty()) {
                        System.out.println("Claude: " + assistant.text());
                    }
                }
            }
        }
    }

    private static SdkMcpTool binary(String name, String description,
                                     java.util.function.DoubleBinaryOperator op) {
        return SdkMcpTool.builder(name, description).param("a", "number").param("b", "number")
                .handler(in -> ToolResult.text(String.valueOf(op.applyAsDouble(number(in.get("a")), number(in.get("b"))))))
                .build();
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
