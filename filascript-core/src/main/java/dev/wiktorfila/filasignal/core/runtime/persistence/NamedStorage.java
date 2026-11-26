package dev.wiktorfila.filasignal.core.runtime.persistence;

import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded key-value storage for program data that must outlive a run, such as detection logs.
 *
 * <p>Values are detached copies: writes encode the value before anything changes and reads decode a fresh copy,
 * so scripts cannot keep a mutable alias to stored data. Every operation either completes or leaves the storage
 * unchanged. Supported values are nil, Booleans, numbers, strings, lists, tuples and string-keyed dictionaries,
 * nested at most {@value #MAX_DEPTH} levels. Single-owner and not thread-safe.
 */
public final class NamedStorage {
    public static final int CAPACITY = 65_536;
    public static final int MAX_ENTRIES = 256;
    public static final int MAX_KEY = 64;
    public static final int MAX_DEPTH = 16;
    private static final int VERSION = 1;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:-]{1," + MAX_KEY + "}");

    /** An expected storage failure with a stable FilaScript error code. */
    public static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public Failure(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        public String code() { return code; }
    }

    /** Largest configurable capacity and key count, for shared stores such as a Storage Server. */
    public static final int MAX_CAPACITY = 1 << 20;
    public static final int MAX_KEYS = 4096;

    private final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
    private final int capacity;
    private final int maxEntries;
    private int used;

    /** A drive-sized store: {@link #CAPACITY} bytes in {@link #MAX_ENTRIES} keys. */
    public NamedStorage() {
        this(CAPACITY, MAX_ENTRIES);
    }

    /** A store with its own limits. Each single value is still limited to {@link #CAPACITY} bytes. */
    public NamedStorage(int capacity, int maxEntries) {
        if (capacity < 1 || capacity > MAX_CAPACITY || maxEntries < 1 || maxEntries > MAX_KEYS) {
            throw new IllegalArgumentException("Invalid storage limits");
        }
        this.capacity = capacity;
        this.maxEntries = maxEntries;
    }

    public int capacity() { return capacity; }
    public int maxEntries() { return maxEntries; }

    /** Bytes counted against the capacity: each key's UTF-8 length plus its encoded value. */
    public int used() { return used; }
    public int size() { return entries.size(); }
    public boolean has(String key) { return entries.containsKey(key); }
    public List<String> keys() { return List.copyOf(entries.keySet()); }

    /** Returns a fresh copy of the stored value, or null when the key is absent. */
    public Value read(String key) {
        byte[] bytes = entries.get(key);
        if (bytes == null) {
            return null;
        }
        try {
            return decodeValue(new DataInputStream(new ByteArrayInputStream(bytes)), 0);
        } catch (IOException impossible) {
            throw new IllegalStateException("Stored value was validated when written", impossible);
        }
    }

    /** Encoded size of a key's value, or -1 when absent. */
    public int sizeOf(String key) {
        byte[] bytes = entries.get(key);
        return bytes == null ? -1 : bytes.length;
    }

    /**
     * Stores a copy of a value, replacing any previous value.
     *
     * @throws Failure {@code storage.key}, {@code storage.value} or {@code storage.full}; nothing changes
     */
    public void write(String key, Value value) {
        put(requireKey(key), encode(value));
    }

    /**
     * Appends a value to the list stored under a key, creating a one-element list when the key is absent.
     *
     * @throws Failure {@code storage.type} when the key holds something other than a list, or a write failure
     */
    public void append(String key, Value value) {
        requireKey(key);
        byte[] element = encode(value);
        byte[] existing = entries.get(key);
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            if (existing == null) {
                data.writeByte(Tag.LIST);
                data.writeInt(1);
            } else {
                if (existing[0] != Tag.LIST) {
                    throw new Failure("storage.type", "Only a list can be appended to; " + key + " holds another value");
                }
                int count = ((existing[1] & 0xff) << 24) | ((existing[2] & 0xff) << 16) | ((existing[3] & 0xff) << 8) | (existing[4] & 0xff);
                if (count >= Value.MAX_COLLECTION_SIZE) {
                    throw new Failure("storage.full", "A stored list holds at most " + Value.MAX_COLLECTION_SIZE + " items");
                }
                data.writeByte(Tag.LIST);
                data.writeInt(count + 1);
                data.write(existing, 5, existing.length - 5);
            }
            // The appended element is itself at depth one inside the list.
            if (depthOf(element) + 1 > MAX_DEPTH) {
                throw new Failure("storage.value", "Stored values nest at most " + MAX_DEPTH + " levels");
            }
            data.write(element);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        put(key, out.toByteArray());
    }

    /** Removes a key. Returns false when it was absent. */
    public boolean delete(String key) {
        byte[] removed = entries.remove(key);
        if (removed != null) {
            used -= cost(key, removed);
        }
        return removed != null;
    }

    public void clear() {
        entries.clear();
        used = 0;
    }

    private void put(String key, byte[] encoded) {
        byte[] prior = entries.get(key);
        if (prior == null && entries.size() >= maxEntries) {
            throw new Failure("storage.full", "Storage holds at most " + maxEntries + " keys");
        }
        int next = used - (prior == null ? 0 : cost(key, prior)) + cost(key, encoded);
        if (next > capacity) {
            throw new Failure("storage.full", "Storage is full: " + used + " of " + capacity + " bytes used, the value needs "
                    + cost(key, encoded));
        }
        entries.put(key, encoded);
        used = next;
    }

    private static int cost(String key, byte[] value) {
        return key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + value.length;
    }

    private static String requireKey(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new Failure("storage.key", "Keys are 1–" + MAX_KEY + " characters of letters, digits, _ . : or -");
        }
        return key;
    }

    /** Validates and encodes a value without changing storage. */
    public static byte[] encode(Value value) {
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            encodeValue(data, value, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (out.size() > CAPACITY) {
                throw new Failure("storage.full", "A stored value is limited to " + CAPACITY + " bytes");
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toByteArray();
    }

    private static final class Tag {
        static final byte NIL = 0, FALSE = 1, TRUE = 2, NUMBER = 3, TEXT = 4, LIST = 5, TUPLE = 6, DICT = 7;
    }

    private static void encodeValue(DataOutputStream out, Value value, int depth, Set<Value> path) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new Failure("storage.value", "Stored values nest at most " + MAX_DEPTH + " levels");
        }
        if (out.size() > CAPACITY) {
            throw new Failure("storage.full", "A stored value is limited to " + CAPACITY + " bytes");
        }
        switch (value) {
            case Value.Nil ignored -> out.writeByte(Tag.NIL);
            case Bool bool -> out.writeByte(bool.value() ? Tag.TRUE : Tag.FALSE);
            case Num number -> {
                out.writeByte(Tag.NUMBER);
                out.writeDouble(number.value());
            }
            case Text text -> {
                out.writeByte(Tag.TEXT);
                writeString(out, text.value());
            }
            case ListValue list -> {
                enter(list, path);
                out.writeByte(Tag.LIST);
                out.writeInt(list.size());
                for (int i = 0; i < list.size(); i++) {
                    encodeValue(out, list.get(i), depth + 1, path);
                }
                path.remove(list);
            }
            case TupleValue tuple -> {
                enter(tuple, path);
                out.writeByte(Tag.TUPLE);
                out.writeInt(tuple.elements().size());
                for (Value element : tuple.elements()) {
                    encodeValue(out, element, depth + 1, path);
                }
                path.remove(tuple);
            }
            case DictValue dict -> {
                enter(dict, path);
                out.writeByte(Tag.DICT);
                List<String> keys = dict.keys();
                out.writeInt(keys.size());
                for (String key : keys) {
                    writeString(out, key);
                    encodeValue(out, dict.get(key), depth + 1, path);
                }
                path.remove(dict);
            }
            default -> throw new Failure("storage.value", "Only nil, Booleans, numbers, strings, lists, tuples and "
                    + "dictionaries can be stored");
        }
    }

    private static void enter(Value container, Set<Value> path) {
        if (!path.add(container)) {
            throw new Failure("storage.value", "Cyclic values cannot be stored");
        }
    }

    private static void writeString(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > CAPACITY) {
            throw new Failure("storage.full", "A stored value is limited to " + CAPACITY + " bytes");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static int depthOf(byte[] element) {
        try {
            return measure(new DataInputStream(new ByteArrayInputStream(element)));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int measure(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        switch (tag) {
            case Tag.NUMBER -> in.readDouble();
            case Tag.TEXT -> in.skipNBytes(in.readInt());
            case Tag.LIST, Tag.TUPLE -> {
                int count = in.readInt();
                int deepest = 0;
                for (int i = 0; i < count; i++) {
                    deepest = Math.max(deepest, measure(in));
                }
                return deepest + 1;
            }
            case Tag.DICT -> {
                int count = in.readInt();
                int deepest = 0;
                for (int i = 0; i < count; i++) {
                    in.skipNBytes(in.readInt());
                    deepest = Math.max(deepest, measure(in));
                }
                return deepest + 1;
            }
            default -> { }
        }
        return 0;
    }

    private static Value decodeValue(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("Stored value is nested too deeply");
        }
        byte tag = in.readByte();
        return switch (tag) {
            case Tag.NIL -> Value.Nil.INSTANCE;
            case Tag.FALSE -> new Bool(false);
            case Tag.TRUE -> new Bool(true);
            case Tag.NUMBER -> {
                double number = in.readDouble();
                if (!Double.isFinite(number)) {
                    throw new IOException("Stored number is not finite");
                }
                yield new Num(number);
            }
            case Tag.TEXT -> new Text(readString(in));
            case Tag.LIST, Tag.TUPLE -> {
                int count = count(in);
                var elements = new ArrayList<Value>(count);
                for (int i = 0; i < count; i++) {
                    elements.add(decodeValue(in, depth + 1));
                }
                yield tag == Tag.LIST ? new ListValue(elements) : new TupleValue(elements);
            }
            case Tag.DICT -> {
                int count = count(in);
                var dict = new DictValue();
                for (int i = 0; i < count; i++) {
                    String key = readString(in);
                    if (dict.get(key) != null) {
                        throw new IOException("Stored dictionary repeats a key");
                    }
                    dict.put(key, decodeValue(in, depth + 1));
                }
                yield dict;
            }
            default -> throw new IOException("Unknown stored value tag " + tag);
        };
    }

    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > Value.MAX_COLLECTION_SIZE) {
            throw new IOException("Stored collection size is invalid");
        }
        return count;
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > CAPACITY) {
            throw new IOException("Stored string length is invalid");
        }
        var decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
        return decoder.decode(java.nio.ByteBuffer.wrap(in.readNBytes(length))).toString();
    }

    /** Serializes all entries with a format version. */
    public byte[] save() {
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            data.writeInt(VERSION);
            data.writeInt(entries.size());
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                writeString(data, entry.getKey());
                data.writeInt(entry.getValue().length);
                data.write(entry.getValue());
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toByteArray();
    }

    /**
     * Restores saved entries, validating every key and value.
     *
     * @throws StateException {@code CORRUPT} for malformed data or {@code INCOMPATIBLE} for an unknown version
     */
    public static NamedStorage load(byte[] saved) throws StateException {
        return load(saved, CAPACITY, MAX_ENTRIES);
    }

    /** Restores saved entries into a store with the given limits. */
    public static NamedStorage load(byte[] saved, int capacity, int maxEntries) throws StateException {
        var storage = new NamedStorage(capacity, maxEntries);
        if (saved.length > (long) capacity * 2 + 64) {
            throw new StateException(StateException.Reason.LIMIT, "Stored data exceeds the storage limit");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(saved))) {
            int version = in.readInt();
            if (version != VERSION) {
                throw new StateException(StateException.Reason.INCOMPATIBLE, "Unsupported storage format " + version);
            }
            int count = in.readInt();
            if (count < 0 || count > maxEntries) {
                throw new StateException(StateException.Reason.CORRUPT, "Stored key count is invalid");
            }
            for (int i = 0; i < count; i++) {
                String key = readString(in);
                int length = in.readInt();
                if (length < 1 || length > CAPACITY) {
                    throw new StateException(StateException.Reason.CORRUPT, "Stored value length is invalid");
                }
                byte[] value = in.readNBytes(length);
                if (value.length != length || !KEY.matcher(key).matches() || storage.entries.containsKey(key)) {
                    throw new StateException(StateException.Reason.CORRUPT, "Stored key or value is invalid");
                }
                // Decoding validates structure and depth.
                var check = new DataInputStream(new ByteArrayInputStream(value));
                decodeValue(check, 0);
                if (check.available() != 0) {
                    throw new StateException(StateException.Reason.CORRUPT, "Stored value has trailing data");
                }
                storage.put(key, value);
            }
            if (in.available() != 0) {
                throw new StateException(StateException.Reason.CORRUPT, "Storage has trailing data");
            }
        } catch (Failure | IOException | IllegalArgumentException error) {
            if (error instanceof StateException state) {
                throw state;
            }
            throw new StateException(StateException.Reason.CORRUPT, "Stored data is damaged: " + error.getMessage());
        }
        return storage;
    }
}
