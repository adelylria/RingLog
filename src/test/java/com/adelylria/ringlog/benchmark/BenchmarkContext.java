package com.adelylria.ringlog.benchmark;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.storage.AppPaths;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

/** Small diagnostic harness: warmup, raw samples and correctness checks, never a timing CI gate. */
public final class BenchmarkContext {
    @FunctionalInterface public interface BenchmarkAction { long run() throws Exception; }

    private final AppPaths paths;
    private final Path workDirectory;
    private final int eventCount;
    private final int warmups;
    private final int samples;
    private final int reportLimit;
    private final long eventId;
    private final long birdId;
    private final List<Path> samplePhotos;
    private final BirdEventRepository events;
    private final CatalogRepository catalog;
    private final List<Result> results = new ArrayList<>();
    private static volatile long blackhole;

    BenchmarkContext(AppPaths paths, Path workDirectory, int eventCount, int warmups,
                     int samples, int reportLimit, long eventId, long birdId, List<Path> samplePhotos) {
        this.paths = paths;
        this.workDirectory = workDirectory;
        this.eventCount = eventCount;
        this.warmups = warmups;
        this.samples = samples;
        this.reportLimit = reportLimit;
        this.eventId = eventId;
        this.birdId = birdId;
        this.samplePhotos = List.copyOf(samplePhotos);
        this.events = new BirdEventRepository(paths);
        this.catalog = new CatalogRepository(paths.databasePath().toString());
    }

    public AppPaths paths() { return paths; }
    public Path workDirectory() { return workDirectory; }
    public String databaseFile() { return paths.databasePath().toString(); }
    public int eventCount() { return eventCount; }
    public int warmups() { return warmups; }
    public int samples() { return samples; }
    public int reportLimit() { return reportLimit; }
    public long eventId() { return eventId; }
    public long photoEventId() { return eventId; }
    public long birdId() { return birdId; }
    public String ringNumber() { return String.format(Locale.ROOT, "BENCH-%06d", birdId); }
    public List<Path> samplePhotos() { return samplePhotos; }
    public BirdEventRepository events() { return events; }
    public CatalogRepository catalog() { return catalog; }

    public List<Long> reportIds(int maximum) {
        return java.util.stream.LongStream.rangeClosed(1, Math.min(maximum, eventCount)).boxed().toList();
    }

    public void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public void measure(String group, String name, BenchmarkAction action) {
        measure(group, name, warmups, samples, action);
    }

    public void measure(String group, String name, int warmupCount, int sampleCount, BenchmarkAction action) {
        if (warmupCount < 0 || sampleCount < 1) throw new IllegalArgumentException("Invalid sample counts");
        List<Long> nanos = new ArrayList<>();
        long checksum = 0;
        long gcCountBefore = gcCount();
        long gcMillisBefore = gcMillis();
        long heapBefore = usedHeap();
        String failure = null;
        try {
            for (int round = 0; round < warmupCount; round++) blackhole ^= action.run();
            gcCountBefore = gcCount();
            gcMillisBefore = gcMillis();
            heapBefore = usedHeap();
            for (int round = 0; round < sampleCount; round++) {
                long started = System.nanoTime();
                long value = action.run();
                nanos.add(System.nanoTime() - started);
                checksum = 31 * checksum + value;
                blackhole ^= value;
            }
        } catch (Exception | AssertionError exception) {
            failure = sanitize(exception.getClass().getSimpleName() + ": " + exception.getMessage());
            System.err.println("BENCH FAILED " + group + "/" + name + ": " + failure);
        }
        Result result = new Result(group, name, eventCount, warmupCount, List.copyOf(nanos), checksum,
                gcCount() - gcCountBefore, gcMillis() - gcMillisBefore, heapBefore, usedHeap(), failure);
        results.add(result);
        print(result);
    }

    /** Caller supplies timed samples only; useful for action EDT time versus end-to-end latency. */
    public void recordNanos(String group, String name, List<Long> nanos, long checksum) {
        require(!nanos.isEmpty() && nanos.stream().allMatch(value -> value >= 0), "Invalid timings");
        Result result = new Result(group, name, eventCount, 0, List.copyOf(nanos), checksum,
                -1, -1, -1, -1, null);
        results.add(result);
        blackhole ^= checksum;
        print(result);
    }

    public void skip(String group, String name, String reason) {
        Result result = new Result(group, name, eventCount, 0, List.of(), 0,
                -1, -1, -1, -1, "SKIPPED: " + reason);
        results.add(result);
        System.out.println("BENCH SKIPPED " + group + '/' + name + ": " + reason);
    }

    public boolean hasFailures() { return results.stream().anyMatch(result -> result.error() != null && !result.skipped()); }

    private static void print(Result result) {
        System.out.printf(Locale.ROOT, "BENCH %s/%s rows=%d n=%d median=%.3fms p95=%.3fms %s%n",
                result.group(), result.name(), result.rows(), result.nanos().size(),
                result.medianMs(), result.p95Ms(), result.error() == null ? "PASS" : "FAILED");
        System.out.flush();
    }

