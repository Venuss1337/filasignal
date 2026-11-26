package dev.wiktorfila.filasignal.cli;

import dev.wiktorfila.filasignal.core.analysis.AnalysisResult;
import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.ast.AstPrinter;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.parser.ParseResult;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionLimits;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionResult;
import dev.wiktorfila.filasignal.core.runtime.execution.Interpreter;
import dev.wiktorfila.filasignal.core.runtime.execution.RuntimeIssue;
import dev.wiktorfila.filasignal.core.source.SourcePosition;
import dev.wiktorfila.filasignal.core.source.SourceSpan;
import dev.wiktorfila.filasignal.core.source.SourceText;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

public final class FilaScriptCli {
    private static final String USAGE = "Usage: filascript-cli (run|ast) <file.fsig>";

    private FilaScriptCli() {
    }

    /** The release version from the JAR manifest, or "development" when run from compiled classes. */
    static String version() {
        String version = FilaScriptCli.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && (args[0].equals("--version") || args[0].equals("--help")))) {
            System.out.println("FilaScript " + version());
            System.out.println(USAGE);
            return;
        }
        if (args.length != 2 || !(args[0].equals("ast") || args[0].equals("run"))) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        int status = process(args[0], args[1]);
        if (status != 0) {
            System.exit(status);
        }
    }

    private static int process(String command, String filename) {
        PrintWriter errors = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        try {
            ParseResult parsed = Parser.parse(SourceText.read(Path.of(filename)));
            if (!parsed.diagnostics().isEmpty()) {
                parsed.diagnostics().forEach(issue -> printDiagnostic(errors, issue));
                return 1;
            }
            if (command.equals("ast")) {
                output.print(AstPrinter.print(parsed.program()));
                output.flush();
                return 0;
            }

            AnalysisResult analyzed = Resolver.resolve(parsed);
            if (!analyzed.diagnostics().isEmpty()) {
                analyzed.diagnostics().forEach(issue -> printDiagnostic(errors, issue));
                return 1;
            }
            Interpreter interpreter = new Interpreter(analyzed.program().orElseThrow(), ExecutionLimits.DEFAULT,
                    output::println);
            ExecutionResult result = interpreter.invoke("main", List.of());
            if (result.issue().isPresent()) {
                RuntimeIssue issue = result.issue().orElseThrow();
                printDiagnostic(errors, issue.diagnostic());
                for (RuntimeIssue.Frame frame : issue.frames()) {
                    errors.printf("  at %s (%s)%n", frame.function(), location(frame.callSite()));
                }
                return 1;
            }
            return 0;
        } catch (IOException | InvalidPathException exception) {
            errors.println(exception.getMessage());
            return 1;
        }
    }

    private static void printDiagnostic(PrintWriter errors, Diagnostic diagnostic) {
        errors.printf("%s: %s: %s%n", location(diagnostic.span()), diagnostic.code(), diagnostic.message());
    }

    private static String location(SourceSpan span) {
        SourcePosition start = span.start();
        return "%s:%d:%d".formatted(span.source().filename(), start.line(), start.column());
    }
}
