package dev.hoori.agents.claude.options;

/** An enum whose constants map to a string value of the CLI wire protocol. */
public interface WireEnum {

    /** The value as sent to / received from the CLI. */
    String wireValue();

    /**
     * Looks up the constant with the given wire value.
     *
     * @return the constant, or {@code null} if none matches
     */
    static <E extends Enum<E> & WireEnum> E fromWire(Class<E> type, String value) {
        for (E constant : type.getEnumConstants()) {
            if (constant.wireValue().equals(value)) {
                return constant;
            }
        }
        return null;
    }
}
