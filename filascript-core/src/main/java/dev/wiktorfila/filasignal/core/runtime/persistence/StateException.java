package dev.wiktorfila.filasignal.core.runtime.persistence;

import java.io.IOException;

public final class StateException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason { CORRUPT, INCOMPATIBLE, LIMIT, UNSUPPORTED }

    private final Reason reason;

    public StateException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
