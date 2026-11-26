package dev.wiktorfila.filasignal.core;

import dev.wiktorfila.filasignal.core.analysis.AnalysisResult;
import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionLimits;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionResult;
import dev.wiktorfila.filasignal.core.runtime.execution.Interpreter;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every FilaScript example in the language documentation is a real program. Each {@code fsig} block in
 * {@code web/src/content/docs/language} must parse and resolve with the standalone built-ins. A block with
 * {@code fn main()} also runs as the command-line tool runs it, and when the next block is a
 * {@code text title="Output"} block, the printed lines must match it exactly.
 *
 * <p>Options on the fence: {@code check="none"} skips a fragment, {@code check="invalid"} requires a diagnostic and
 * {@code check="fault"} requires a runtime fault.
 */
class DocumentationExamplesTest {
    private static final Pattern FENCE = Pattern.compile("(?m)^([ \\t]*)```(\\w+)([^\\n]*)\\n(.*?)^[ \\t]*```[ \\t]*$", Pattern.DOTALL);
    private static final Pattern OPTION = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private record Block(String language, String options, String code, int line) {
        String option(String name) {
            Matcher matcher = OPTION.matcher(options);
            while (matcher.find()) {
                if (matcher.group(1).equals(name)) {
                    return matcher.group(2);
                }
            }
            return "";
        }
    }

    @Test
    void languageGuideExamplesRunAsDocumented() throws IOException {
        Path docs = repository().resolve("web/src/content/docs/language");
        var problems = new ArrayList<String>();
        int checked = 0;
        List<Path> pages;
        try (var files = Files.walk(docs)) {
            pages = files.filter(path -> path.toString().matches(".*\\.mdx?$")).sorted().toList();
        }
        for (Path page : pages) {
            List<Block> blocks = blocks(Files.readString(page, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            for (int i = 0; i < blocks.size(); i++) {
                Block block = blocks.get(i);
                if (!block.language().equals("fsig") || block.option("check").equals("none")) {
                    continue;
                }
                checked++;
                Block next = i + 1 < blocks.size() ? blocks.get(i + 1) : null;
                String expected = next != null && next.language().equals("text") && next.option("title").equals("Output")
                        ? next.code() : null;
                String problem = check(docs.relativize(page) + ":" + block.line(), block, expected);
                if (problem != null) {
                    problems.add(problem);
                }
            }
        }
        assertTrue(problems.isEmpty(), "Documentation examples failed:\n" + String.join("\n", problems));
        assertTrue(checked >= 30, "Expected the language guide to contain examples, found " + checked);
    }

    private static String check(String where, Block block, String expected) {
        AnalysisResult analyzed = Resolver.resolve(Parser.parse(new SourceText(where, "example.fsig", block.code())));
        boolean invalid = block.option("check").equals("invalid");
        if (invalid) {
            return analyzed.diagnostics().isEmpty() ? where + ": expected a diagnostic" : null;
        }
        if (!analyzed.diagnostics().isEmpty()) {
            var first = analyzed.diagnostics().getFirst();
            return where + ": " + first.code() + " " + first.message() + " at " + first.span().start();
        }
        var program = analyzed.program().orElseThrow();
        if (program.topLevel("main") == null) {
            return expected == null ? null : where + ": has an Output block but no fn main()";
        }
        var output = new ArrayList<String>();
        ExecutionResult result = new Interpreter(program, ExecutionLimits.DEFAULT, output::add).invoke("main", List.of());
        boolean fault = block.option("check").equals("fault");
        if (result.issue().isPresent() != fault) {
            return where + ": " + (fault ? "expected a runtime fault" : "runtime fault "
                    + result.issue().orElseThrow().diagnostic().code() + " " + result.issue().orElseThrow().diagnostic().message());
        }
        if (expected != null && !String.join("\n", output).equals(expected.stripTrailing())) {
            return where + ": printed\n" + String.join("\n", output) + "\nbut the page shows\n" + expected.stripTrailing();
        }
        return null;
    }

    private static List<Block> blocks(String text) {
        var blocks = new ArrayList<Block>();
        Matcher fence = FENCE.matcher(text);
        while (fence.find()) {
            String indent = fence.group(1);
            String code = fence.group(4).replaceAll("(?m)^" + Pattern.quote(indent), "");
            int line = text.substring(0, fence.start()).split("\n", -1).length;
            blocks.add(new Block(fence.group(2), fence.group(3), code, line));
        }
        return blocks;
    }

    private static Path repository() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("web/src/content/docs"))) {
                return dir;
            }
        }
        throw new IllegalStateException("The documentation site was not found above " + Path.of("").toAbsolutePath());
    }
}
