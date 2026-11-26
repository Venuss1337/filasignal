package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.ast.Ast.BindingDeclaration;
import dev.wiktorfila.filasignal.core.runtime.persistence.StateException;
import dev.wiktorfila.filasignal.core.runtime.persistence.StateException.Reason;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.CRC32;

/** Bounded versioned object table. References are local to a snapshot. */
final class GlobalSnapshot {
    static final int MAX_BYTES = 262_144;
    private static final int MAX_OBJECTS = 16_384;
    private static final int MAGIC = 0x46534753; // FSGS
    private static final int VERSION = 1;
    private static final int NIL = 0, NUMBER = 1, BOOLEAN = 2, STRING = 3, ERROR = 4,
            LIST = 5, TUPLE = 6, DICTIONARY = 7, ENUM = 8, INSTANCE = 9;

    record Global(String type, Value value) {
    }

    private record Field(String name, String type) {
    }

    private record Definition(int tag, String name, List<Field> fields, int[] children) {
    }

    private record Root(String name, String type, int reference) {
    }

    static byte[] encode(Map<String, Global> globals) throws IOException {
        var bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(new OutputStream() {
            private void check(int length) throws StateException {
                if (length > MAX_BYTES - 4 - bytes.size()) {
                    throw problem(Reason.LIMIT, "Snapshot exceeds the storage limit");
                }
            }

            @Override
            public void write(int value) throws IOException {
                check(1);
                bytes.write(value);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                check(length);
                bytes.write(buffer, offset, length);
            }
        });

        var graph = new Graph();
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        if (globals.size() > MAX_OBJECTS) {
            throw problem(Reason.LIMIT, "Too many globals");
        }
        output.writeInt(globals.size());
        for (var root : globals.entrySet()) {
            text(output, root.getKey());
            text(output, root.getValue().type());
            output.writeInt(graph.id(root.getValue().value()));
        }
        // Entries can grow as their references are discovered. The end marker closes the table.
        for (int node = 0; node < graph.values.size(); node++) {
            writeValue(output, graph, graph.values.get(node));
        }
        output.writeByte(255);
        byte[] body = bytes.toByteArray();
        new DataOutputStream(bytes).writeInt(checksum(body, body.length));
        return bytes.toByteArray();
    }

    private static void writeValue(DataOutputStream out, Graph graph, Value value) throws IOException {
        switch (value) {
            case Nil ignored -> out.writeByte(NIL);
            case Num n -> {
                out.writeByte(NUMBER);
                out.writeDouble(n.value());
            }
            case Bool b -> {
                out.writeByte(BOOLEAN);
                out.writeBoolean(b.value());
            }
            case Text text -> {
                out.writeByte(STRING);
                text(out, text.value());
            }
            case ErrorValue error -> {
                out.writeByte(ERROR);
                text(out, error.code());
                text(out, error.message());
            }
            case ListValue list -> {
                out.writeByte(LIST);
                references(out, graph, list.snapshot());
            }
            case TupleValue tuple -> {
                out.writeByte(TUPLE);
                references(out, graph, tuple.elements());
            }
            case HostHandle ignored -> throw problem(Reason.UNSUPPORTED, "Host handles are transient; store an alias instead");
            case DictValue dictionary -> {
                if (!dictionary.writable()) {
                    throw problem(Reason.UNSUPPORTED, "Copy the read-only host snapshot fields to persist them");
                }
                out.writeByte(DICTIONARY);
                out.writeInt(dictionary.size());
                for (String key : dictionary.keys()) {
                    text(out, key);
                    out.writeInt(graph.id(dictionary.get(key)));
                }
            }
            case EnumMember member -> {
                out.writeByte(ENUM);
                text(out, member.enumeration());
                text(out, member.name());
            }
            case Instance instance -> {
                out.writeByte(INSTANCE);
                text(out, instance.type().declaration().name().text());
                List<Field> schema = fields(instance.type());
                if (!instance.keys().equals(schema.stream().map(Field::name).toList())) {
                    throw problem(Reason.UNSUPPORTED, "Instance does not match its declared field schema");
                }
                out.writeInt(schema.size());
                for (Field field : schema) {
                    text(out, field.name());
                    text(out, field.type());
                    out.writeInt(graph.id(instance.get(field.name())));
                }
            }
            default -> throw problem(Reason.UNSUPPORTED, "Snapshot contains a non-persistable value");
        }
    }

