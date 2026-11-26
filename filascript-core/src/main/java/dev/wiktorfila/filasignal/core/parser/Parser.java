package dev.wiktorfila.filasignal.core.parser;

import dev.wiktorfila.filasignal.core.ast.Ast.*;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.lexer.LexResult;
import dev.wiktorfila.filasignal.core.lexer.Lexer;
import dev.wiktorfila.filasignal.core.lexer.Token;
import dev.wiktorfila.filasignal.core.lexer.TokenType;
import dev.wiktorfila.filasignal.core.source.SourceSpan;
import dev.wiktorfila.filasignal.core.source.SourceText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import static dev.wiktorfila.filasignal.core.lexer.TokenType.*;

/** Recursive descent, with one expression method per precedence level. */
public final class Parser {
    private static final int MAX_NESTING = 128;

    private final List<Token> tokens;
    private final List<Diagnostic> diagnostics;
    private int position;
    private int nesting;
    private boolean softNewlines;
    private Token previous;
    private int[] closingDelimiters;

    private Parser(LexResult lexed) {
        tokens = lexed.tokens();
        diagnostics = new ArrayList<>(lexed.diagnostics());
    }

    public static ParseResult parse(SourceText source) {
        return parse(Lexer.lex(source));
    }

    /** Parses a complete lexer result, including its EOF token and lexical diagnostics. */
    public static ParseResult parse(LexResult lexed) {
        SourceText source = lexed.tokens().getLast().span().source();
        Parser parser = new Parser(lexed);
        List<Declaration> declarations = parser.items(Scope.FILE, parser::declaration);
        parser.diagnostics.sort(Comparator.comparingInt(issue -> issue.span().startOffset()));
        return new ParseResult(new Program(declarations, new SourceSpan(source, 0, source.text().length())),
                parser.diagnostics);
    }

    private Declaration declaration() {
        return switch (type()) {
            case GLOBAL -> bindingDeclaration(advance(), BindingKind.GLOBAL, binding());
            case CONST -> bindingDeclaration(advance(), BindingKind.CONSTANT, binding());
            case FN -> function();
            case CLASS -> classDeclaration();
            case ENUM -> enumDeclaration();
            case ON -> eventDeclaration();
            default -> throw fail(Code.EXPECTED_DECLARATION, "Expected a top-level declaration");
        };
    }

    private BindingDeclaration bindingDeclaration(Token start, BindingKind kind, Binding binding) {
        expect(ASSIGN, "'=' before the initializer");
        skipNewlines();
        Expression initializer = expression();
        return new BindingDeclaration(kind, binding, initializer, through(start.span()));
    }

    private Binding binding() {
        Name name = name();
        Name annotation = match(COLON) ? name() : null;
        return new Binding(name, annotation, through(name.span()));
    }

    private Function function() {
        Token start = advance();
        Name name = name();
        List<Binding> parameters = delimited(LEFT_PAREN, RIGHT_PAREN, () -> commaList(RIGHT_PAREN, false, this::binding));
        Block body = block();
        return new Function(name, parameters, body, through(start.span()));
    }

    private ClassDeclaration classDeclaration() {
        Token start = advance();
        Name name = name();
        openBlock("'{' before class members");
        List<ClassMember> members = items(Scope.CLASS, () -> classMember(name));
        expect(RIGHT_BRACE, "'}' after class members");
        return new ClassDeclaration(name, members, through(start.span()));
    }

    private ClassMember classMember(Name className) {
        if (check(FN)) {
            return function();
        }
        Token start = peek();
        if (check(IDENTIFIER) && nextType() == LEFT_PAREN) {
            Name name = name();
            if (!className.text().equals(name.text())) {
                report(Code.INVALID_CONSTRUCTOR_NAME, "Constructor name must match the enclosing class name",
                        name.span());
            }
            List<Binding> parameters = delimited(LEFT_PAREN, RIGHT_PAREN,
                    () -> commaList(RIGHT_PAREN, false, this::binding));
            Block body = block();
            return new Constructor(name, parameters, body, through(start.span()));
        }
        return bindingDeclaration(start, BindingKind.FIELD, binding());
    }

