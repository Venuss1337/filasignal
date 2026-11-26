package dev.wiktorfila.filasignal.fabric.peripheral;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Persisted ACL for a network root or for a peripheral while it is unlinked. */
record NetworkAccess(UUID owner, Set<UUID> trusted) {
    static final int MAX_TRUSTED = 16;

    NetworkAccess {
        java.util.Objects.requireNonNull(owner);
        trusted = Set.copyOf(trusted);
        if (trusted.size() > MAX_TRUSTED || trusted.contains(owner)) {
            throw new IllegalArgumentException("Invalid network access list");
        }
    }

    NetworkAccess trust(UUID player, boolean grant) {
        var next = new LinkedHashSet<>(trusted);
        if (grant) {
            next.add(player);
        } else {
            next.remove(player);
        }
        return new NetworkAccess(owner, next);
    }
}
