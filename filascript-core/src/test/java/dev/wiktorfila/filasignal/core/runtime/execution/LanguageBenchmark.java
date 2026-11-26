package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interpreter throughput with the default depth and data limits, outside Minecraft. Excluded from {@code test}; run with
 * {@code gradlew :filascript-core:languageBenchmark} or {@code tools/benchmarks/Run-Benchmarks.ps1}. Results go to
 * {@code -Dfilasignal.benchmark.out} as language-results.json and language-results.md.
 */
@Tag("benchmark")
class LanguageBenchmark {
    private record Workload(String name, String source, String function) {
    }

    private static final List<Workload> WORKLOADS = List.of(
            new Workload("arithmetic loop", """
                    fn run() {
                        total = 0
                        i = 0
                        while i < 5000 {
                            total = total + i * 3 % 7
                            i++
                        }
                        return total
                    }
                    """, "run"),
            new Workload("function calls", """
                    fn add(a, b) { return a + b }
                    fn run() {
                        total = 0
                        i = 0
                        while i < 2000 {
                            total = add(total, i)
                            i++
                        }
                        return total
                    }
                    """, "run"),
            new Workload("recursion (fib 15)", """
                    fn fib(n) {
                        if n < 2 { return n }
                        return fib(n - 1) + fib(n - 2)
                    }
                    fn run() { return fib(15) }
                    """, "run"),
            new Workload("string building", """
                    fn run() {
                        text = ""
                        i = 0
                        while i < 300 {
                            text = text + str(i % 10)
                            i++
                        }
                        return text
                    }
                    """, "run"),
            new Workload("list operations", """
                    fn run() {
                        items = []
                        i = 0
                        while i < 1000 {
                            items.append(i)
                            i++
                        }
                        total = 0
                        for item in items {
                            total = total + item
                        }
                        return total
                    }
                    """, "run"),
            new Workload("maps and objects", """
                    class Point {
                        x = 0
                        y = 0
                    }
                    fn run() {
                        counts = {}
                        i = 0
                        while i < 500 {
                            p = Point()
                            p.x = i
                            key = str(i % 16)
                            counts[key] = p.x
                            i++
                        }
                        return counts["0"]
                    }
                    """, "run"));

    /** Default depth and data limits; a larger operation budget so each workload completes in one invocation. */
    private static final ExecutionLimits THROUGHPUT = new ExecutionLimits(64, 10_000_000);

    @Test
    void measureInterpreterThroughput() throws IOException {
        int iterations = Integer.getInteger("filasignal.benchmark.iterations", 200);
        Path out = Path.of(System.getProperty("filasignal.benchmark.out", "build/benchmark")).toAbsolutePath();
        var rows = new ArrayList<String>();
        var json = new ArrayList<String>();
        rows.add("| Workload | Operations | Mean | Median | P95 | Max | ns/operation |");
        rows.add("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");

        // Parsing and resolving a large program (every workload repeated with renamed functions).
        var big = new StringBuilder();
        for (int copy = 0; copy < 40; copy++) {
            for (Workload workload : WORKLOADS) {
                big.append(workload.source().replaceAll("\\b(run|add|fib|Point)\\b", "$1_" + copy)).append('\n');
            }
        }
        String bigSource = big.toString();
        double[] compile = time(iterations / 4 + 5, () -> Resolver.resolve(Parser.parse(new SourceText("big", "big.fsig", bigSource))));
        rows.add(String.format(Locale.ROOT, "| parse and resolve %d lines | - | %.3f ms | %.3f | %.3f | %.3f | - |",
                bigSource.lines().count(), mean(compile), pct(compile, 50), pct(compile, 95), compile[compile.length - 1]));
        json.add(String.format(Locale.ROOT, "{\"name\": \"parse and resolve\", \"lines\": %d, \"meanMs\": %.4f, \"medianMs\": %.4f, \"p95Ms\": %.4f}",
                bigSource.lines().count(), mean(compile), pct(compile, 50), pct(compile, 95)));

        for (Workload workload : WORKLOADS) {
            var compiled = Resolver.resolve(Parser.parse(new SourceText(workload.name(), "bench.fsig", workload.source())));
            assertTrue(compiled.diagnostics().isEmpty(), compiled.diagnostics()::toString);
            var interpreter = new Interpreter(compiled.program().orElseThrow(), THROUGHPUT, line -> { });
            long[] operations = new long[1];
            double[] times = time(iterations, () -> {
                var result = interpreter.invoke(workload.function(), List.of());
                assertTrue(result.issue().isEmpty(), () -> workload.name() + ": " + result.issue().orElseThrow());
                operations[0] = result.operationsUsed();
            });
            double nsPerOp = mean(times) * 1e6 / Math.max(1, operations[0]);
            rows.add(String.format(Locale.ROOT, "| %s | %d | %.3f ms | %.3f | %.3f | %.3f | %.1f |", workload.name(), operations[0],
                    mean(times), pct(times, 50), pct(times, 95), times[times.length - 1], nsPerOp));
            json.add(String.format(Locale.ROOT, "{\"name\": \"%s\", \"operations\": %d, \"meanMs\": %.4f, \"medianMs\": %.4f, \"p95Ms\": %.4f, \"maxMs\": %.4f, \"nsPerOperation\": %.2f}",
                    workload.name(), operations[0], mean(times), pct(times, 50), pct(times, 95), times[times.length - 1], nsPerOp));
        }
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Files.createDirectories(out);
        Files.writeString(out.resolve("language-results.md"), String.join("\n", rows)
                + String.format(Locale.ROOT, "%n%nIterations: %d (after %d warm-up iterations). Heap used at the end: %d MB of %d MB.%n",
                iterations, iterations / 2, heap.getUsed() >> 20, heap.getMax() >> 20), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("language-results.json"), "{\n  \"iterations\": " + iterations + ",\n  \"workloads\": [\n    "
                + String.join(",\n    ", json) + "\n  ]\n}\n", StandardCharsets.UTF_8);
    }

    /** Warms up with half as many runs, then returns sorted wall times in milliseconds. */
    private static double[] time(int iterations, Runnable action) {
        for (int i = 0; i < iterations / 2; i++) {
            action.run();
        }
        double[] times = new double[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            action.run();
            times[i] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(times);
        return times;
    }

    private static double mean(double[] values) {
        return Arrays.stream(values).average().orElse(0);
    }

    private static double pct(double[] sorted, double p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