    private EnumDeclaration enumDeclaration() {
        Token start = advance();
        Name name = name();
        List<Name> members = delimited(LEFT_BRACE, RIGHT_BRACE, () -> {
            if (check(RIGHT_BRACE)) {
                throw fail(Code.EXPECTED_TOKEN, "Expected at least one enum member");
            }
            return commaList(RIGHT_BRACE, true, this::name);
        });
        return new EnumDeclaration(name, members, through(start.span()));
    }

    private EventDeclaration eventDeclaration() {
        Token start = advance();
        List<Name> path = new ArrayList<>();
        path.add(name());
        while (match(DOT)) {
            path.add(name());
        }
        List<Expression> arguments = check(LEFT_PAREN) ? arguments() : List.of();
        Block body = block();
        return new EventDeclaration(path, arguments, body, through(start.span()));
    }

    private Block block() {
        return nested(() -> {
            Token start = openBlock("'{' before the block");
            List<Statement> statements = items(Scope.BLOCK, this::statement);
            expect(RIGHT_BRACE, "'}' after the block");
            return new Block(statements, through(start.span()));
        });
    }

    private Token openBlock(String description) {
        if (check(NEWLINE)) {
            int candidate = afterNewlines();
            if (tokens.get(candidate).type() == LEFT_BRACE) {
                SyntaxFailure failure = fail(Code.EXPECTED_TOKEN, "Expected " + description + " on the header line");
                position = candidate + 1;
                throw failure;
            }
        }
        return expect(LEFT_BRACE, description);
    }

    private Statement statement() {
        Token start = peek();
        return switch (type()) {
            case CONST -> bindingDeclaration(advance(), BindingKind.CONSTANT, binding());
            case IF -> conditional();
            case WHILE -> {
                advance();
                Expression condition = expression();
                Block body = block();
                yield new While(condition, body, through(start.span()));
            }
            case FOR -> {
                advance();
                Name variable = name();
                expect(IN, "'in' after the loop variable");
                Expression iterable = expression();
                Block body = block();
                yield new For(variable, iterable, body, through(start.span()));
            }
            case RETURN -> {
                advance();
                List<Expression> values = boundary(Scope.BLOCK) ? List.of() : expressionList();
                yield new Return(values, through(start.span()));
            }
            case BREAK -> new Break(advance().span());
            case CONTINUE -> new Continue(advance().span());
            default -> check(IDENTIFIER) && nextType() == COLON
                    ? bindingDeclaration(start, BindingKind.ANNOTATED, binding())
                    : assignmentOrCall();
        };
    }

    private If conditional() {
        Token start = advance();
        List<Branch> branches = new ArrayList<>();
        branches.add(branch(start));
        Block fallback = null;
        while (true) {
            int candidate = afterNewlines();
            if (tokens.get(candidate).type() != ELSE) {
                break;
            }
            position = candidate;
            Token alternative = advance();
            if (match(IF)) {
                branches.add(branch(alternative));
            } else {
                fallback = block();
                break;
            }
        }
        return new If(branches, fallback, through(start.span()));
    }

    private Branch branch(Token start) {
        Expression condition = expression();
        Block body = block();
        return new Branch(condition, body, through(start.span()));
    }

