package dev.wiktorfila.filasignal.fabric.editor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A bounded set of completions for the exact draft and caret that opened it.
 *
 * <p>Catalog lines are {@code signature<TAB>description[<TAB>type]}. Lines starting with {@code "on "} are events;
 * the optional type names the device object a function returns, such as {@code EntityDetector}. The menu understands
 * three situations:
 * <ul>
 *   <li>after {@code on} at the start of a line it offers events and inserts a complete handler;</li>
 *   <li>after {@code name.} where {@code name} was assigned from a device function, it offers that device's members,
 *       or after {@code on name.} its events;</li>
 *   <li>otherwise it offers names starting with the typed prefix.</li>
 * </ul>
 */
final class CompletionMenu {
    record Entry(String insertion, String signature, String detail) {}

    private static final int LIMIT = 32;
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?m)^\\s*(?:const\\s+|global\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*(?:,\\s*[A-Za-z_][A-Za-z0-9_]*\\s*)?=\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\(");

    private final int start;
    private final List<Entry> entries;
    private int selected;

    private CompletionMenu(int start, List<Entry> entries) {
        this.start = start;
        this.entries = entries;
    }

    static CompletionMenu open(String draft, int caret, List<String> catalog) {
        int start = caret;
        while (start > 0) {
            char ch = draft.charAt(start - 1);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.') {
                break;
            }
            start--;
        }
        String prefix = draft.substring(start, caret);
        int lineStart = draft.lastIndexOf('\n', start - 1) + 1;
        String before = draft.substring(lineStart, start);
        boolean handler = before.strip().equals("on") && before.endsWith(" ");
        int dot = prefix.lastIndexOf('.');
        Map<String, String> types = types(draft, catalog);

        if (handler) {
            if (dot > 0 && types.containsKey(prefix.substring(0, dot))) {
                // on gate.ent… : the events of the device the constant refers to.
                String type = types.get(prefix.substring(0, dot));
                String member = prefix.substring(dot + 1);
                return new CompletionMenu(start + dot + 1, catalog.stream().filter(line -> line.startsWith("on " + type + "."))
                        .map(line -> handler(line, ("on " + type + ".").length())).filter(item -> item.insertion().startsWith(member))
                        .limit(LIMIT).toList());
            }
            return new CompletionMenu(start, catalog.stream().filter(line -> line.startsWith("on ")
                            && !Character.isUpperCase(line.charAt(3)))
                    .map(line -> handler(line, 3)).filter(item -> item.insertion().startsWith(prefix)).limit(LIMIT).toList());
        }
        if (dot > 0 && types.containsKey(prefix.substring(0, dot))) {
            // gate.sc… : members of the device the variable holds.
            String type = types.get(prefix.substring(0, dot));
            String member = prefix.substring(dot + 1);
            return new CompletionMenu(start + dot + 1, catalog.stream()
                    .filter(line -> line.startsWith(type + ".") && !line.startsWith("on "))
                    .map(line -> entry(line, true)).filter(item -> item.insertion().startsWith(member)).limit(LIMIT).toList());
        }
        var exact = catalog.stream().filter(line -> !line.startsWith("on ")).map(line -> entry(line, false))
                .filter(item -> item.insertion().startsWith(prefix)).limit(LIMIT).toList();
        if (!exact.isEmpty() || dot < 0) {
            return new CompletionMenu(start, exact);
        }
        // A dynamic receiver is not a type declaration. Keep the host type visible on fallback items.
        String member = prefix.substring(dot + 1);
        return new CompletionMenu(start + dot + 1, catalog.stream()
                .filter(line -> Character.isUpperCase(line.charAt(0)) && line.indexOf('.') > 0
                        && Character.isLowerCase(line.charAt(line.indexOf('.') + 1)))
                .map(line -> entry(line, true)).filter(item -> item.insertion().startsWith(member))
                .limit(LIMIT).toList());
    }

    /** Variables and constants assigned from a catalog function that names the device type it returns. */
    static Map<String, String> types(String draft, List<String> catalog) {
        var returns = new HashMap<String, String>();
        for (String line : catalog) {
            String[] parts = line.split("\t");
            int paren = parts[0].indexOf('(');
            if (parts.length >= 3 && paren > 0 && !parts[2].isEmpty()) {
                returns.put(parts[0].substring(0, paren), parts[2]);
            }
        }
        var types = new HashMap<String, String>();
        types.put("device", "device");
        var matcher = ASSIGNMENT.matcher(draft.length() > 65_536 ? draft.substring(0, 65_536) : draft);
        while (matcher.find()) {
            String type = returns.get(matcher.group(2));
            if (type != null) {
                types.put(matcher.group(1), type);
            }
        }
        return types;
    }

    private static Entry entry(String line, boolean member) {
        String[] parts = line.split("\t");
        String signature = parts[0];
        String detail = parts.length > 1 ? parts[1] : "";
        int paren = signature.indexOf('(');
        String name = paren < 0 ? signature : signature.substring(0, paren);
        if (member) { name = name.substring(name.indexOf('.') + 1); }
        return new Entry(name, signature, detail);
    }

    /** An event line becomes a complete handler, such as {@code entityEnter(entity) {\n    \n}}. */
    private static Entry handler(String line, int skip) {
        String[] parts = line.split("\t");
        String signature = parts[0].substring(skip);
        String detail = parts.length > 1 ? parts[1] : "";
        return new Entry(signature + " {\n    \n}", parts[0], detail);
    }

    int start() { return start; }
    boolean empty() { return entries.isEmpty(); }
    Entry selected() { return entries.get(selected); }
    boolean selected(Entry entry) { return entry.equals(selected()); }
    void move(int amount) { selected = Math.floorMod(selected + amount, entries.size()); }
    List<Entry> visible() {
        int first = Math.min(selected, Math.max(0, entries.size() - 5));
        return entries.subList(first, Math.min(first + 5, entries.size()));
    }
}
