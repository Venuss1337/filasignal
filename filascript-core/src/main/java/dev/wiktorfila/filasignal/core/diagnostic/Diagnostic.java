package dev.wiktorfila.filasignal.core.diagnostic;

import dev.wiktorfila.filasignal.core.source.SourceSpan;

import java.util.Objects;

public record Diagnostic(Code code, String message, SourceSpan span) {
    public Diagnostic {
        Objects.requireNonNull(code);
        Objects.requireNonNull(message);
        Objects.requireNonNull(span);
    }

    public enum Code {
        INVALID_CHARACTER,
        INVALID_LINE_ENDING,
        UNSUPPORTED_OPERATOR,
        INVALID_NUMBER,
        NUMBER_OUT_OF_RANGE,
        INVALID_ESCAPE,
        INVALID_STRING_CHARACTER,
        UNTERMINATED_STRING,
        UNTERMINATED_COMMENT,
        EXPECTED_TOKEN,
        EXPECTED_EXPRESSION,
        EXPECTED_DECLARATION,
        EXPECTED_SEPARATOR,
        EXPECTED_CALL_STATEMENT,
        INVALID_ASSIGNMENT_TARGET,
        INVALID_ASSIGNMENT_ARITY,
        CHAINED_COMPARISON,
        INVALID_CONSTRUCTOR_NAME,
        UNSUPPORTED_UPDATE_POSITION,
        NESTING_LIMIT,
        UNKNOWN_BINDING,
        DUPLICATE_BINDING,
        IMMUTABLE_BINDING,
        INVALID_DECLARATION,
        INVALID_CONTROL_FLOW,
        INVALID_INITIALIZER,
        FUNCTION_AS_VALUE,
        UNSUPPORTED_FEATURE,
        ANALYSIS_DEPTH_LIMIT,
        UNKNOWN_ENTRY_POINT,
        NOT_CALLABLE,
        ARGUMENT_COUNT,
        RETURN_ARITY,
        TYPE_MISMATCH,
        DIVISION_BY_ZERO,
        NON_FINITE_NUMBER,
        UNSUPPORTED_ITERABLE,
        UNSUPPORTED_DESTRUCTURING,
        EXECUTION_BUDGET,
        CALL_DEPTH_LIMIT,
        EXECUTION_DEPTH_LIMIT,
        COLLECTION_LIMIT,
        INDEX_OUT_OF_BOUNDS,
        MISSING_KEY,
        IMMUTABLE_VALUE,
        DATA_LIMIT,
        OUTPUT_LIMIT,
        CANCELLED,
        PERSISTENCE_FAILURE,
        HOST_FAILURE
    }
}
