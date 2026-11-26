package dev.wiktorfila.filasignal.core.runtime.execution;

public record ExecutionLimits(int maxCallDepth, long operations, long dataUnits, int logLines, long logUnits) {
    public static final ExecutionLimits DEFAULT = new ExecutionLimits(64, 100_000);

    public ExecutionLimits(int maxCallDepth, long operations) {
        this(maxCallDepth, operations, 262_144, 256, 32_768);
    }

    public ExecutionLimits {
        if (maxCallDepth < 1 || operations < 1 || dataUnits < 1 || logLines < 0 || logUnits < 0) {
            throw new IllegalArgumentException("Invalid execution limits");
        }
    }
}
