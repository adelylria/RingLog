package com.adelylria.ringlog.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.RingLog;
import com.adelylria.ringlog.application.ApplicationContext;
import com.adelylria.ringlog.application.ApplicationMutationServices;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.portable.InstalledDistribution;
import com.adelylria.ringlog.portable.PortableCopyService;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.repository.MigrationConflictRepository;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.ui.components.Sidebar;
import com.adelylria.ringlog.ui.importexport.ConflictsPanel;
import com.adelylria.ringlog.ui.panels.CaptureDetailPanel;
import com.adelylria.ringlog.ui.panels.CaptureListPanel;
import com.adelylria.ringlog.ui.panels.CapturePanel;
import com.adelylria.ringlog.ui.panels.HomePanel;
import com.adelylria.ringlog.ui.panels.PlacePanel;
import com.adelylria.ringlog.ui.panels.ReportsPanel;
import com.adelylria.ringlog.ui.panels.SettingsPanel;
import com.adelylria.ringlog.ui.panels.SpeciesPanel;
import com.adelylria.ringlog.ui.portable.PortableCopyPanel;
import com.adelylria.ringlog.ui.theme.UiKit;
import com.adelylria.ringlog.ui.update.UpdateOverlay;
import com.adelylria.ringlog.update.UpdateCoordinator;

public class MainFrame extends JFrame {

    public static final String HOME = "HOME";
    public static final String CAPTURES = "CAPTURES";
    public static final String NEW_CAPTURE = "NEW_CAPTURE";
    public static final String CAPTURE_DETAIL = "CAPTURE_DETAIL";
    public static final String SPECIES = "SPECIES";
    public static final String PLACES = "PLACES";
    public static final String REPORTS = "REPORTS";
    public static final String CONFLICTS = "CONFLICTS";
    public static final String SETTINGS = "SETTINGS";
    public static final String PORTABLE_COPY = "PORTABLE_COPY";

    private final ApplicationContext context;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel = new JPanel(cardLayout);
    private final JLabel portableCreationNotice = UiKit.valueLabel(
            "Creando copia portátil. No cierres RingLog ni retires el dispositivo."
    );
    private final Sidebar sidebar;
    private final BirdEventRepository eventRepository;
    private final CatalogRepository catalogRepository;
    private final HomePanel homePanel;
    private final CaptureListPanel captureListPanel;
    private final CaptureDetailPanel captureDetailPanel;
    private final CapturePanel capturePanel;
    private final SpeciesPanel speciesPanel;
    private final PlacePanel placePanel;
    private final ReportsPanel reportsPanel;
    private final ConflictsPanel conflictsPanel;
    private final SettingsPanel settingsPanel;
    private final PortableCopyPanel portableCopyPanel;
    private final UpdateOverlay updateOverlay;
    private final UpdateCoordinator updateCoordinator;
    private JMenuItem importMenuItem;
    private JMenuItem exportMenuItem;
    private JMenuItem portableMenuItem;
    private boolean portableCreationBusy;
    private boolean snapshotBusy;

    public MainFrame() {
        this(ApplicationContext.normal(AppPaths.production()));
    }

    public MainFrame(AppPaths appPaths) {
        this(ApplicationContext.normal(appPaths));
    }

    public MainFrame(ApplicationContext context) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        eventRepository = new BirdEventRepository(
                context.paths(),
                context.databaseAccess(),
                context.mutationCoordinator()
        );
        catalogRepository = new CatalogRepository(
                context.databaseAccess(), context.mutationCoordinator()
        );

        initializeWindow();
        if (context.capabilities().checkUpdates()) {
            updateOverlay = new UpdateOverlay();
            updateCoordinator = UpdateCoordinator.application(
                    updateOverlay, this::closeForUpdate
            );
        } else {
            updateOverlay = null;
            updateCoordinator = null;
        }

