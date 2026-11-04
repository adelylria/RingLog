package com.adelylria.ringlog.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One isolated JVM per group and archive size. Run through scripts/RunBenchmarks.ps1. */
public final class RingLogBenchmark {
    private RingLogBenchmark() { }

    public static void main(String[] args) {
        int exit = 1;
        BenchmarkContext context = null;
        String fatal = null;
        String started = Instant.now().toString();
        Path output = null;
        String group = "db";
        String profile = "quick";
        double fixtureMillis = 0;
        String revision = System.getProperty("ringlog.benchmark.revision", "working-tree-unspecified");
        try {
            if (Runtime.version().feature() != 17) throw new IllegalArgumentException("Use RingLog's Java 17 runtime");
            Map<String, String> options = parse(args);
            group = options.getOrDefault("group", "db");
            profile = options.getOrDefault("profile", "quick");
            if (!java.util.Set.of("db", "ui", "storage", "portable", "desktop").contains(group)) throw new IllegalArgumentException("Unknown group");
            int rows = integer(options, "rows", 1000, 100, 100000);
            int history = integer(options, "history-length", 10, 1, 1000);
            System.setProperty("ringlog.benchmark.historyLength", Integer.toString(history));
            int warmups = integer(options, "warmups", 2, 0, 20);
            int samples = integer(options, "samples", 5, 1, 50);
            int limit = integer(options, "report-limit", 250, 1, 5000);
            int timeoutSeconds = integer(options, "timeout-seconds", 1200, 60, 14400);
            startWatchdog(timeoutSeconds);
            output = Path.of(options.getOrDefault("output", "target/benchmarks/" + group + '-' + System.currentTimeMillis()))
                    .toAbsolutePath().normalize();
            Files.createDirectories(output);
            Path work = Files.createDirectories(output.resolve("work"));
            Files.createDirectories(work.resolve("tmp"));
            System.setProperty("java.io.tmpdir", work.resolve("tmp").toString());
            System.setProperty("ringlog.test.mode", "true");
            System.out.println("BENCH START group=" + group + " rows=" + rows + " profile=" + profile);
            long fixtureStart = System.nanoTime();
            context = BenchmarkFixture.create(work, rows, history, warmups, samples, limit);
            fixtureMillis = (System.nanoTime() - fixtureStart) / 1_000_000.0;
            switch (group) {
                case "db" -> DatabaseBenchmarks.run(context);
                case "ui" -> UiBenchmarks.run(context);
                case "storage" -> StorageBenchmarks.run(context);
                case "portable" -> PortableBenchmarks.run(context);
                case "desktop" -> DesktopBenchmarks.run(context);
                default -> throw new IllegalArgumentException("Unknown group");
            }
            exit = context.hasFailures() ? 1 : 0;
        } catch (Exception | AssertionError | OutOfMemoryError exception) {
            fatal = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            System.err.println("BENCH FATAL: " + fatal);
            exception.printStackTrace(System.err);
        } finally {
            if (context != null && output != null) {
                try {
                    context.writeReport(output.resolve("results.json"), group, profile, revision,
                            started, fixtureMillis, fatal);
                    System.out.println("BENCH REPORT " + output.resolve("results.json"));
                } catch (Exception reportFailure) {
                    reportFailure.printStackTrace(System.err);
                    exit = 1;
                }
            }
            System.exit(exit);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (index + 1 >= args.length || !args[index].startsWith("--")) throw new IllegalArgumentException("Use --option value pairs");
            String name = args[index].substring(2);
            if (!java.util.Set.of("group", "profile", "rows", "history-length", "warmups", "samples", "report-limit", "output", "timeout-seconds").contains(name)
                    || options.putIfAbsent(name, args[index + 1]) != null) throw new IllegalArgumentException("Unknown or duplicate option: " + name);
        }
        return options;
    }

    private static int integer(Map<String, String> options, String name, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " outside supported limits");
        return value;
    }

    /** Last-resort bound for this isolated fixture JVM, including an unresponsive Swing EDT. */
    private static void startWatchdog(int timeoutSeconds) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutSeconds * 1000L);
            } catch (InterruptedException cancelled) {
                return;
            }
            System.err.println("BENCH FATAL: isolated benchmark JVM timed out after " + timeoutSeconds + " seconds");
            System.err.flush();
            Runtime.getRuntime().halt(124);
        }, "ringlog-benchmark-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
