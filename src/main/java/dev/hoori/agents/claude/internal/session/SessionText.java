package dev.hoori.agents.claude.internal.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.hoori.agents.claude.internal.Json;

/**
 * Cheap reads of session JSONL files: head/tail chunks and string-field extraction that works on
 * truncated lines without a full JSON parse (Python: the text helpers of {@code sessions.py}).
 */
public final class SessionText {

    /** Size of the head/tail buffer for lite metadata reads. */
    public static final int LITE_READ_BUF_SIZE = 65536;

    /** Auto-generated or system messages skipped when looking for the first meaningful prompt. */
    public static final Pattern SKIP_FIRST_PROMPT_PATTERN = Pattern.compile(
            "^(?:<local-command-stdout>|<session-start-hook>|<tick>|<goal>|"
                    + "\\[Request interrupted by user[^\\]]*\\]|"
                    + "\\s*<ide_opened_file>[\\s\\S]*</ide_opened_file>\\s*$|"
                    + "\\s*<ide_selection>[\\s\\S]*</ide_selection>\\s*$)");

    public static final Pattern COMMAND_NAME_PATTERN = Pattern.compile("<command-name>(.*?)</command-name>");

    private SessionText() {
    }

    /** Head, tail, mtime (epoch ms) and size of a session file. */
    public record LiteSessionFile(long mtime, long size, String head, String tail) {
    }

    /**
     * Reads the head and tail of a session file.
     *
     * @return the lite view, or {@code null} on any error or if the file is empty
     */
    public static LiteSessionFile readSessionLite(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long mtime = java.nio.file.Files.getLastModifiedTime(file).toMillis();
            ByteBuffer headBuffer = ByteBuffer.allocate(LITE_READ_BUF_SIZE);
            readFully(channel, headBuffer, 0);
            if (headBuffer.position() == 0) {
                return null;
            }
            String head = decode(headBuffer);
            long tailOffset = Math.max(0, size - LITE_READ_BUF_SIZE);
            String tail;
            if (tailOffset == 0) {
                tail = head;
            } else {
                ByteBuffer tailBuffer = ByteBuffer.allocate(LITE_READ_BUF_SIZE);
                readFully(channel, tailBuffer, tailOffset);
                tail = decode(tailBuffer);
            }
            return new LiteSessionFile(mtime, size, head, tail);
        } catch (IOException e) {
            return null;
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long offset = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
    }

    /** UTF-8 decode replacing malformed input (a chunk boundary may split a character). */
    static String decode(ByteBuffer buffer) {
        buffer.flip();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(buffer).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalStateException(e); // cannot happen with REPLACE
        }
    }

    /** Unescapes a raw JSON string value; returns it unchanged if it is not valid JSON. */
    public static String unescapeJsonString(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        try {
            return Json.parse("\"" + raw + "\"") instanceof String s ? s : raw;
        } catch (RuntimeException e) {
            return raw;
        }
    }

    /** First value of {@code "key":"value"} (or {@code "key": "value"}) in {@code text}, or {@code null}. */
    public static String extractJsonStringField(String text, String key) {
        for (String pattern : List.of("\"" + key + "\":\"", "\"" + key + "\": \"")) {
            int idx = text.indexOf(pattern);
            if (idx < 0) {
                continue;
            }
            int end = endOfString(text, idx + pattern.length());
            if (end >= 0) {
                return unescapeJsonString(text.substring(idx + pattern.length(), end));
            }
        }
        return null;
    }

    /** Like {@link #extractJsonStringField} but returns the last occurrence. */
    public static String extractLastJsonStringField(String text, String key) {
        String last = null;
        for (String pattern : List.of("\"" + key + "\":\"", "\"" + key + "\": \"")) {
            int from = 0;
            while (true) {
                int idx = text.indexOf(pattern, from);
                if (idx < 0) {
                    break;
                }
                int valueStart = idx + pattern.length();
                int end = endOfString(text, valueStart);
                if (end >= 0) {
                    last = unescapeJsonString(text.substring(valueStart, end));
                    from = end + 1;
                } else {
                    from = text.length() + 1;
                }
            }
        }
        return last;
    }

    /** Index of the closing quote of a JSON string starting at {@code start}, or -1 if truncated. */
    private static int endOfString(String text, int start) {
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * The first meaningful user prompt of a JSONL head chunk: skips tool results, meta and
     * compact-summary entries, slash commands (remembered as fallback) and auto-generated
     * messages; truncated to 200 characters.
     */
    public static String extractFirstPromptFromHead(String head) {
        String commandFallback = "";
        for (String line : head.split("\n", -1)) {
            if (!line.contains("\"type\":\"user\"") && !line.contains("\"type\": \"user\"")) {
                continue;
            }
            if (line.contains("\"tool_result\"")
                    || line.contains("\"isMeta\":true") || line.contains("\"isMeta\": true")
                    || line.contains("\"isCompactSummary\":true") || line.contains("\"isCompactSummary\": true")) {
                continue;
            }
            Map<String, Object> entry;
            try {
                entry = Json.parseObject(line);
            } catch (RuntimeException e) {
                continue;
            }
            if (!"user".equals(entry.get("type"))) {
                continue;
            }
            for (String raw : textsOf(Json.object(entry, "message"))) {
                String result = raw.replace("\n", " ").strip();
                if (result.isEmpty()) {
                    continue;
                }
                Matcher command = COMMAND_NAME_PATTERN.matcher(result);
                if (command.find()) {
                    if (commandFallback.isEmpty()) {
                        commandFallback = command.group(1);
                    }
                    continue;
                }
                if (SKIP_FIRST_PROMPT_PATTERN.matcher(result).lookingAt()) {
                    continue;
                }
                return truncate(result, 200);
            }
        }
        return commandFallback;
    }

    /** Truncates to {@code max} characters, appending an ellipsis. */
    public static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max).stripTrailing() + "…" : value;
    }

    /** The string content, or the text blocks, of a message object. */
    public static List<String> textsOf(Map<String, Object> message) {
        List<String> texts = new ArrayList<>();
        if (message == null) {
            return texts;
        }
        Object content = message.get("content");
        if (content instanceof String s) {
            texts.add(s);
        } else if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> b && "text".equals(b.get("type")) && b.get("text") instanceof String t) {
                    texts.add(t);
                }
            }
        }
        return texts;
    }
}
