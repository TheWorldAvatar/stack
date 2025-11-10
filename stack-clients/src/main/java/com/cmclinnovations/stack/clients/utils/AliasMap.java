package com.cmclinnovations.stack.clients.utils;

import java.util.HashMap;

/**
 * A map that allows retrieving and checking for values using any of a set of
 * aliased keys.
 */
public class AliasMap<V> extends HashMap<String, V> {
    @SafeVarargs
    public final boolean containsKey(String... aliases) {
        if (aliases != null) {
            for (String alias : aliases) {
                if (super.containsKey(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    @SafeVarargs
    public final V get(String... aliases) {
        if (aliases != null) {
            for (String alias : aliases) {
                if (super.containsKey(alias)) {
                    return super.get(alias);
                }
            }
        }
        return (V) null;
    }

    @SafeVarargs
    public final V put(String key, V value, String... aliases) {
        if (aliases != null) {
            for (String alias : aliases) {
                super.remove(alias); // Remove existing mappings for aliases
            }
        }
        return super.put(key, value);
    }
}
