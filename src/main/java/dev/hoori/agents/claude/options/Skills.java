package dev.hoori.agents.claude.options;

import java.util.List;

/**
 * Which skills the session may use. Setting skills also defaults {@code settingSources} to
 * {@code user, project} so installed skills are discovered.
 */
public sealed interface Skills {

    /** Every discovered skill (adds the bare {@code Skill} tool). */
    static Skills all() {
        return new All();
    }

    /** Only these skills (adds {@code Skill(name)} rules and filters the system prompt). */
    static Skills of(String... names) {
        return new Named(List.of(names));
    }

    /** Only these skills. */
    static Skills of(List<String> names) {
        return new Named(names);
    }

    /** All skills. */
    record All() implements Skills {
    }

    /** A list of skill names, or {@code plugin:skill} for plugin skills. */
    record Named(List<String> names) implements Skills {
        public Named {
            names = List.copyOf(names);
        }
    }
}
