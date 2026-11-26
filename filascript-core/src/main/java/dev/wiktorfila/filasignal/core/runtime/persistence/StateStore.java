package dev.wiktorfila.filasignal.core.runtime.persistence;

import java.io.IOException;
import java.util.Optional;

/** Storage adapter. A successful save must atomically replace the prior complete snapshot. */
public interface StateStore {
    Optional<byte[]> load(String controller) throws IOException;

    void save(String controller, byte[] snapshot) throws IOException;

    @FunctionalInterface
    interface Migration {
        byte[] migrate(byte[] incompatibleSnapshot, StateException reason) throws IOException;
    }
}
