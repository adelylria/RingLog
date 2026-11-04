package com.adelylria.ringlog.benchmark;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.portable.InstalledDistribution;
import com.adelylria.ringlog.portable.PortableCopyResult;
import com.adelylria.ringlog.portable.PortableCopyService;

/** Optional full binary/media copy. One local-disk sample; it does not claim USB performance. */
public final class PortableBenchmarks {
    private PortableBenchmarks() { }

    public static void run(BenchmarkContext ctx) throws Exception {
        Path kit = Path.of(System.getProperty("ringlog.benchmark.kitRoot", ".")).toAbsolutePath().normalize();
        Path runtime = Path.of(System.getProperty("ringlog.benchmark.runtime", System.getProperty("java.home")));
        Properties release = new Properties();
        try (var input = Files.newInputStream(runtime.resolve("release"))) { release.load(input); }
        if (!"\"x86_64\"".equals(release.getProperty("OS_ARCH"))
                || !"\"17.0.7\"".equals(release.getProperty("JAVA_VERSION"))
                || Files.isRegularFile(runtime.resolve("bin/javac.exe"))) {
            ctx.skip("portable", "create-and-validate-read-only-copy",
                    "Requires the pinned RingLog x64 JRE; a JDK or x86 runtime would give a misleading copy size");
            return;
        }
        Path distribution = ctx.workDirectory().resolve("distribution");
        Files.createDirectory(distribution);
        for (String file : List.of("RingLog.jar", "RingLog.ico", "distribution.properties")) {
            Files.copy(kit.resolve("app").resolve(file), distribution.resolve(file));
        }
        // Hard links make setup cheap on one volume. The measured production copy always copies bytes.
        linkOrCopyTree(kit.resolve("libs"), distribution.resolve("libs"));
        linkOrCopyTree(runtime, distribution.resolve("runtime"));
        PortableCopyService service = new PortableCopyService(ctx.paths(),
                new InstalledDistribution(distribution), new DataMutationCoordinator());
        Path destination = Files.createDirectory(ctx.workDirectory().resolve("portable-output"));
        ctx.measure("portable", "preflight-size-space-and-paths", () -> {
            var estimate = service.preflight(destination);
            ctx.require(estimate.hasEnoughSpace(), "Insufficient space for synthetic portable copy");
            return estimate.estimatedCopyBytes();
        });
        AtomicReference<PortableCopyResult> completed = new AtomicReference<>();
        ctx.measure("portable", "create-and-validate-read-only-copy", 0, 1, () -> {
            PortableCopyResult result = service.create(destination, false, progress -> { });
            completed.set(result);
            ctx.require(result.eventCount() == ctx.eventCount(), "Portable copy must retain all synthetic records");
            return result.copiedBytes();
        });
        if (completed.get() != null) {
            ctx.require(Files.isRegularFile(completed.get().portableRoot().resolve("RingLog.jar")), "Portable application missing");
            ctx.require(Files.isRegularFile(completed.get().portableRoot().resolve("data/ringlog.db")), "Portable database missing");
        }
    }

    private static void linkOrCopyTree(Path source, Path destination) throws Exception {
        Path root = source.toAbsolutePath().normalize();
        Path targetRoot = destination.toAbsolutePath().normalize();
        try (var walk = Files.walk(root)) {
            for (Path file : walk.toList()) {
                if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("Benchmark distribution cannot contain symbolic links");
                Path target = targetRoot.resolve(root.relativize(file)).normalize();
                if (!target.startsWith(targetRoot)) throw new IllegalArgumentException("Unsafe fixture path");
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else {
                    try { Files.createLink(target, file); }
                    catch (java.io.IOException | UnsupportedOperationException unavailable) {
                        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
        }
    }
}
