package dev.hoori.agents.claude.hook;

/** An in-process hook callback. Runs on a virtual thread. */
@FunctionalInterface
public interface HookCallback {

    /**
     * @param input     the hook input
     * @param toolUseId the tool use id, if the event concerns a tool call
     * @param context   hook context
     * @return the hook output; return {@link HookOutput#empty()} for "no opinion"
     * @throws Exception reported to the CLI as a hook failure
     */
    HookOutput call(HookInput input, String toolUseId, HookContext context) throws Exception;
}
