package dev.hoori.agents.claude.options;

/** When mirrored transcript entries are written to the {@code SessionStore}. */
public enum SessionStoreFlushMode implements WireEnum {
    /** Buffer entries and flush before each result (default). */
    BATCHED("batched"),
    /** Flush each frame as soon as it arrives. */
    EAGER("eager");

    private final String wireValue;

    SessionStoreFlushMode(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }
}
