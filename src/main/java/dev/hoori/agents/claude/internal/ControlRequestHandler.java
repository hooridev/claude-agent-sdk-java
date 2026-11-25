package dev.hoori.agents.claude.internal;

import java.util.Map;

/** Answers one subtype of control request sent by the CLI (Strategy). */
interface ControlRequestHandler {

    /** The {@code request.subtype} this handler answers. */
    String subtype();

    /**
     * @param request the {@code request} object of the control request
     * @return the {@code response} payload of the success response
     * @throws Exception sent back as an error response
     */
    Map<String, Object> handle(Map<String, Object> request) throws Exception;

    /** Called on the reader thread, in frame order, before {@link #handle} runs on its own thread. */
    default void onReceived(Map<String, Object> request) {
    }
}