    private Statement assignmentOrCall() {
        Expression first = expression();
        List<Expression> left = check(COMMA) ? restOfExpressionList(first) : List.of(first);
        if (match(ASSIGN)) {
            List<Target> targets = left.stream().map(this::target).toList();
            skipNewlines();
            List<Expression> values = expressionList();
            return new Assignment(targets, AssignmentOperator.ASSIGN, values, through(first.span()));
        }
        AssignmentOperator operator = switch (type()) {
            case PLUS_ASSIGN -> AssignmentOperator.ADD;
            case MINUS_ASSIGN -> AssignmentOperator.SUBTRACT;
            case STAR_ASSIGN -> AssignmentOperator.MULTIPLY;
            case SLASH_ASSIGN -> AssignmentOperator.DIVIDE;
            case PERCENT_ASSIGN -> AssignmentOperator.REMAINDER;
            default -> null;
        };
        if (operator != null || check(INCREMENT) || check(DECREMENT)) {
            if (left.size() != 1) {
                throw fail(Code.INVALID_ASSIGNMENT_ARITY, "Expected one target for this update");
            }
            Target target = target(first);
            Token operation = advance();
            if (operator != null) {
                skipNewlines();
                Expression value = expression();
                if (check(COMMA)) {
                    throw fail(Code.INVALID_ASSIGNMENT_ARITY, "Expected one value for compound assignment");
                }
                return new Assignment(List.of(target), operator, List.of(value), through(first.span()));
            }
            UpdateOperator update = operation.type() == INCREMENT ? UpdateOperator.INCREMENT : UpdateOperator.DECREMENT;
            return new Update(target, update, through(first.span()));
        }
        if (left.size() == 1 && first instanceof Call call) {
            return new CallStatement(call, call.span());
        }
        throw fail(Code.EXPECTED_CALL_STATEMENT, "Expected an assignment, update, or call statement", through(first.span()));
    }

    private Target target(Expression expression) {
        if (expression instanceof Target target) {
            return target;
        }
        throw fail(Code.INVALID_ASSIGNMENT_TARGET, "Expected a name, member, or indexed assignment target",
                expression.span());
    }

    private List<Expression> expressionList() {
        return restOfExpressionList(expression());
    }

    private List<Expression> restOfExpressionList(Expression first) {
        List<Expression> values = new ArrayList<>();
        values.add(first);
        while (match(COMMA)) {
            skipNewlines();
            values.add(expression());
        }
        return values;
    }

    private Expression expression() {
        return nested(this::or);
    }

    private Expression or() {
        Expression left = and();
        while (match(OR)) {
            skipNewlines();
            left = binary(left, BinaryOperator.OR, and());
        }
        return left;
    }

    private Expression and() {
        Expression left = not();
        while (match(AND)) {
            skipNewlines();
            left = binary(left, BinaryOperator.AND, not());
        }
        return left;
    }

    private Expression not() {
        if (!match(NOT)) {
            return comparison();
        }
        Token start = previous;
        skipNewlines();
        Expression operand = nested(this::not);
        return new Unary(UnaryOperator.NOT, operand, through(start.span()));
    }

    private Expression comparison() {
        Expression left = sum();
        if (!isComparison(type())) {
            return left;
        }
        TokenType operator = advance().type();
        skipNewlines();
        BinaryOperator kind = switch (operator) {
            case IS -> {
                if (match(NOT)) {
                    skipNewlines();
                    yield BinaryOperator.IS_NOT;
                }
                yield BinaryOperator.IS;
            }
            case LESS -> BinaryOperator.LESS;
            case LESS_EQUAL -> BinaryOperator.LESS_EQUAL;
            case GREATER -> BinaryOperator.GREATER;
            case GREATER_EQUAL -> BinaryOperator.GREATER_EQUAL;
            default -> throw new AssertionError(operator);
        };
        Expression right = sum();
        if (isComparison(type())) {
            throw fail(Code.CHAINED_COMPARISON, "Comparisons require parentheses or a logical operator between them");
        }
        return binary(left, kind, right);
    }

    private static boolean isComparison(TokenType type) {
        return switch (type) {
            case IS, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> true;
            default -> false;
        };
    }

