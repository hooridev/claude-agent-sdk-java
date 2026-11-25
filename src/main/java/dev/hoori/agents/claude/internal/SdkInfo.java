package dev.hoori.agents.claude.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build information of this SDK. */
public final class SdkInfo {

    /** The SDK version (from the Maven build), or {@code unknown}. */
    public static final String VERSION = loadVersion();

    /** Value of {@code CLAUDE_CODE_ENTRYPOINT} this SDK reports to the CLI. */
    public static final String ENTRYPOINT = "sdk-java";

    private SdkInfo() {
    }

    private static String loadVersion() {
        try (InputStream in = SdkInfo.class.getResourceAsStream("/dev/hoori/agents/claude/sdk.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "unknown");
            return version.startsWith("${") ? "unknown" : version;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
