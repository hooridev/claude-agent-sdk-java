import java.util.LinkedHashMap;
import java.util.Map;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.ClaudeSdkClient;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.Message;
import dev.hoori.agents.claude.permission.PermissionResult;

/** Deciding about each tool call in code (port of tool_permission_callback.py). */
public class ToolPermissionCallback {

    public static void main(String[] args) {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .canUseTool((toolName, input, context) -> {
                    System.out.println("Permission request: " + toolName + " " + input);
                    if (toolName.equals("Read") || toolName.equals("Glob") || toolName.equals("Grep")) {
                        return PermissionResult.allow();
                    }
                    if (toolName.equals("Write") && String.valueOf(input.get("file_path")).startsWith("/etc")) {
                        return PermissionResult.deny("Cannot write to system directories");
                    }
                    if (toolName.equals("Write")) {
                        // Redirect writes into a safe directory.
                        Map<String, Object> modified = new LinkedHashMap<>(input);
                        modified.put("file_path", "./safe_output/" + java.nio.file.Path.of(
                                String.valueOf(input.get("file_path"))).getFileName());
                        return PermissionResult.allow(modified);
                    }
                    if (toolName.equals("Bash") && String.valueOf(input.get("command")).contains("rm -rf")) {
                        return PermissionResult.deny("Dangerous command pattern detected");
                    }
                    return PermissionResult.allow();
                })
                .build();

        try (ClaudeSdkClient client = new ClaudeSdkClient(options)) {
            client.connect();
            client.query("List the files in the current directory, then create a file hello.txt saying hi.");
            for (Message message : client.receiveResponse()) {
                if (message instanceof AssistantMessage assistant && !assistant.text().isEmpty()) {
                    System.out.println("Claude: " + assistant.text());
                }
            }
        }
    }
}