    private Expression sum() {
        Expression left = product();
        while (check(PLUS) || check(MINUS)) {
            BinaryOperator operator = advance().type() == PLUS ? BinaryOperator.ADD : BinaryOperator.SUBTRACT;
            skipNewlines();
            left = binary(left, operator, product());
        }
        return left;
    }

    private Expression product() {
        Expression left = unary();
        while (check(STAR) || check(SLASH) || check(PERCENT)) {
            BinaryOperator operator = switch (advance().type()) {
                case STAR -> BinaryOperator.MULTIPLY;
                case SLASH -> BinaryOperator.DIVIDE;
                default -> BinaryOperator.REMAINDER;
            };
            skipNewlines();
            left = binary(left, operator, unary());
        }
        return left;
    }

    private Expression unary() {
        if (!(check(PLUS) || check(MINUS))) {
            return postfix();
        }
        Token start = advance();
        UnaryOperator operator = start.type() == PLUS ? UnaryOperator.PLUS : UnaryOperator.MINUS;
        skipNewlines();
        Expression operand = postfix();
        return new Unary(operator, operand, through(start.span()));
    }

    private Expression postfix() {
        Expression expression = atom();
        while (true) {
            SourceSpan start = expression.span();
            if (match(DOT)) {
                Name member = name();
                expression = new Member(expression, member, through(start));
            } else if (check(LEFT_BRACKET)) {
                Expression index = delimited(LEFT_BRACKET, RIGHT_BRACKET, this::expression);
                expression = new Index(expression, index, through(start));
            } else if (check(LEFT_PAREN)) {
                List<Expression> arguments = arguments();
                expression = new Call(expression, arguments, through(start));
            } else {
                return expression;
            }
        }
    }

    private List<Expression> arguments() {
        return delimited(LEFT_PAREN, RIGHT_PAREN, () -> commaList(RIGHT_PAREN, false, this::expression));
    }

    private Expression atom() {
        Token token = peek();
        return switch (type()) {
            case IDENTIFIER -> name();
            case NUMBER -> new NumberLiteral(advance().lexeme(), token.span());
            case STRING -> new StringLiteral(advance().stringValue(), token.span());
            case TRUE, FALSE -> new BooleanLiteral(advance().type() == TRUE, token.span());
            case NIL -> new Nil(advance().span());
            case SELF -> new Self(advance().span());
            case LEFT_PAREN -> delimited(LEFT_PAREN, RIGHT_PAREN, () -> parenthesized(token));
            case LEFT_BRACKET -> {
                List<Expression> elements = delimited(LEFT_BRACKET, RIGHT_BRACKET,
                        () -> commaList(RIGHT_BRACKET, true, this::expression));
                yield new ListLiteral(elements, through(token.span()));
            }
            case LEFT_BRACE -> {
                List<DictionaryEntry> entries = delimited(LEFT_BRACE, RIGHT_BRACE,
                        () -> commaList(RIGHT_BRACE, true, this::dictionaryEntry));
                yield new Dictionary(entries, through(token.span()));
            }
            case INCREMENT, DECREMENT -> throw fail(Code.UNSUPPORTED_UPDATE_POSITION,
                    "Increment and decrement are postfix statements");
            default -> throw fail(Code.EXPECTED_EXPRESSION, "Expected an expression");
        };
    }

    // The closer is still at the cursor, so this span includes it even before delimited consumes it.
    private Expression parenthesized(Token start) {
        if (check(RIGHT_PAREN)) {
            return new Tuple(List.of(), cover(start.span(), peek().span()));
        }
        Expression first = expression();
        if (!match(COMMA)) {
            expectClosingParen();
            return new Group(first, cover(start.span(), peek().span()));
        }
        List<Expression> elements = new ArrayList<>();
        elements.add(first);
        elements.addAll(commaList(RIGHT_PAREN, true, this::expression));
        expectClosingParen();
        return new Tuple(elements, cover(start.span(), peek().span()));
    }

    private void expectClosingParen() {
        if (!check(RIGHT_PAREN)) {
            throw fail(Code.EXPECTED_TOKEN, "Expected ')'");
        }
    }