    void writeReport(Path destination, String group, String profile, String revision,
                     String started, double fixtureMillis, String fatalError) throws IOException {
        Files.createDirectories(destination.getParent());
        try (JsonGenerator json = new JsonFactory().createGenerator(destination.toFile(),
                com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            json.writeStringField("format", "RingLog benchmark");
            json.writeNumberField("formatVersion", 1);
            json.writeStringField("startedAt", started);
            json.writeStringField("completedAt", java.time.Instant.now().toString());
            json.writeStringField("profile", profile);
            json.writeStringField("revision", revision);
            json.writeStringField("group", group);
            json.writeNumberField("rows", eventCount);
            json.writeNumberField("historyLength", Integer.getInteger("ringlog.benchmark.historyLength", 10));
            json.writeNumberField("schema", com.adelylria.ringlog.database.Database.SCHEMA_VERSION);
            json.writeNumberField("warmups", warmups);
            json.writeNumberField("samples", samples);
            json.writeNumberField("reportLimit", reportLimit);
            json.writeNumberField("fixtureSetupMs", fixtureMillis);
            json.writeObjectFieldStart("environment");
            for (String property : List.of("os.name", "os.version", "os.arch", "java.version", "java.vendor", "java.vm.name"))
                json.writeStringField(property, System.getProperty(property, "unknown"));
            json.writeNumberField("processors", Runtime.getRuntime().availableProcessors());
            json.writeNumberField("maxHeapMiB", Runtime.getRuntime().maxMemory() / 1048576);
            json.writeBooleanField("headless", java.awt.GraphicsEnvironment.isHeadless());
            json.writeStringField("gc", ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .map(bean -> bean.getName()).collect(java.util.stream.Collectors.joining(", ")));
            json.writeEndObject();
            if (fatalError != null) json.writeStringField("fatalError", sanitize(fatalError));
            json.writeArrayFieldStart("cases");
            for (Result result : results) {
                json.writeStartObject();
                json.writeStringField("group", result.group());
                json.writeStringField("name", result.name());
                json.writeNumberField("rows", result.rows());
                json.writeStringField("status", result.skipped() ? "SKIPPED" : result.error() == null ? "PASS" : "FAILED");
                json.writeNumberField("warmups", result.warmups());
                json.writeNumberField("samples", result.nanos().size());
                json.writeNumberField("medianMs", result.medianMs());
                json.writeNumberField("p95Ms", result.p95Ms());
                json.writeNumberField("minMs", result.minMs());
                json.writeNumberField("maxMs", result.maxMs());
                json.writeNumberField("gcCount", result.gcCount());
                json.writeNumberField("gcMillis", result.gcMillis());
                json.writeNumberField("heapBeforeMiB", result.heapBefore() < 0 ? -1 : result.heapBefore() / 1048576.0);
                json.writeNumberField("heapAfterMiB", result.heapAfter() < 0 ? -1 : result.heapAfter() / 1048576.0);
                json.writeNumberField("checksum", result.checksum());
                if (result.error() != null) json.writeStringField("error", result.error());
                json.writeArrayFieldStart("rawMs");
                for (long value : result.nanos()) json.writeNumber(value / 1_000_000.0);
                json.writeEndArray();
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
        }
        StringBuilder csv = new StringBuilder("group,name,rows,status,warmups,samples,median_ms,p95_ms,min_ms,max_ms,gc_count,gc_ms,checksum\n");
        for (Result result : results) csv.append(String.format(Locale.ROOT,
                "%s,%s,%d,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%d,%d%n",
                result.group(), result.name(), result.rows(), result.skipped() ? "SKIPPED" : result.error() == null ? "PASS" : "FAILED",
                result.warmups(), result.nanos().size(), result.medianMs(), result.p95Ms(),
                result.minMs(), result.maxMs(), result.gcCount(), result.gcMillis(), result.checksum()));
        Files.writeString(destination.resolveSibling("results.csv"), csv, StandardCharsets.UTF_8);
    }

    private static long usedHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    private String sanitize(String message) {
        String result = message.replace(workDirectory.toString(), "<benchmark-work>");
        String userDirectory = System.getProperty("user.home");
        if (userDirectory != null && !userDirectory.isBlank()) {
            result = result.replace(userDirectory, "<user-home>")
                    .replace(userDirectory.replace('\\', '/'), "<user-home>");
        }
        return result;
    }
    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }
    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    private record Result(String group, String name, int rows, int warmups, List<Long> nanos, long checksum,
                          long gcCount, long gcMillis, long heapBefore, long heapAfter, String error) {
        private boolean skipped() { return error != null && error.startsWith("SKIPPED: "); }
        private double percentile(double quantile) {
            if (nanos.isEmpty()) return 0;
            long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * quantile) - 1))] / 1_000_000.0;
        }
        private double medianMs() {
            if (nanos.isEmpty()) return 0;
            long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            return (sorted.length % 2 == 1 ? sorted[sorted.length / 2]
                    : (sorted[sorted.length / 2 - 1] / 2.0 + sorted[sorted.length / 2] / 2.0)) / 1_000_000.0;
        }
        private double p95Ms() { return percentile(.95); }
        private double minMs() { return nanos.stream().mapToLong(Long::longValue).min().orElse(0) / 1_000_000.0; }
        private double maxMs() { return nanos.stream().mapToLong(Long::longValue).max().orElse(0) / 1_000_000.0; }
    }
}
