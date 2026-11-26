package dev.wiktorfila.filasignal.fabric.peripheral;

/**
 * An expected hardware or inventory failure that a host function reports as {@code (nil, error)} with a stable
 * error code. Thrown and caught on the server thread inside one host call.
 */
final class HostFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    HostFailure(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    String code() { return code; }
}
