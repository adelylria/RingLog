package com.adelylria.ringlog.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.database.DatabaseAccess;
import com.adelylria.ringlog.database.ReadOnlyDatabaseAccess;
import com.adelylria.ringlog.database.WritableDatabaseAccess;
import com.adelylria.ringlog.model.EnvironmentCategory;
import com.adelylria.ringlog.model.view.EnvironmentOption;
import com.j256.ormlite.support.ConnectionSource;

/** Counts real SQLite connections and exercises invalidation races; timings are evidence, not assertions. */
public final class EnvironmentCatalogPerformanceTest {
    private EnvironmentCatalogPerformanceTest() { }

    public static void repeatedReadsAndMutationsUseOneSnapshotPerGeneration() throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-cache-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            CountingAccess access = new CountingAccess(new WritableDatabaseAccess(database.toString()));
            CatalogRepository catalog = new CatalogRepository(access);
            List<EnvironmentOption> first = catalog.findEnvironmentOptions();
            for (int index = 0; index < 10000; index++) {
                require(catalog.findEnvironmentOptions() == first, "Repeated reads must reuse the immutable snapshot");
            }
            require(access.connections.get() == 1, "Ten thousand repeated catalog reads must perform one SQLite load");
            try {
                first.clear();
                throw new AssertionError("A caller must not mutate the shared snapshot");
            } catch (UnsupportedOperationException expected) { }

