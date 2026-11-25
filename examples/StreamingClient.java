import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.ClaudeSdkClient;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.Message;
import dev.hoori.agents.claude.message.ResultMessage;

/** Multi-turn conversation and interrupts with ClaudeSdkClient (port of streaming_mode.py). */
public class StreamingClient {

    public static void main(String[] args) throws Exception {
        try (ClaudeSdkClient client = new ClaudeSdkClient(ClaudeAgentOptions.defaults())) {
            client.connect();

            client.query("What's the capital of France?");
            printResponse(client);

            client.query("What's the population of that city?"); // the client keeps the context
            printResponse(client);

            // Interrupt a long-running task from another thread.
            client.query("Count from 1 to 100 slowly, one number per line.");
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(2000);
                    client.interrupt();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            printResponse(client);
        }
    }

    private static void printResponse(ClaudeSdkClient client) {
        for (Message message : client.receiveResponse()) {
            if (message instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.text());
            } else if (message instanceof ResultMessage result) {
                System.out.println("-- " + result.subtype() + " after " + result.durationMs() + " ms");
            }
        }
    }
}
