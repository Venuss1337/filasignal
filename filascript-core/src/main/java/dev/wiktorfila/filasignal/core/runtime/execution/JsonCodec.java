package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/** JSON is an interchange format only. Decode directly into native runtime values. */
final class JsonCodec {
    private static final int MAX_TEXT = 65_536;
    private static final int MAX_DEPTH = 64;
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private final String source;
    private int cursor;

    private JsonCodec(String source) {
        this.source = source;
    }

    static Value parse(String source) {
        if (source.length() > MAX_TEXT) {
            throw new Invalid("JSON input exceeds " + MAX_TEXT + " UTF-16 units");
        }
        JsonCodec reader = new JsonCodec(source);
        Value value = reader.value(0);
        reader.space();
        if (reader.cursor != source.length()) {
            throw reader.invalid("Unexpected trailing input");
        }
        return value;
    }

    private Value value(int depth) {
        if (depth > MAX_DEPTH) {
            throw invalid("JSON nesting limit exceeded");
        }
        space();
        return switch (peek()) {
            case '[' -> array(depth);
            case '{' -> object(depth);
            case '"' -> new Text(string());
            case 't' -> literal("true", new Bool(true));
            case 'f' -> literal("false", new Bool(false));
            case 'n' -> literal("null", Nil.INSTANCE);
            default -> number();
        };
    }

    private Value array(int depth) {
        cursor++;
        space();
        var elements = new ArrayList<Value>();
        if (!take(']')) {
            do {
                if (elements.size() == Value.MAX_COLLECTION_SIZE) {
                    throw invalid("Array exceeds the collection size limit");
                }
                elements.add(value(depth + 1));
                space();
            } while (take(','));
            expect(']');
        }
        return new ListValue(elements);
    }

    private Value object(int depth) {
        cursor++;
        space();
        var dictionary = new DictValue();
        if (!take('}')) {
            do {
                space();
                String key = string();
                if (dictionary.get(key) != null) {
                    throw invalid("Duplicate object key");
                }
                if (dictionary.size() == Value.MAX_COLLECTION_SIZE) {
                    throw invalid("Object exceeds the collection size limit");
                }
                space();
                expect(':');
                dictionary.put(key, value(depth + 1));
                space();
            } while (take(','));
            expect('}');
        }
        return dictionary;
    }

    private Value literal(String text, Value value) {
        if (!source.startsWith(text, cursor)) {
            throw invalid("Expected " + text);
        }
        cursor += text.length();
        return value;
    }

    private Value number() {
        var match = NUMBER.matcher(source).region(cursor, source.length());
        if (!match.lookingAt()) {
            throw invalid("Expected a JSON value");
        }
        double value = Double.parseDouble(match.group());
        cursor = match.end();
        if (!Double.isFinite(value)) {
            throw invalid("Number exceeds finite binary64 range");
        }
        return new Num(value);
    }

    private String string() {
        expect('"');
        var output = new StringBuilder();
        while (cursor < source.length()) {
            char character = source.charAt(cursor++);
            if (character == '"') {
                String result = output.toString();
                checkUnicode(result);
                return result;
            }
            if (character == '\\') {
                if (cursor == source.length()) {
                    throw invalid("Incomplete escape");
                }
                character = switch (source.charAt(cursor++)) {
                    case '"', '\\', '/' -> source.charAt(cursor - 1);
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> hex();
                    default -> throw invalid("Invalid escape");
                };
            } else if (character < ' ') {
                throw invalid("Unescaped control character");
            }
            output.append(character);
        }
        throw invalid("Unterminated string");
    }

    private char hex() {
        int result = 0;
        for (int i = 0; i < 4; i++) {
            int c = peek();
            int digit = c >= '0' && c <= '9' ? c - '0'
                    : c >= 'a' && c <= 'f' ? c - 'a' + 10
                    : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) {
                throw invalid("Expected four hexadecimal digits");
            }
            cursor++;
            result = result * 16 + digit;
        }
        return (char) result;
    }

    private int peek() {
        return cursor == source.length() ? -1 : source.charAt(cursor);
    }

    private void space() {
        while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') {
            cursor++;
        }
    }

    private boolean take(char next) {
        if (peek() != next) {
            return false;
        }
        cursor++;
        return true;
    }

    private void expect(char next) {
        if (!take(next)) {
            throw invalid("Expected '" + next + "'");
        }
    }

    private Invalid invalid(String message) {
        return new Invalid(message + " at JSON UTF-16 offset " + cursor);
    }

    private static void checkUnicode(String text) {
        if (text.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)) {
            throw new Invalid("Unpaired Unicode surrogate in JSON string");
        }
    }

    static String stringify(Value value, IntConsumer charge) {
        var writer = new Writer(charge);
        writer.value(value, 0);
        return writer.output.toString();
    }

    private static final class Writer {
        private final StringBuilder output = new StringBuilder();
        private final IdentityHashMap<Value, Boolean> active = new IdentityHashMap<>();
        private final IntConsumer charge;

        private Writer(IntConsumer charge) {
            this.charge = charge;
        }

        private void value(Value value, int depth) {
            if (depth > MAX_DEPTH) {
                throw new Invalid("JSON nesting limit exceeded");
            }
            if (active.put(value, true) != null) {
                throw new Invalid("Cyclic value cannot be represented in JSON");
            }
            try {
                switch (value) {
                    case Nil ignored -> append("null");
                    case Bool bool -> append(Boolean.toString(bool.value()));
                    case Num number -> append(Double.toString(number.value()));
                    case Text text -> string(text.value());
                    case ListValue list -> {
                        append("[");
                        for (int i = 0; i < list.size(); i++) {
                            if (i != 0) {
                                append(",");
                            }
                            value(list.get(i), depth + 1);
                        }
                        append("]");
                    }
                    case DictValue dictionary -> {
                        append("{");
                        boolean first = true;
                        for (String key : dictionary.keys()) {
                            if (!first) {
                                append(",");
                            }
                            first = false;
                            string(key);
                            append(":");
                            value(dictionary.get(key), depth + 1);
                        }
                        append("}");
                    }
                    default -> throw new Invalid("Value type cannot be represented in JSON");
                }
            } finally {
                active.remove(value);
            }
        }

        private void string(String text) {
            if (text.length() > MAX_TEXT) {
                throw new Invalid("JSON output size limit exceeded");
            }
            charge.accept(text.length());
            checkUnicode(text);
            append("\"");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '\\' -> append("\\\\");
                    case '"' -> append("\\\"");
                    default -> {
                        if (c < ' ') {
                            append("\\u%04x".formatted((int) c));
                        } else {
                            append(Character.toString(c));
                        }
                    }
                }
            }
            append("\"");
        }

        private void append(String text) {
            charge.accept(text.length());
            if (text.length() > MAX_TEXT - output.length()) {
                throw new Invalid("JSON output size limit exceeded");
            }
            output.append(text);
        }
    }

    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1;

        Invalid(String message) {
            super(message, null, false, false);
        }
    }
}
