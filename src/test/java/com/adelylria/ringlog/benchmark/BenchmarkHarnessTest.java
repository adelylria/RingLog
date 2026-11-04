package com.adelylria.ringlog.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.adelylria.ringlog.storage.AppPaths;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;

/** Verifies the diagnostic reports and sample accounting; it contains no timing thresholds. */
public final class BenchmarkHarnessTest {
    private BenchmarkHarnessTest() { }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("ringlog-benchmark-harness-").toAbsolutePath().normalize();
        try {
            BenchmarkContext context = new BenchmarkContext(AppPaths.forDataRoot(work.resolve("data")),
                    work, 100, 2, 3, 10, 1, 1, List.of());
            AtomicInteger calls = new AtomicInteger();
            context.measure("test", "warmup-accounting", () -> calls.incrementAndGet());
            require(calls.get() == 5, "Warmups and measured calls must both run");
            context.recordNanos("test", "known-distribution", List.of(1_000_000L, 2_000_000L,
                    3_000_000L, 4_000_000L, 100_000_000L), 42);
            context.skip("test", "unavailable-feature", "Synthetic skip");
            require(!context.hasFailures(), "Skipped cases must not masquerade as failures");
            context.measure("test", "correctness-failure", 0, 1,
                    () -> { throw new AssertionError("Synthetic correctness failure"); });
            require(context.hasFailures(), "A failed correctness check must fail the benchmark");
            Path report = work.resolve("results.json");
            context.writeReport(report, "test", "quick", "test-revision", "test-start", 12.5, null);
            List<String> statuses = new ArrayList<>();
            boolean knownMedian = false;
            boolean knownP95 = false;
            boolean rawSamples = false;
            String currentCase = null;
            try (var parser = new JsonFactory().createParser(report.toFile())) {
                while (parser.nextToken() != null) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) continue;
                    String field = parser.currentName();
                    parser.nextToken();
                    if ("name".equals(field)) currentCase = parser.getText();
                    if ("status".equals(field)) statuses.add(parser.getText());
                    if ("known-distribution".equals(currentCase) && "medianMs".equals(field)) knownMedian = parser.getDoubleValue() == 3.0;
                    if ("known-distribution".equals(currentCase) && "p95Ms".equals(field)) knownP95 = parser.getDoubleValue() == 100.0;
                    if ("known-distribution".equals(currentCase) && "rawMs".equals(field)) {
                        int values = 0;
                        while (parser.nextToken() != JsonToken.END_ARRAY) values++;
                        rawSamples = values == 5;
                    }
                }
            }
            require(knownMedian && knownP95 && rawSamples, "Reports must retain exact samples and correct quantiles");
            require(statuses.equals(List.of("PASS", "PASS", "SKIPPED", "FAILED")), "Reports must distinguish success, skip and failure");
            require(Files.readString(work.resolve("results.csv")).contains("known-distribution"), "CSV report missing cases");
            Files.createDirectories(work.resolve("existing/synthetic-data"));
            boolean refused = false;
            try { BenchmarkFixture.create(work.resolve("existing"), 100, 10, 0, 1, 10); }
            catch (IllegalArgumentException expected) { refused = true; }
            require(refused, "A benchmark must never reuse an existing data directory");
            System.out.println("BenchmarkHarnessTest: PASS");
        } finally {
            Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            require(work.startsWith(temporary) && !work.equals(temporary)
                    && work.getFileName().toString().startsWith("ringlog-benchmark-harness-"), "Unsafe cleanup target");
            try (var walk = Files.walk(work)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