    private DictionaryEntry dictionaryEntry() {
        Token key = peek();
        if (!(match(IDENTIFIER) || match(STRING))) {
            throw fail(Code.EXPECTED_TOKEN, "Expected an identifier or quoted dictionary key");
        }
        expect(COLON, "':' after the dictionary key");
        Expression value = expression();
        String text = key.type() == STRING ? key.stringValue() : key.lexeme();
        return new DictionaryEntry(text, key.span(), value, through(key.span()));
    }

    private <T> List<T> commaList(TokenType close, boolean trailingComma, Supplier<T> item) {
        List<T> result = new ArrayList<>();
        if (!check(close)) {
            do {
                result.add(item.get());
            } while (match(COMMA) && !(trailingComma && check(close)));
        }
        return result;
    }

    private <T> T delimited(TokenType open, TokenType close, Supplier<T> content) {
        expect(open, delimiter(open));
        boolean outer = softNewlines;
        softNewlines = true;
        T result;
        try {
            skipNewlines();
            result = content.get();
        } finally {
            softNewlines = outer;
        }
        expect(close, delimiter(close));
        return result;
    }

    private static String delimiter(TokenType type) {
        return switch (type) {
            case LEFT_PAREN -> "'('";
            case RIGHT_PAREN -> "')'";
            case LEFT_BRACKET -> "'['";
            case RIGHT_BRACKET -> "']'";
            case LEFT_BRACE -> "'{'";
            case RIGHT_BRACE -> "'}'";
            default -> throw new AssertionError(type);
        };
    }

    private <T> T nested(Supplier<T> action) {
        if (nesting >= MAX_NESTING) {
            throw fail(Code.NESTING_LIMIT, "Syntax nesting exceeds " + MAX_NESTING + " levels");
        }
        nesting++;
        try {
            return action.get();
        } finally {
            nesting--;
        }
    }

    private enum Scope { FILE, CLASS, BLOCK }

    private <T> List<T> items(Scope scope, Supplier<T> parseItem) {
        List<T> result = new ArrayList<>();
        skipSeparators();
        while (!endOfItems(scope)) {
            int start = position;
            try {
                T item = parseItem.get();
                if (!boundary(scope)) {
                    if (check(INCREMENT) || check(DECREMENT)) {
                        throw fail(Code.UNSUPPORTED_UPDATE_POSITION, "Increment and decrement are standalone postfix statements");
                    }
                    throw fail(Code.EXPECTED_SEPARATOR, "Expected a newline or ';' between items");
                }
                result.add(item);
            } catch (SyntaxFailure ignored) {
                recover(start, scope);
            }
            skipSeparators();
        }
        return result;
    }

    /** Recover at an item boundary, skipping balanced constructs of the abandoned item. */
    private void recover(int start, Scope scope) {
        if (closingDelimiters == null) {
            closingDelimiters = matchDelimiters();
        }
        int braces = 0;
        int failure = position;
        for (position = start; !check(EOF); position++) {
            if (position >= failure && position > start) {
                boolean startsLine = tokens.get(position - 1).type() == NEWLINE;
                if (braces == 0 && (boundary(scope) || endOfItems(scope) || startsLine)) {
                    return;
                }
                if (startsLine && isTopLevelAnchor(type()) && (scope != Scope.CLASS || !check(FN))) {
                    return;
                }
            }
            int close = closingDelimiters[position];
            if (close != 0) {
                position = close;
            } else {
                braces = braceDepth(braces, type());
            }
        }
    }

