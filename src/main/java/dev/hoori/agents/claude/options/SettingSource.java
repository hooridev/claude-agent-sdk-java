package dev.hoori.agents.claude.options;

/** Which filesystem settings Claude Code loads ({@code --setting-sources}). */
public enum SettingSource implements WireEnum {
    USER("user"), PROJECT("project"), LOCAL("local");

    private final String wireValue;

    SettingSource(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }
}