    private static void references(DataOutputStream out, Graph graph, List<Value> values) throws IOException {
        out.writeInt(values.size());
        for (Value value : values) {
            out.writeInt(graph.id(value));
        }
    }

    static Map<String, Value> decode(byte[] bytes, Map<String, String> globals, Function<String, Value> types)
            throws StateException {
        if (bytes.length > MAX_BYTES) {
            throw problem(Reason.LIMIT, "Snapshot exceeds the storage limit");
        }
        if (bytes.length < 17 || checksum(bytes, bytes.length - 4)
                != ByteBuffer.wrap(bytes, bytes.length - 4, 4).getInt()) {
            throw problem(Reason.CORRUPT, "Incomplete snapshot or checksum mismatch");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 4))) {
            if (input.readInt() != MAGIC) {
                throw problem(Reason.CORRUPT, "Unknown snapshot signature");
            }
            if (input.readInt() != VERSION) {
                throw problem(Reason.INCOMPATIBLE, "Unsupported snapshot format version");
            }

            var roots = new ArrayList<Root>();
            int rootCount = count(input, MAX_OBJECTS);
            var names = new HashSet<String>();
            for (int i = 0; i < rootCount; i++) {
                String name = readText(input);
                String type = readText(input);
                if (!names.add(name)) {
                    throw problem(Reason.CORRUPT, "Duplicate snapshot global");
                }
                if (!type.equals(globals.get(name))) {
                    throw problem(Reason.INCOMPATIBLE, "Saved global was removed or changed its annotation: " + name);
                }
                roots.add(new Root(name, type, input.readInt()));
            }

            var values = new ArrayList<Value>();
            var definitions = new ArrayList<Definition>();
            for (int tag = input.readUnsignedByte(); tag != 255; tag = input.readUnsignedByte()) {
                if (values.size() == MAX_OBJECTS) {
                    throw problem(Reason.LIMIT, "Snapshot exceeds object count limit");
                }
                readValue(tag, input, types, values, definitions);
            }
            if (input.read() != -1) {
                throw problem(Reason.CORRUPT, "Trailing snapshot data");
            }

            materializeTuples(values, definitions);
            for (int id = 0; id < values.size(); id++) {
                fill(values.get(id), definitions.get(id), values);
            }
            var restored = new LinkedHashMap<String, Value>();
            for (Root root : roots) {
                Value value = values.get(reference(root.reference(), values.size()));
                checkType(root.type(), value);
                restored.put(root.name(), value);
            }
            return restored;
        } catch (StateException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw problem(Reason.CORRUPT, "Malformed snapshot data");
        }
    }

    private static void readValue(int tag, DataInputStream input, Function<String, Value> types,
                                  List<Value> values, List<Definition> definitions) throws IOException {
        Value value;
        Definition definition = null;
        switch (tag) {
            case NIL -> value = Nil.INSTANCE;
            case NUMBER -> value = new Num(input.readDouble());
            case BOOLEAN -> {
                int flag = input.readUnsignedByte();
                if (flag > 1) {
                    throw problem(Reason.CORRUPT, "Invalid Boolean");
                }
                value = new Bool(flag == 1);
            }
            case STRING -> value = new Text(readText(input));
            case ERROR -> value = new ErrorValue(readText(input), readText(input));
            case ENUM -> {
                String type = readText(input);
                String member = readText(input);
                if (!(types.apply(type) instanceof EnumType enumeration) || enumeration.members().get(member) == null) {
                    throw problem(Reason.INCOMPATIBLE, "Saved enum member no longer exists");
                }
                value = enumeration.members().get(member);
            }
            case LIST, TUPLE, DICTIONARY, INSTANCE -> {
                String name = tag == INSTANCE ? readText(input) : "";
                int size = count(input, Value.MAX_COLLECTION_SIZE);
                var fields = new ArrayList<Field>();
                var keys = new HashSet<String>();
                var references = new int[size];
                for (int i = 0; i < size; i++) {
                    if (tag == DICTIONARY || tag == INSTANCE) {
                        String key = readText(input);
                        if (!keys.add(key)) {
                            throw problem(Reason.CORRUPT, "Duplicate saved field or key");
                        }
                        fields.add(new Field(key, tag == INSTANCE ? readText(input) : ""));
                    }
                    references[i] = input.readInt();
                }
                definition = new Definition(tag, name, fields, references);
                value = switch (tag) {
                    case LIST -> new ListValue(List.of());
                    case TUPLE -> null;
                    case DICTIONARY -> new DictValue();
                    default -> {
                        if (!(types.apply(name) instanceof ClassType type) || !fields(type).equals(fields)) {
                            throw problem(Reason.INCOMPATIBLE, "Saved class schema changed: " + name);
                        }
                        yield new Instance(type);
                    }
                };
            }
            default -> throw problem(Reason.CORRUPT, "Unknown snapshot value tag");
        }
        values.add(value);
        definitions.add(definition);
    }

    private static void materializeTuples(List<Value> values, List<Definition> definitions) throws StateException {
        byte[] state = new byte[values.size()];
        var stack = new java.util.ArrayDeque<TupleFrame>();
        for (int id = 0; id < values.size(); id++) {
            if (values.get(id) != null) {
                continue;
            }
            stack.push(new TupleFrame(id));
            state[id] = 1;
            while (!stack.isEmpty()) {
                TupleFrame frame = stack.peek();
                int[] children = definitions.get(frame.id).children();
                if (frame.position == children.length) {
                    var elements = new ArrayList<Value>(children.length);
                    for (int child : children) {
                        elements.add(values.get(child));
                    }
                    values.set(frame.id, new TupleValue(elements));
                    state[frame.id] = 2;
                    stack.pop();
                    continue;
                }
                int child = reference(children[frame.position], values.size());
                frame.position++;
                if (values.get(child) == null) {
                    if (state[child] == 1) {
                        throw problem(Reason.CORRUPT, "Snapshot contains a cycle of immutable tuple slots");
                    }
                    state[child] = 1;
                    stack.push(new TupleFrame(child));
                }
            }
        }
    }

    private static final class TupleFrame {
        private final int id;
        private int position;

        private TupleFrame(int id) {
            this.id = id;
        }
    }

    private static void fill(Value value, Definition definition, List<Value> values) throws StateException {
        if (definition == null || definition.tag() == TUPLE) {
            return;
        }
        for (int i = 0; i < definition.children().length; i++) {
            Value child = values.get(reference(definition.children()[i], values.size()));
            switch (value) {
                case ListValue list -> list.insert(list.size(), child);
                case DictValue dictionary -> dictionary.put(definition.fields().get(i).name(), child);
                case Instance instance -> {
                    Field field = definition.fields().get(i);
                    checkType(field.type(), child);
                    instance.put(field.name(), child);
                }
                default -> throw problem(Reason.CORRUPT, "Invalid snapshot aggregate");
            }
        }
    }

    private static void checkType(String type, Value value) throws StateException {
        if (!type.isEmpty() && !type.equals(Interpreter.typeName(value))) {
            throw problem(Reason.INCOMPATIBLE, "Saved value does not match declared type " + type);
        }
    }

    private static List<Field> fields(ClassType type) {
        return type.declaration().members().stream().filter(BindingDeclaration.class::isInstance)
                .map(BindingDeclaration.class::cast)
                .map(field -> new Field(field.binding().name().text(),
                        field.binding().type() == null ? "" : field.binding().type().text())).toList();
    }

    private static int reference(int id, int size) throws StateException {
        if (id < 0 || id >= size) {
            throw problem(Reason.CORRUPT, "Snapshot reference points outside the value table");
        }
        return id;
    }

    private static int count(DataInputStream input, int max) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > max) {
            throw problem(Reason.LIMIT, "Snapshot field or entry count exceeds limits");
        }
        return count;
    }

    private static void text(DataOutputStream output, String text) throws IOException {
        if (text.length() > MAX_BYTES || text.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)) {
            throw problem(Reason.UNSUPPORTED, "Cannot encode oversized or malformed text");
        }
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = count(input, MAX_BYTES);
        if (length > input.available()) {
            throw problem(Reason.CORRUPT, "Truncated snapshot text");
        }
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
    }

    private static int checksum(byte[] data, int size) {
        var crc = new CRC32();
        crc.update(data, 0, size);
        return (int) crc.getValue();
    }

    private static StateException problem(Reason reason, String message) {
        return new StateException(reason, message);
    }

    private static final class Graph {
        private final IdentityHashMap<Value, Integer> ids = new IdentityHashMap<>();
        private final List<Value> values = new ArrayList<>();

        private int id(Value value) throws StateException {
            Integer known = ids.get(value);
            if (known != null) {
                return known;
            }
            if (values.size() == MAX_OBJECTS) {
                throw problem(Reason.LIMIT, "Snapshot exceeds value table limit");
            }
            int id = values.size();
            ids.put(value, id);
            values.add(value);
            return id;
        }
    }
}