        sidebar = new Sidebar(this::navigate, context.capabilities());
        homePanel = new HomePanel(
                eventRepository,
                this::showEvent,
                () -> navigate(NEW_CAPTURE),
                context.capabilities().mutateDiary()
        );
        captureListPanel = new CaptureListPanel(
                eventRepository,
                this::showEvent,
                () -> navigate(NEW_CAPTURE),
                null,
                context.capabilities().mutateDiary()
        );
        captureDetailPanel = new CaptureDetailPanel(
                eventRepository,
                () -> navigate(CAPTURES),
                this::editEvent,
                context.capabilities().mutateDiary()
        );
        capturePanel = context.capabilities().mutateDiary()
                ? new CapturePanel(
                eventRepository,
                catalogRepository,
                this::showEvent,
                () -> navigate(CAPTURES)
        ) : null;
        speciesPanel = new SpeciesPanel(
                catalogRepository, context.capabilities().manageCatalogs()
        );
        placePanel = new PlacePanel(
                catalogRepository, context.capabilities().manageCatalogs()
        );
        reportsPanel = new ReportsPanel(eventRepository, catalogRepository);

        ApplicationMutationServices mutations = context.mutationServices().orElse(null);
        conflictsPanel = new ConflictsPanel(
                new MigrationConflictRepository(context.databaseAccess()),
                mutations == null ? null : mutations.conflictResolution(),
                this::refreshImportedData
        );

        portableCopyPanel = createPortablePanel();
        settingsPanel = new SettingsPanel(
                this,
                context.paths(),
                context.capabilities(),
                mutations == null ? null : mutations.importExport(),
                this::refreshImportedData,
                () -> navigate(CONFLICTS),
                this::checkForUpdatesManually,
                () -> navigate(PORTABLE_COPY)
        );

