package dev.wiktorfila.filasignal.core.runtime.value;

import dev.wiktorfila.filasignal.core.runtime.value.Value.*;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Bounded, read-only text summaries of runtime values for debugging displays.
 *
 * <p>The work is proportional to the requested length: containers show at most {@value #ELEMENTS} entries,
 * nesting stops after {@value #DEPTH} levels and cycles print as {@code <cycle>}. Host handles show only their
 * type, so a summary never calls a host or discloses device data.
 */
public final class ValueSummary {
    private static final int ELEMENTS = 8;
    private static final int DEPTH = 3;

    private final StringBuilder text = new StringBuilder();
    private final Set<Value> path = Collections.newSetFromMap(new IdentityHashMap<>());
    private final int limit;

    private ValueSummary(int limit) {
        this.limit = limit;
    }

    /** Describes a value in at most {@code limit} UTF-16 units, ending with an ellipsis when truncated. */
    public static String describe(Value value, int limit) {
        if (limit < 8) {
            throw new IllegalArgumentException("Summary limit is too small");
        }
        var summary = new ValueSummary(limit);
        summary.append(value, 0);
        if (summary.text.length() <= limit) {
            return summary.text.toString();
        }
        int end = limit - 1;
        if (Character.isHighSurrogate(summary.text.charAt(end - 1))) {
            end--;
        }
        return summary.text.substring(0, end) + "…";
    }

    /** Formats whole numbers without a fraction, such as {@code 3}, and other numbers in Java's shortest form. */
    public static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private boolean full() {
        return text.length() > limit;
    }

    private void append(Value value, int depth) {
        if (full()) {
            return;
        }
        switch (value) {
            case Num number -> text.append(number(number.value()));
            case Bool bool -> text.append(bool.value());
            case Value.Nil ignored -> text.append("nil");
            case Text string -> quote(string.value());
            case ErrorValue error -> text.append("error(").append(clip(error.code())).append(": ")
                    .append(clip(error.message())).append(')');
            case EnumMember member -> text.append(member.enumeration()).append('.').append(member.name());
            case HostHandle handle -> text.append(handle.type()).append(" handle");
            case EnumType type -> text.append("enum ").append(type.name());
            case Namespace namespace -> text.append("namespace ").append(namespace.name().toLowerCase(java.util.Locale.ROOT));
            case Callable ignored -> text.append("function");
            case ListValue list -> container(list, depth, '[', ']', list.size(), index -> list.get(index), null);
            case TupleValue tuple -> container(tuple, depth, '(', ')', tuple.elements().size(),
                    index -> tuple.elements().get(index), null);
            case DictValue dict -> {
                List<String> keys = dict.keys();
                container(dict, depth, '{', '}', keys.size(), index -> dict.get(keys.get(index)), keys);
            }
            case Instance instance -> {
                text.append(instance.type().declaration().name().text());
                List<String> keys = instance.keys();
                container(instance, depth, '{', '}', keys.size(), index -> instance.get(keys.get(index)), keys);
            }
        }
    }

    private void container(Value owner, int depth, char open, char close, int size,
                           java.util.function.IntFunction<Value> element, List<String> keys) {
        if (!path.add(owner)) {
            text.append("<cycle>");
            return;
        }
        try {
            text.append(open);
            if (depth >= DEPTH && size > 0) {
                text.append("… ").append(size).append(size == 1 ? " item" : " items");
            } else {
                int shown = Math.min(size, ELEMENTS);
                for (int index = 0; index < shown && !full(); index++) {
                    if (index > 0) {
                        text.append(", ");
                    }
                    if (keys != null) {
                        text.append(clip(keys.get(index))).append(": ");
                    }
                    append(element.apply(index), depth + 1);
                }
                if (size > shown) {
                    text.append(", … ").append(size - shown).append(" more");
                }
            }
            text.append(close);
        } finally {
            path.remove(owner);
        }
    }

    private void quote(String value) {
        text.append('"');
        int end = Math.min(value.length(), limit);
        for (int index = 0; index < end; index++) {
            char c = value.charAt(index);
            switch (c) {
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                default -> text.append(c < 0x20 ? '?' : c);
            }
        }
        if (end < value.length()) {
            text.append('…');
        }
        text.append('"');
    }

    private String clip(String value) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
