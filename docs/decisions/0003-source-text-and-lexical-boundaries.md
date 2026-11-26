# ADR 0003 — Source text and lexical boundaries

**Status:** Accepted for FilaScript 0.1.

Use strict UTF-8, remove one leading BOM, and preserve LF and CRLF in decoded text. Token and diagnostic spans use half-open UTF-16 offsets for direct Java slicing. Human-facing locations use one-based Unicode code point columns. Malformed UTF-8 reports the byte offset in the original input because decoded source is unavailable.

Emit every physical LF or CRLF as a token, including line breaks inside comments. This lets the parser decide which breaks separate statements and which continue an expression. In particular, braces can open either a statement block or a dictionary. Semicolons remain explicit, and a newline before `else` is allowed without permitting a semicolon there.

Use ASCII identifiers, decimal numbers with optional fraction and exponent, and double-quoted strings with a small escape set. Braced Unicode escapes express one scalar directly and avoid exposing Java surrogate pairs to scripts.

See [source and newline rules](../language/SPEC-0.1-DRAFT.md#2-source-model) for the complete contract.
