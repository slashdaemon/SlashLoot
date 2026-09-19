package dev.blockacademy.slashloot.core;

import dev.blockacademy.slashloot.SlashLootMod;
import dev.blockacademy.slashloot.config.SlashLootConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opt-in decision logging ({@code "debugLogging": true} in {@code config/slashloot.json}).
 *
 * <p>Emitted from {@link Handling} so the log always reflects the decision that was actually taken —
 * there is no second code path that could disagree with it.
 *
 * <p>{@code unpackLootTable} is reached by hopper polling, so identical repeat decisions are
 * suppressed through a small bounded LRU. A container that changes verdict (config reload, block
 * replaced) logs again.
 */
public final class DebugLog {

    private static final int MAX_TRACKED = 512;

    private static final Map<String, String> LAST_VERDICT =
            new LinkedHashMap<>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_TRACKED;
                }
            };

    private DebugLog() {}

    public static boolean enabled() {
        return SlashLootConfig.get().debugLogging;
    }

    /**
     * Logs one decision line, unless the same key already produced the same line.
     *
     * @param key   stable identity of the container (dimension + packed pos, or entity uuid)
     * @param line  the fully-rendered log line
     */
    public static void decision(String key, String line) {
        if (!enabled()) return;
        synchronized (LAST_VERDICT) {
            String previous = LAST_VERDICT.put(key, line);
            if (line.equals(previous)) return;
        }
        SlashLootMod.LOG.info("{}", line);
    }

    /** Unconditional info line for admin-triggered actions (prune, forget) — not deduped. */
    public static void action(String line) {
        if (!enabled()) return;
        SlashLootMod.LOG.info("{}", line);
    }

    /** Drops remembered verdicts so a config reload re-logs everything. */
    public static void reset() {
        synchronized (LAST_VERDICT) {
            LAST_VERDICT.clear();
        }
    }
}
