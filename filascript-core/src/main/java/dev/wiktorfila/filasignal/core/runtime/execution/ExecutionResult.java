package dev.wiktorfila.filasignal.core.runtime.execution;

import java.util.Optional;

public record ExecutionResult(ReturnValues returns, Optional<RuntimeIssue> issue, long operationsUsed) {
}
