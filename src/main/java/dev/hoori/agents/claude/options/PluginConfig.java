package dev.hoori.agents.claude.options;

/** A plugin to load. Only local plugins ({@code --plugin-dir}) are supported. */
public record PluginConfig(String type, String path) {

    /** A plugin loaded from a local directory. */
    public static PluginConfig local(String path) {
        return new PluginConfig("local", path);
    }
}