        initializeLayout();
        context.mutationCoordinator().addSnapshotListener(active ->
                SwingUtilities.invokeLater(() -> {
                    snapshotBusy = active;
                    applyMutationAvailability();
                })
        );
    }

    public static String initialScreen() {
        return HOME;
    }

    private void initializeWindow() {
        setTitle(context.portableReadOnly()
                ? "RingLog · Copia portátil · Solo lectura"
                : "RingLog · Diario de campo");
        ApplicationIcon.applyTo(this);
        setSize(1280, 820);
        setMinimumSize(new Dimension(1040, 680));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                if (!portableCreationBusy) {
                    dispose();
                    System.exit(0);
                }
            }
        });
    }

    private PortableCopyPanel createPortablePanel() {
        if (!context.capabilities().createPortableCopy()) {
            return null;
        }
        return new PortableCopyPanel(
                () -> new PortableCopyService(
                        (AppPaths) context.paths(),
                        InstalledDistribution.fromRunningApplication(RingLog.class),
                        context.mutationCoordinator()
                ),
                () -> navigate(SETTINGS),
                this::setPortableCreationBusy
        );
    }

    private void initializeLayout() {
        setLayout(new BorderLayout());
        contentPanel.add(homePanel, HOME);
        contentPanel.add(captureListPanel, CAPTURES);
        contentPanel.add(captureDetailPanel, CAPTURE_DETAIL);
        if (capturePanel != null) {
            contentPanel.add(capturePanel, NEW_CAPTURE);
        }
        contentPanel.add(speciesPanel, SPECIES);
        contentPanel.add(placePanel, PLACES);
        contentPanel.add(reportsPanel, REPORTS);
        contentPanel.add(conflictsPanel, CONFLICTS);
        contentPanel.add(settingsPanel, SETTINGS);
        if (portableCopyPanel != null) {
            contentPanel.add(portableCopyPanel, PORTABLE_COPY);
        }

        add(sidebar, BorderLayout.WEST);
        JPanel center = new JPanel(new BorderLayout());
        center.add(contentPanel, BorderLayout.CENTER);
        if (context.portableReadOnly()) {
            JLabel banner = UiKit.valueLabel("Copia portátil · Solo lectura");
            banner.setName("portableReadOnlyBadge");
            banner.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, UiKit.borderColor()),
                    BorderFactory.createEmptyBorder(9, 18, 9, 18)
            ));
            banner.setBackground(UiKit.accentSoftColor());
            banner.setForeground(UiKit.accentColor());
            banner.setOpaque(true);
            center.add(banner, BorderLayout.NORTH);
        } else {
            portableCreationNotice.setName("portableCreationNotice");
            portableCreationNotice.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
            portableCreationNotice.setBackground(UiKit.accentSoftColor());
            portableCreationNotice.setForeground(UiKit.accentColor());
            portableCreationNotice.setOpaque(true);
            portableCreationNotice.setVisible(false);
            center.add(portableCreationNotice, BorderLayout.NORTH);
        }
        add(center, BorderLayout.CENTER);
        JMenuBar menuBar = createMenuBar();
        if (menuBar.getMenuCount() > 0) {
            setJMenuBar(menuBar);
        }
        if (updateOverlay != null) {
            setGlassPane(updateOverlay);
        }
        navigate(initialScreen());
    }

    public void startUpdateCheck() {
        if (updateCoordinator != null) {
            updateCoordinator.checkAutomatically();
        }
    }

    public void checkForUpdatesManually() {
        if (updateCoordinator != null) {
            updateCoordinator.checkManually();
        }
    }

    private void closeForUpdate() {
        dispose();
        System.exit(0);
    }

    public void navigate(String screen) {
        if ((NEW_CAPTURE.equals(screen) && !context.capabilities().mutateDiary())
                || (PORTABLE_COPY.equals(screen)
                && !context.capabilities().createPortableCopy())) {
            screen = HOME;
        }
        cardLayout.show(contentPanel, screen);
        sidebar.setActiveDestination(screen);
        switch (screen) {
            case HOME -> homePanel.refresh();
            case CAPTURES -> captureListPanel.refresh();
            case NEW_CAPTURE -> capturePanel.prepareNew();
            case SPECIES -> speciesPanel.refresh();
            case PLACES -> placePanel.refresh();
            case REPORTS -> reportsPanel.refresh();
            case CONFLICTS -> conflictsPanel.refresh();
            case PORTABLE_COPY -> portableCopyPanel.prepare();
            default -> {
                // Screens with their own refresh actions are updated when opened.
            }
        }
    }

    public void showEvent(long eventId) {
        captureDetailPanel.setEventId(eventId);
        navigate(CAPTURE_DETAIL);
    }

    private void editEvent(BirdEventDetail detail) {
        if (capturePanel == null || !mutationActionsEnabled()) {
            return;
        }
        capturePanel.edit(detail);
        cardLayout.show(contentPanel, NEW_CAPTURE);
        sidebar.setActiveDestination(CAPTURES);
    }

    private void refreshImportedData() {
        homePanel.refresh();
        captureListPanel.refresh();
        if (capturePanel != null) {
            capturePanel.refreshCatalogs();
        }
        speciesPanel.refresh();
        placePanel.refresh();
        reportsPanel.refresh();
        conflictsPanel.refresh();
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("Archivo");
        ApplicationMutationServices mutations = context.mutationServices().orElse(null);
        if (context.capabilities().importOrRestore() && mutations != null) {
            importMenuItem = new JMenuItem("Importar historial o copia…");
            importMenuItem.addActionListener(event -> settingsPanel.startImportChooser());
            file.add(importMenuItem);
        }
        if (context.capabilities().exportNativeBackup() && mutations != null) {
            exportMenuItem = new JMenuItem("Exportar copia completa…");
            exportMenuItem.addActionListener(event -> settingsPanel.startExportChooser());
            file.add(exportMenuItem);
        }
        if (context.capabilities().createPortableCopy()) {
            portableMenuItem = new JMenuItem("Crear copia portátil…");
            portableMenuItem.addActionListener(event -> navigate(PORTABLE_COPY));
            file.add(portableMenuItem);
        }
        if (file.getItemCount() > 0) {
            bar.add(file);
        }
        return bar;
    }

    private void setPortableCreationBusy(boolean active) {
        portableCreationBusy = active;
        applyMutationAvailability();
    }

    private boolean mutationActionsEnabled() {
        return !portableCreationBusy && !snapshotBusy;
    }

    private void applyMutationAvailability() {
        boolean enabled = mutationActionsEnabled();
        portableCreationNotice.setVisible(!enabled);
        sidebar.setMutationActionsEnabled(enabled);
        homePanel.setMutationActionsEnabled(enabled);
        captureListPanel.setMutationActionsEnabled(enabled);
        speciesPanel.setMutationActionsEnabled(enabled);
        placePanel.setMutationActionsEnabled(enabled);
        conflictsPanel.setMutationActionsEnabled(enabled);
        settingsPanel.setMutationActionsEnabled(enabled);
        if (capturePanel != null) {
            capturePanel.setMutationActionsEnabled(enabled);
        }
        if (importMenuItem != null) {
            importMenuItem.setEnabled(enabled);
        }
        if (exportMenuItem != null) {
            exportMenuItem.setEnabled(enabled);
        }
        if (portableMenuItem != null) {
            portableMenuItem.setEnabled(enabled);
        }
    }
}