            long added = catalog.saveEnvironmentOption(null, EnvironmentCategory.CLOUDS, "Cubierto");
            List<EnvironmentOption> created = reloadAfterMutation(catalog, access);
            require(created.stream().anyMatch(option -> option.id() == added), "New choices must invalidate the cached list");
            catalog.saveEnvironmentOption(added, EnvironmentCategory.CLOUDS, "Muy cubierto");
            List<EnvironmentOption> renamed = reloadAfterMutation(catalog, access);
            require(renamed.stream().anyMatch(option -> option.id() == added && option.name().equals("Muy cubierto")),
                    "Renamed choices must be visible on the next read");
            catalog.setEnvironmentOptionActive(added, false);
            List<EnvironmentOption> hidden = reloadAfterMutation(catalog, access);
            require(hidden.stream().anyMatch(option -> option.id() == added && !option.active()),
                    "Availability changes must be visible on the next read");
            catalog.moveEnvironmentOption(added, -1);
            List<EnvironmentOption> moved = reloadAfterMutation(catalog, access);
            require(moved.stream().filter(option -> option.category() == EnvironmentCategory.CLOUDS).toList().get(3).id() == added,
                    "Reordering must invalidate cached positions");
            try {
                catalog.saveEnvironmentOption(null, EnvironmentCategory.CLOUDS, "Muy cubierto");
                throw new AssertionError("A duplicate must be rejected");
            } catch (IllegalArgumentException expected) { }
            int afterRejectedMutation = access.connections.get();
            require(catalog.findEnvironmentOptions() == moved && access.connections.get() == afterRejectedMutation,
                    "Rejected mutations must not force an unnecessary reload");

            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE environment_option SET name = 'Importado' WHERE id = " + added);
            }
            catalog.invalidateEnvironmentOptions();
            List<EnvironmentOption> imported = reloadAfterMutation(catalog, access);
            require(imported.stream().anyMatch(option -> option.id() == added && option.name().equals("Importado")),
                    "The explicit import/restore hook must discard the old snapshot");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void concurrentReadersShareTheSameDatabaseLoad() throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-concurrent-");
        var workers = Executors.newFixedThreadPool(8);
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            CountingAccess access = new CountingAccess(new WritableDatabaseAccess(database.toString()));
            CatalogRepository catalog = new CatalogRepository(access);
            CountDownLatch start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<List<EnvironmentOption>>>();
            for (int index = 0; index < 8; index++) {
                futures.add(workers.submit(() -> { start.await(); return catalog.findEnvironmentOptions(); }));
            }
            start.countDown();
            List<EnvironmentOption> snapshot = futures.get(0).get(10, TimeUnit.SECONDS);
            for (var future : futures) require(future.get(10, TimeUnit.SECONDS) == snapshot,
                    "Concurrent readers must share one fully loaded immutable snapshot");
            require(access.connections.get() == 1, "Concurrent cold readers must not issue duplicate SQLite loads");
        } finally {
            workers.shutdownNow();
            require(workers.awaitTermination(10, TimeUnit.SECONDS), "Concurrent readers must finish");
            deleteDirectory(root);
        }
    }

    public static void invalidationDiscardsAnInFlightOldSnapshotWithoutWaitingForIo() throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-invalidation-race-");
        var workers = Executors.newFixedThreadPool(2);
        ReadGate gate = new ReadGate();
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            CountingAccess access = new CountingAccess(new WritableDatabaseAccess(database.toString()));
            CatalogRepository catalog = new CatalogRepository(access);
            access.afterRead.set(gate);
            var loading = workers.submit(catalog::findEnvironmentOptions);
            require(gate.readCompleted.await(10, TimeUnit.SECONDS), "The old database snapshot must be captured before invalidation");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE environment_option SET name = 'Despejado importado' WHERE category = 'CLOUDS' AND sort_order = 0");
            }
            // A pending disk read must never block UI-side invalidation.
            workers.submit(catalog::invalidateEnvironmentOptions).get(10, TimeUnit.SECONDS);
            gate.release.countDown();
            List<EnvironmentOption> loaded = loading.get(10, TimeUnit.SECONDS);
            require(loaded.get(0).name().equals("Despejado importado") && access.connections.get() == 2,
                    "A load invalidated in flight must be discarded and reloaded, never published after invalidation");
            require(catalog.findEnvironmentOptions() == loaded && access.connections.get() == 2,
                    "Only the new generation may be cached");
        } finally {
            gate.release.countDown();
            workers.shutdownNow();
            require(workers.awaitTermination(10, TimeUnit.SECONDS), "Invalidation workers must finish");
            deleteDirectory(root);
        }
    }

    public static void portableReadsAreCachedAndFailuresRemainRetryable() throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-portable-cache-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            CountingAccess access = new CountingAccess(new ReadOnlyDatabaseAccess(database));
            CatalogRepository catalog = new CatalogRepository(access);
            access.failNext.set(true);
            try {
                catalog.findEnvironmentOptions();
                throw new AssertionError("A failed database load must be reported");
            } catch (RepositoryException expected) { }
            List<EnvironmentOption> snapshot = catalog.findEnvironmentOptions();
            for (int index = 0; index < 1000; index++) require(catalog.findEnvironmentOptions() == snapshot,
                    "A failed first load must be retryable and portable reads must reuse the valid cache");
            require(access.connections.get() == 1, "A portable cache must also require only one successful database load");
            catalog.invalidateEnvironmentOptions();
            reloadAfterMutation(catalog, access);
            require(Database.SCHEMA_VERSION == 8, "This check must exercise the current environment schema");
            Database.validate(database.toString());
        } finally {
            deleteDirectory(root);
        }
    }

    public static void measureColdAndCachedReads() throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-read-timing-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            CountingAccess access = new CountingAccess(new ReadOnlyDatabaseAccess(database));
            CatalogRepository catalog = new CatalogRepository(access);
            for (int index = 0; index < 1000; index++) catalog.findEnvironmentOptions();
            long[] cold = new long[25];
            for (int index = 0; index < cold.length; index++) {
                catalog.invalidateEnvironmentOptions();
                long started = System.nanoTime();
                require(catalog.findEnvironmentOptions().size() == 18, "Cold reads must preserve all choices");
                cold[index] = System.nanoTime() - started;
            }
            int beforeCached = access.connections.get();
            long[] cached = new long[20000];
            long checksum = 0;
            for (int index = 0; index < cached.length; index++) {
                long started = System.nanoTime();
                checksum += catalog.findEnvironmentOptions().size();
                cached[index] = System.nanoTime() - started;
            }
            require(access.connections.get() == beforeCached && checksum == 18L * cached.length,
                    "Twenty thousand hot reads must preserve the complete catalog without any SQLite connection");
            Arrays.sort(cold);
            Arrays.sort(cached);
            System.out.printf(Locale.ROOT,
                    "Environment catalog measurement: cold median=%.3f ms (%d SQLite loads); cached median=%.3f us (%d reads, 0 SQLite loads)%n",
                    cold[cold.length / 2] / 1_000_000.0, cold.length,
                    cached[cached.length / 2] / 1_000.0, cached.length);
        } finally {
            deleteDirectory(root);
        }
    }

    private static List<EnvironmentOption> reloadAfterMutation(CatalogRepository catalog, CountingAccess access) {
        int before = access.connections.get();
        List<EnvironmentOption> reloaded = catalog.findEnvironmentOptions();
        require(access.connections.get() == before + 1, "A changed generation must load once on demand");
        require(catalog.findEnvironmentOptions() == reloaded && access.connections.get() == before + 1,
                "Further reads of that generation must not query SQLite");
        return reloaded;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteDirectory(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class ReadGate {
        private final CountDownLatch readCompleted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private void pause() throws SQLException {
            readCompleted.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("Timed out releasing isolated cache test");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted isolated cache test", exception);
            }
        }
    }

    private static final class CountingAccess implements DatabaseAccess {
        private final DatabaseAccess delegate;
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicBoolean failNext = new AtomicBoolean();
        private final AtomicReference<ReadGate> afterRead = new AtomicReference<>();
        private CountingAccess(DatabaseAccess delegate) { this.delegate = delegate; }
        @Override public Connection openConnection() throws SQLException { return delegate.openConnection(); }
        @Override public ConnectionSource openConnectionSource() throws SQLException {
            connections.incrementAndGet();
            return delegate.openConnectionSource();
        }
        @Override public boolean readOnly() { return delegate.readOnly(); }
        @Override public <T> T withOrm(OrmOperation<T> operation) throws SQLException {
            if (failNext.compareAndSet(true, false)) throw new SQLException("Simulated isolated SQLite read failure");
            T result = DatabaseAccess.super.withOrm(operation);
            ReadGate gate = afterRead.getAndSet(null);
            if (gate != null) gate.pause();
            return result;
        }
    }

    public static void main(String[] args) throws Exception {
        repeatedReadsAndMutationsUseOneSnapshotPerGeneration();
        concurrentReadersShareTheSameDatabaseLoad();
        invalidationDiscardsAnInFlightOldSnapshotWithoutWaitingForIo();
        portableReadsAreCachedAndFailuresRemainRetryable();
        measureColdAndCachedReads();
        System.out.println("EnvironmentCatalogPerformanceTest: PASS");
    }
}
