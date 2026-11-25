package dev.hoori.agents.claude.options;

/** Reasoning effort level ({@code --effort}). */
public enum EffortLevel implements WireEnum {
    LOW("low"), MEDIUM("medium"), HIGH("high"), XHIGH("xhigh"), MAX("max");

    private final String wireValue;

    EffortLevel(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }
}
