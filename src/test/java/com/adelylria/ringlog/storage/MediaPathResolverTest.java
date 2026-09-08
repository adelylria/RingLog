package com.adelylria.ringlog.storage;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MediaPathResolverTest {

    private MediaPathResolverTest() {
    }

    public static void relativePhotoReferenceResolvesCorrectly() throws Exception {
        Path root = Files.createTempDirectory("ringlog-media-resolver-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root);
            MediaPathResolver resolver = new MediaPathResolver(paths);

            require(resolver.resolveEventPhoto("events/ab/example.jpg").equals(
                            paths.eventPhotosDirectory().resolve("ab/example.jpg")),
                    "An events reference must resolve below photos/events");
            require(resolver.resolveEventPhoto("native/cd/restored.jpg").equals(
                            paths.nativePhotosDirectory().resolve("cd/restored.jpg")),
                    "A native reference must resolve below photos/native");
            require(resolver.resolveUnassignedPhoto("ef/orphan.jpg").equals(
                            paths.unassignedPhotosDirectory().resolve("ef/orphan.jpg")),
                    "An unassigned reference must resolve below unassigned-photos");
        } finally {
            Files.deleteIfExists(root);
        }
    }

    public static void mediaResolverRejectsTraversal() throws Exception {
        Path root = Files.createTempDirectory("ringlog-media-traversal-");
        try {
            MediaPathResolver resolver = new MediaPathResolver(AppPaths.forDataRoot(root));
            requireRejected(() -> resolver.resolveEventPhoto("events/../../outside.jpg"));
            requireRejected(() -> resolver.resolveEventPhoto("events/../native/file.jpg"));
            requireRejected(() -> resolver.resolveUnassignedPhoto("../outside.jpg"));
            requireRejected(() -> resolver.resolveEventPhoto("events\\..\\outside.jpg"));
        } finally {
            Files.deleteIfExists(root);
        }
    }

    public static void mediaResolverRejectsAbsoluteManagedReference() throws Exception {
        Path root = Files.createTempDirectory("ringlog-media-absolute-");
        try {
            MediaPathResolver resolver = new MediaPathResolver(AppPaths.forDataRoot(root));
            requireRejected(() -> resolver.resolveEventPhoto("C:/photos/example.jpg"));
            requireRejected(() -> resolver.resolveEventPhoto("C:\\photos\\example.jpg"));
            requireRejected(() -> resolver.resolveEventPhoto("//server/share/example.jpg"));
            requireRejected(() -> resolver.resolveEventPhoto("\\\\server\\share\\example.jpg"));
            requireRejected(() -> resolver.resolveEventPhoto("/var/photos/example.jpg"));
        } finally {
            Files.deleteIfExists(root);
        }
    }

    public static void managedPathsRoundTripToCanonicalReferences() throws Exception {
        Path root = Files.createTempDirectory("ringlog-media-reference-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root);
            MediaPathResolver resolver = new MediaPathResolver(paths);
            Path event = paths.eventPhotosDirectory().resolve("ab/photo.jpg");
            Path restored = paths.nativePhotosDirectory().resolve("cd/photo.jpg");
            Path unassigned = paths.unassignedPhotosDirectory().resolve("ef/photo.jpg");

            require("events/ab/photo.jpg".equals(
                            resolver.toEventReference(PhotoArea.EVENTS, event)),
                    "Event paths must be stored as canonical managed references");
            require("native/cd/photo.jpg".equals(
                            resolver.toEventReference(PhotoArea.NATIVE, restored)),
                    "Native paths must be stored as canonical managed references");
            require("ef/photo.jpg".equals(resolver.toUnassignedReference(unassigned)),
                    "Unassigned paths must be relative to unassigned-photos");
            requireRejected(() -> resolver.toEventReference(
                    PhotoArea.EVENTS, root.resolve("outside.jpg")));
        } finally {
            Files.deleteIfExists(root);
        }
    }

    private static void requireRejected(ThrowingAction action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Unsafe managed media reference was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        relativePhotoReferenceResolvesCorrectly();
        mediaResolverRejectsTraversal();
        mediaResolverRejectsAbsoluteManagedReference();
        managedPathsRoundTripToCanonicalReferences();
        System.out.println("MediaPathResolverTest: PASS");
    }
}