    private int[] matchDelimiters() {
        int[] closes = new int[tokens.size()];
        int[] pending = new int[tokens.size()];
        int size = 0;
        for (int i = 0; i < tokens.size(); i++) {
            switch (tokens.get(i).type()) {
                case LEFT_PAREN, LEFT_BRACKET, LEFT_BRACE -> pending[size++] = i;
                case RIGHT_PAREN, RIGHT_BRACKET, RIGHT_BRACE -> {
                    if (size > 0 && matchingClose(tokens.get(pending[size - 1]).type()) == tokens.get(i).type()) {
                        closes[pending[--size]] = i;
                    } else {
                        size = 0;
                    }
                }
                default -> { }
            }
        }
        return closes;
    }

    private static TokenType matchingClose(TokenType opener) {
        return switch (opener) {
            case LEFT_PAREN -> RIGHT_PAREN;
            case LEFT_BRACKET -> RIGHT_BRACKET;
            case LEFT_BRACE -> RIGHT_BRACE;
            default -> throw new AssertionError(opener);
        };
    }

    private static int braceDepth(int depth, TokenType type) {
        return switch (type) {
            case LEFT_BRACE -> depth + 1;
            case RIGHT_BRACE -> Math.max(0, depth - 1);
            default -> depth;
        };
    }

    private boolean endOfItems(Scope scope) {
        return check(EOF) || (scope != Scope.FILE && (check(RIGHT_BRACE)
                || (isTopLevelAnchor(type()) && (scope == Scope.BLOCK || !check(FN)))));
    }

    private static boolean isTopLevelAnchor(TokenType type) {
        return switch (type) {
            case GLOBAL, FN, CLASS, ENUM, ON -> true;
            default -> false;
        };
    }

    private boolean boundary(Scope scope) {
        return check(NEWLINE) || check(SEMICOLON) || check(EOF) || (scope != Scope.FILE && check(RIGHT_BRACE));
    }

    private void skipSeparators() {
        while (check(NEWLINE) || check(SEMICOLON)) {
            position++;
        }
    }

    private void skipNewlines() {
        position = afterNewlines();
    }

    private int afterNewlines() {
        int candidate = position;
        while (tokens.get(candidate).type() == NEWLINE) {
            candidate++;
        }
        return candidate;
    }

    private Name name() {
        Token token = expect(IDENTIFIER, "an identifier");
        return new Name(token.lexeme(), token.span());
    }

    private Token peek() {
        return tokens.get(position);
    }

    private TokenType type() {
        return peek().type();
    }

    private TokenType nextType() {
        return tokens.get(position + 1).type();
    }

    private boolean check(TokenType type) {
        return type() == type;
    }

    private Token advance() {
        previous = tokens.get(position++);
        if (softNewlines) {
            skipNewlines();
        }
        return previous;
    }

    private boolean match(TokenType type) {
        if (!check(type)) {
            return false;
        }
        advance();
        return true;
    }

    private Token expect(TokenType type, String description) {
        if (!check(type)) {
            throw fail(Code.EXPECTED_TOKEN, "Expected " + description);
        }
        return advance();
    }

    private SourceSpan through(SourceSpan start) {
        return cover(start, previous.span());
    }

    private static SourceSpan cover(SourceSpan start, SourceSpan end) {
        return new SourceSpan(start.source(), start.startOffset(), end.endOffset());
    }

    private static Binary binary(Expression left, BinaryOperator operator, Expression right) {
        return new Binary(left, operator, right, cover(left.span(), right.span()));
    }

    private SyntaxFailure fail(Code code, String message) {
        // The lexer has already diagnosed an ERROR token.
        if (!check(ERROR)) {
            report(code, message, peek().span());
        }
        return SyntaxFailure.INSTANCE;
    }

    private SyntaxFailure fail(Code code, String message, SourceSpan span) {
        report(code, message, span);
        return SyntaxFailure.INSTANCE;
    }

    private void report(Code code, String message, SourceSpan span) {
        diagnostics.add(new Diagnostic(code, message, span));
    }

    private static final class SyntaxFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private static final SyntaxFailure INSTANCE = new SyntaxFailure();

        private SyntaxFailure() {
            super(null, null, false, false);
        }
    }
}
