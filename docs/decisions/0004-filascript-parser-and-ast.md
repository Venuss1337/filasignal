# ADR 0004 — FilaScript parser and AST

**Status:** Accepted for M02.

Use recursive descent with one expression routine per grammar precedence level. Loops consume left-associative and postfix chains, and comparison consumes at most one operator. This follows the EBNF directly and keeps the precedence difference between Boolean `not` and unary signs explicit.

The parser consumes the existing lexer tokens. A delimited expression, parameter list or enum body permits physical newlines. Statements preserve separators and the parser looks through newlines before `else` only when it finds `else`. Expression position selects a dictionary. Declaration and control-flow headers select a block.

Use immutable, source-spanned AST records behind sealed interfaces. Assignment stores its targets, operator and right-hand expression list. Grouping has its own node, so a later phase can distinguish `a, b = f()` from `a, b = (f())`. Object construction has ordinary call syntax. The parser checks syntactic target shape and constructor spelling. Binding lookup, result counts, annotation enforcement, constructor availability and event filter validation belong to analysis or execution.

The parser returns a partial tree with lexical and syntax diagnostics ordered by source offset. It resumes at item boundaries and skips balanced delimiters in abandoned items. When a delimiter is missing, line breaks and enclosing declaration or block boundaries provide recovery points. A 128-level parser recursion budget covers nested blocks, expressions and `not` operands. Flat chains use loops, and the debug printer uses an explicit stack. The budget protects the Java stack and can be revisited independently of the grammar.
