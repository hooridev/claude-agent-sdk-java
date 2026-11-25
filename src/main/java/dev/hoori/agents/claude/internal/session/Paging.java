package dev.hoori.agents.claude.internal.session;

import java.util.List;

/** Offset/limit paging shared by the session read functions. */
final class Paging {

    private Paging() {
    }

    /** Skips {@code offset} items (if positive), then keeps at most {@code limit} (if positive). */
    static <T> List<T> page(List<T> items, int limit, int offset) {
        int from = Math.clamp(offset, 0, items.size());
        int to = limit > 0 ? (int) Math.min(items.size(), (long) from + limit) : items.size();
        return List.copyOf(items.subList(from, to));
    }
}
