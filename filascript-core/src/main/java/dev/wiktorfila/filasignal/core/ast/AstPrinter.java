package dev.wiktorfila.filasignal.core.ast;

import dev.wiktorfila.filasignal.core.ast.Ast.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Deterministic S-expressions for inspection. Walks long expression chains without recursion. */
public final class AstPrinter {
    private AstPrinter() {
    }

    public static String print(Node root) {
        StringBuilder output = new StringBuilder();
        ArrayDeque<Frame> pending = new ArrayDeque<>();
        pending.push(new Frame(root, 0));

        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            output.append("  ".repeat(Math.min(frame.depth(), 16)));
            if (frame.node() == null) {
                output.append(")\n");
                continue;
            }
            Description description = describe(frame.node());
            output.append('(').append(description.label());
            List<? extends Node> children = description.children();
            if (children.isEmpty()) {
                output.append(")\n");
                continue;
            }
            output.append('\n');
            pending.push(new Frame(null, frame.depth()));
            for (int i = children.size() - 1; i >= 0; i--) {
                pending.push(new Frame(children.get(i), frame.depth() + 1));
            }
        }

        return output.toString();
    }

    private static Description describe(Node node) {
        return switch (node) {
            case Program program -> new Description("Program", program.declarations());
            case Binding binding -> binding.type() == null
                    ? describe("Binding", binding.name())
                    : describe("Binding", binding.name(), binding.type());
            case BindingDeclaration binding -> describe("BindingDeclaration " + binding.kind(),
                    binding.binding(), binding.initializer());
            case Function function -> new Description("Function",
                    join(List.of(function.name()), function.parameters(), List.of(function.body())));
            case Constructor constructor -> new Description("Constructor",
                    join(List.of(constructor.name()), constructor.parameters(), List.of(constructor.body())));
            case ClassDeclaration clazz -> new Description("Class",
                    join(List.of(clazz.name()), clazz.members(), List.of()));
            case EnumDeclaration enumeration -> new Description("Enum",
                    join(List.of(enumeration.name()), enumeration.members(), List.of()));
            case EventDeclaration event -> {
                String qualified = String.join(".", event.name().stream().map(Name::text).toList());
                yield new Description("Event " + qualified, join(event.arguments(), List.of(event.body()), List.of()));
            }
            case Block block -> new Description("Block", block.statements());
            case Assignment assignment -> new Description("Assignment " + assignment.operator()
                    + " targets=" + assignment.targets().size(), join(assignment.targets(), assignment.values(), List.of()));
            case Update update -> describe("Update " + update.operator(), update.target());
            case CallStatement statement -> describe("CallStatement", statement.call());
            case If conditional -> new Description("If",
                    conditional.fallback() == null ? conditional.branches()
                            : join(conditional.branches(), List.of(conditional.fallback()), List.of()));
            case Branch branch -> describe("Branch", branch.condition(), branch.body());
            case While loop -> describe("While", loop.condition(), loop.body());
            case For loop -> describe("For", loop.variable(), loop.iterable(), loop.body());
            case Break ignored -> describe("Break");
            case Continue ignored -> describe("Continue");
            case Return returned -> new Description("Return", returned.values());
            case Name name -> describe("Name " + name.text());
            case Self ignored -> describe("Self");
            case NumberLiteral number -> describe("Number " + number.spelling());
            case StringLiteral string -> describe("String " + quote(string.value()));
            case BooleanLiteral bool -> describe("Boolean " + bool.value());
            case Nil ignored -> describe("Nil");
            case Group group -> describe("Group", group.expression());
            case Unary unary -> describe("Unary " + unary.operator(), unary.operand());
            case Binary binary -> describe("Binary " + binary.operator(), binary.left(), binary.right());
            case Call call -> new Description("Call", join(List.of(call.callee()), call.arguments(), List.of()));
            case Member member -> describe("Member", member.receiver(), member.name());
            case Index index -> describe("Index", index.receiver(), index.index());
            case ListLiteral list -> new Description("List", list.elements());
            case Tuple tuple -> new Description("Tuple", tuple.elements());
            case Dictionary dictionary -> new Description("Dictionary", dictionary.entries());
            case DictionaryEntry entry -> describe("Entry " + quote(entry.key()), entry.value());
        };
    }

    private static Description describe(String label, Node... children) {
        return new Description(label, List.of(children));
    }

    private static List<Node> join(List<? extends Node> first, List<? extends Node> middle, List<? extends Node> last) {
        List<Node> children = new ArrayList<>(first.size() + middle.size() + last.size());
        children.addAll(first);
        children.addAll(middle);
        children.addAll(last);
        return children;
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(character)) {
                        escaped.append("\\u{").append(Integer.toHexString(character)).append('}');
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private record Frame(Node node, int depth) {
    }

    private record Description(String label, List<? extends Node> children) {
    }
}
