package com.adelylria.ringlog.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/** Keeps ordinary mutations out of a short, consistent DB-and-media snapshot window. */
public final class DataMutationCoordinator {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final List<Consumer<Boolean>> snapshotListeners = new CopyOnWriteArrayList<>();
    private volatile boolean snapshotInProgress;

    public Lease acquireMutation() {
        lock.readLock().lock();
        return lock.readLock()::unlock;
    }

    public Lease acquireConsistentSnapshot() {
        lock.writeLock().lock();
        snapshotInProgress = true;
        notifySnapshotListeners(true);
        return () -> {
            snapshotInProgress = false;
            notifySnapshotListeners(false);
            lock.writeLock().unlock();
        };
    }

    public boolean snapshotInProgress() {
        return snapshotInProgress;
    }

    public void addSnapshotListener(Consumer<Boolean> listener) {
        if (listener != null) {
            snapshotListeners.add(listener);
        }
    }

    private void notifySnapshotListeners(boolean active) {
        for (Consumer<Boolean> listener : snapshotListeners) {
            listener.accept(active);
        }
    }

    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
