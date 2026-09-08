package com.adelylria.ringlog.portable;

import java.awt.Frame;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import com.adelylria.ringlog.RingLog;
import com.adelylria.ringlog.application.ApplicationContext;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.ui.MainFrame;

/** Runs in a separate process with the packaged JAR first on the classpath. */
public final class PortableStartupProbe {
    private PortableStartupProbe() { }

    public static void main(String[] args) {
        int exitCode = 1;
        try {
            Path root = Path.of(args[0]).toRealPath();
            require(Runtime.version().feature() == 17, "Probe must use bundled Java 17");
            require(Path.of(System.getProperty("java.home")).toRealPath()
                    .equals(root.resolve("runtime").toRealPath()), "Probe must use the copied runtime");
            require(Path.of(RingLog.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toRealPath().equals(root.resolve("RingLog.jar")),
                    "Application classes must come from the copied JAR, not target/classes");
            RingLog.main(new String[0]);
            AtomicReference<MainFrame> application = new AtomicReference<>();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (application.get() == null && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> {
                    for (Frame frame : Frame.getFrames()) {
                        if (frame instanceof MainFrame main && main.isShowing()) {
                            application.set(main);
                        }
                    }
                });
                if (application.get() == null) {
                    Thread.sleep(100);
                }
            }
            MainFrame frame = application.get();
            require(frame != null, "Packaged portable must open the actual MainFrame");
            ApplicationContext context = (ApplicationContext) field(frame, "context");
            require(context.portableReadOnly() && context.mutationServices().isEmpty(),
                    "Portable startup must not create mutation services");
            for (String absent : new String[]{"capturePanel", "updateCoordinator", "updateOverlay"}) {
                require(field(frame, absent) == null, "Portable must not create " + absent);
            }
            require(context.paths().databasePath().startsWith(root)
                    && context.paths().logsDirectory().startsWith(root)
                    && context.preferences().path().startsWith(root), "All state must stay portable");
            context.preferences().put("theme.dark", "true");
            BirdEventRepository events = (BirdEventRepository) field(frame, "eventRepository");
            require(events.findAll().size() == 289, "The UI must read all 289 real events");
            long firstId = events.findAll().get(0).id();
            for (String route : new String[]{MainFrame.HOME, MainFrame.CAPTURES,
                    MainFrame.SPECIES, MainFrame.PLACES, MainFrame.REPORTS,
                    MainFrame.CONFLICTS, MainFrame.SETTINGS}) {
                SwingUtilities.invokeAndWait(() -> frame.navigate(route));
                // Let the same asynchronous refresh workers used by the UI load their data.
                Thread.sleep(250);
            }
            SwingUtilities.invokeAndWait(() -> frame.showEvent(firstId));
            Thread.sleep(500);
            require(events.findDetail(firstId).isPresent(), "Entry detail must remain accessible");
            System.out.println("PortableStartupProbe: PASS (bundled Java, packaged startup, navigation, local state)");
            exitCode = 0;
        } catch (Throwable failure) {
            failure.printStackTrace();
        } finally {
            // Exit the isolated process so no Swing worker retains handles before move/cleanup.
            System.exit(exitCode);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
