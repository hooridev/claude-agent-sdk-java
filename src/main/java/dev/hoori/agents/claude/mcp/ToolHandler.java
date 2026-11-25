package dev.hoori.agents.claude.mcp;

import java.util.Map;

/** Implementation of an {@link SdkMcpTool}. Runs on a virtual thread; may be interrupted on cancellation. */
@FunctionalInterface
public interface ToolHandler {

    /**
     * @param arguments the validated tool arguments
     * @return the tool result
     * @throws Exception reported to Claude as an error result
     */
    ToolResult call(Map<String, Object> arguments) throws Exception;
}
