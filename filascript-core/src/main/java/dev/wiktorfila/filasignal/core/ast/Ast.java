package dev.wiktorfila.filasignal.core.ast;

import dev.wiktorfila.filasignal.core.source.SourceSpan;

import java.util.List;

/** Immutable syntax tree. Names and calls retain their syntactic form for later resolution. */
public final class Ast {
    private Ast() {
    }

    public sealed interface Node {
        SourceSpan span();
    }

    public sealed interface Declaration extends Node {
    }

    public sealed interface ClassMember extends Node {
    }

    public sealed interface Statement extends Node {
    }

    public sealed interface Expression extends Node {
    }

    public sealed interface Target extends Expression permits Name, Member, Index {
    }

    public record Program(List<Declaration> declarations, SourceSpan span) implements Node {
        public Program { declarations = List.copyOf(declarations); }
    }

    /** The type is null when the annotation is omitted. */
    public record Binding(Name name, Name type, SourceSpan span) implements Node {
    }

    public enum BindingKind { GLOBAL, CONSTANT, ANNOTATED, FIELD }

    public record BindingDeclaration(BindingKind kind, Binding binding, Expression initializer, SourceSpan span)
            implements Declaration, ClassMember, Statement {
    }

    public record Function(Name name, List<Binding> parameters, Block body, SourceSpan span)
            implements Declaration, ClassMember {
        public Function { parameters = List.copyOf(parameters); }
    }

    public record Constructor(Name name, List<Binding> parameters, Block body, SourceSpan span)
            implements ClassMember {
        public Constructor { parameters = List.copyOf(parameters); }
    }

    public record ClassDeclaration(Name name, List<ClassMember> members, SourceSpan span) implements Declaration {
        public ClassDeclaration { members = List.copyOf(members); }
    }

    public record EnumDeclaration(Name name, List<Name> members, SourceSpan span) implements Declaration {
        public EnumDeclaration { members = List.copyOf(members); }
    }

    public record EventDeclaration(List<Name> name, List<Expression> arguments, Block body, SourceSpan span)
            implements Declaration {
        public EventDeclaration {
            name = List.copyOf(name);
            arguments = List.copyOf(arguments);
        }
    }

    public record Block(List<Statement> statements, SourceSpan span) implements Node {
        public Block { statements = List.copyOf(statements); }
    }

    public record Assignment(List<Target> targets, AssignmentOperator operator, List<Expression> values,
                             SourceSpan span) implements Statement {
        public Assignment {
            targets = List.copyOf(targets);
            values = List.copyOf(values);
        }
    }

    public enum AssignmentOperator { ASSIGN, ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER }

    public record Update(Target target, UpdateOperator operator, SourceSpan span) implements Statement {
    }

    public enum UpdateOperator { INCREMENT, DECREMENT }

    public record CallStatement(Call call, SourceSpan span) implements Statement {
    }

    /** The fallback is null when there is no else block. */
    public record If(List<Branch> branches, Block fallback, SourceSpan span) implements Statement {
        public If { branches = List.copyOf(branches); }
    }

    public record Branch(Expression condition, Block body, SourceSpan span) implements Node {
    }

    public record While(Expression condition, Block body, SourceSpan span) implements Statement {
    }

    public record For(Name variable, Expression iterable, Block body, SourceSpan span) implements Statement {
    }

    public record Break(SourceSpan span) implements Statement {
    }

    public record Continue(SourceSpan span) implements Statement {
    }

    public record Return(List<Expression> values, SourceSpan span) implements Statement {
        public Return { values = List.copyOf(values); }
    }

    public record Name(String text, SourceSpan span) implements Target {
    }

    public record Self(SourceSpan span) implements Expression {
    }

    public record NumberLiteral(String spelling, SourceSpan span) implements Expression {
    }

    public record StringLiteral(String value, SourceSpan span) implements Expression {
    }

    public record BooleanLiteral(boolean value, SourceSpan span) implements Expression {
    }

    public record Nil(SourceSpan span) implements Expression {
    }

    public record Group(Expression expression, SourceSpan span) implements Expression {
    }

    public enum UnaryOperator { PLUS, MINUS, NOT }

    public record Unary(UnaryOperator operator, Expression operand, SourceSpan span) implements Expression {
    }

    public enum BinaryOperator {
        OR, AND, IS, IS_NOT, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
        ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER
    }

    public record Binary(Expression left, BinaryOperator operator, Expression right, SourceSpan span)
            implements Expression {
    }

    public record Call(Expression callee, List<Expression> arguments, SourceSpan span) implements Expression {
        public Call { arguments = List.copyOf(arguments); }
    }

    public record Member(Expression receiver, Name name, SourceSpan span) implements Target {
    }

    public record Index(Expression receiver, Expression index, SourceSpan span) implements Target {
    }

    public record ListLiteral(List<Expression> elements, SourceSpan span) implements Expression {
        public ListLiteral { elements = List.copyOf(elements); }
    }

    public record Tuple(List<Expression> elements, SourceSpan span) implements Expression {
        public Tuple { elements = List.copyOf(elements); }
    }

    public record Dictionary(List<DictionaryEntry> entries, SourceSpan span) implements Expression {
        public Dictionary { entries = List.copyOf(entries); }
    }

    public record DictionaryEntry(String key, SourceSpan keySpan, Expression value, SourceSpan span)
            implements Node {
    }
}
