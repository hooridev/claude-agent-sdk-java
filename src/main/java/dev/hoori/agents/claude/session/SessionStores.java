package dev.hoori.agents.claude.session;

import java.util.Arrays;

/** Detects which optional {@link SessionStore} methods an adapter implements. */
public final class SessionStores {

    private SessionStores() {
    }

    /**
     * Whether {@code store}'s class overrides the optional method {@code methodName} (the Java
     * counterpart of the Python SDK's "is the Protocol default replaced" probe).
     */
    public static boolean implementsMethod(SessionStore store, String methodName) {
        if (store == null) {
            return false;
        }
        Class<?> type = store.getClass();
        while (type != null && type != Object.class) {
            boolean declared = Arrays.stream(type.getDeclaredMethods())
                    .anyMatch(m -> m.getName().equals(methodName) && !m.isSynthetic() && !m.isBridge());
            if (declared) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }
}
