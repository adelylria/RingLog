package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;
import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;

import com.adelylria.ringlog.media.PhotoImages;
import com.adelylria.ringlog.ui.theme.UiKit;

/** Compact, asynchronous, read-only gallery with an optional external add-photo action. */
public final class EventPhotoGallery extends JPanel {
    private final List<String> photos;
    private final PhotoCanvas canvas = new PhotoCanvas();
    private final JLabel counter = new PhotoCounter();
    private final JTextArea status = new JTextArea(2, 20);
    private final JPanel footer = new JPanel(new BorderLayout(0, 6));
    private final JButton previous = navigationButton(false);
    private final JButton next = navigationButton(true);
    private final JButton add;
    private JButton remove;
    private final boolean expanded;
    private final IntConsumer onOpen;
    private SwingWorker<PhotoImages.Preview, Void> loader;
    private int selected;
    private long generation;

    public EventPhotoGallery(List<String> photos, Runnable onAdd) {
        this(photos, onAdd, null);
    }

    public EventPhotoGallery(List<String> photos, Runnable onAdd, IntConsumer onOpen) {
        this(photos, onAdd, onOpen, false, 0);
    }

    public static EventPhotoGallery expanded(List<String> photos, int selected) {
        return new EventPhotoGallery(photos, null, null, true, selected);
    }

    private EventPhotoGallery(List<String> photos, Runnable onAdd, IntConsumer onOpen,
            boolean expanded, int initialIndex) {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        setName("eventPhotoGallery");
        this.photos = List.copyOf(photos);
        this.expanded = expanded;
        this.onOpen = onOpen;
        counter.setName("photoCounter");
        previous.setName("previousEventPhoto");
        next.setName("nextEventPhoto");
        previous.setToolTipText("Foto anterior");
        next.setToolTipText("Foto siguiente");
        previous.getAccessibleContext().setAccessibleName("Foto anterior");
        next.getAccessibleContext().setAccessibleName("Foto siguiente");
        previous.addActionListener(event -> select(selected - 1));
        next.addActionListener(event -> select(selected + 1));
        canvas.add(previous);
        canvas.add(next);
        canvas.add(counter);
        footer.setName("photoGalleryFooter");
        footer.setOpaque(false);
        footer.setVisible(false);
        status.setName("photoSaveStatus");
        status.setOpaque(false);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setFont(counter.getFont());
        status.setVisible(false);
        footer.add(status, BorderLayout.SOUTH);
        if (onAdd != null) {
            add = UiKit.primaryButton("");
            add.setIcon(new NavigationIcon(NavigationIcon.Kind.ADD));
            add.setMargin(new Insets(0, 0, 0, 0));
            add.putClientProperty("JButton.minimumWidth", 0);
            add.setPreferredSize(new Dimension(34, 34));
            add.setFocusPainted(true);
            add.setName("addEventPhoto");
            add.getAccessibleContext().setAccessibleName("Añadir foto");
            add.setToolTipText("Añadir una imagen JPG o PNG a este registro");
            add.addActionListener(event -> onAdd.run());
            canvas.add(add);
        } else {
            add = null;
        }
        add(canvas, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        bind(canvas, JComponent.WHEN_FOCUSED, "ENTER", "openPhoto", this::openPhoto);
        bind(canvas, JComponent.WHEN_FOCUSED, "SPACE", "openPhoto", this::openPhoto);
        bind(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, "LEFT", "previousPhoto",
                () -> { if (selected > 0) select(selected - 1); });
        bind(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, "RIGHT", "nextPhoto",
                () -> { if (selected < photos.size() - 1) select(selected + 1); });
        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1 && event.getClickCount() == 1) openPhoto();
            }
        });
        select(initialIndex);
    }

    public int selectedIndex() { return selected; }

    public void showPhoto(int index) {
        if (index != selected) select(index);
    }

    public void focusImage() { canvas.requestFocusInWindow(); }

    private void openPhoto() {
        if (onOpen != null && canvas.image != null) onOpen.accept(selected);
    }

    public void setMutationActionsEnabled(boolean enabled) {
        if (add != null) add.setEnabled(enabled);
        if (remove != null) remove.setEnabled(enabled);
    }

    /** Only the record editor supplies this action; detail and expanded views remain read-only. */
    public void setRemoveAction(Runnable onRemove) {
        if (remove != null) canvas.remove(remove);
        remove = UiKit.secondaryButton("");
        remove.setIcon(new NavigationIcon(NavigationIcon.Kind.DELETE));
        remove.setBackground(new Color(43, 53, 47));
        remove.setForeground(Color.WHITE);
        remove.setMargin(new Insets(0, 0, 0, 0));
        remove.putClientProperty("JButton.minimumWidth", 0);
        remove.setPreferredSize(new Dimension(34, 34));
        remove.setName("removeEventPhoto");
        remove.setToolTipText("Eliminar esta foto del registro");
        remove.getAccessibleContext().setAccessibleName("Eliminar esta foto del registro");
        remove.addActionListener(event -> onRemove.run());
        remove.setVisible(!photos.isEmpty());
        canvas.add(remove);
        canvas.revalidate();
    }

    public void setStatus(String text) {
        status.setText(text);
        status.setToolTipText(text);
        status.setVisible(text != null && !text.isBlank());
        footer.setVisible(status.isVisible());
        revalidate();
    }

    public void showLastPhoto() {
        select(photos.size() - 1);
    }

    public void dispose() {
        generation++;
        if (loader != null) loader.cancel(true);
    }

    private void select(int index) {
        if (loader != null) loader.cancel(true);
        long request = ++generation;
        selected = Math.max(0, Math.min(index, photos.size() - 1));
        previous.setVisible(photos.size() > 1);
        next.setVisible(photos.size() > 1);
        previous.setEnabled(selected > 0);
        next.setEnabled(selected < photos.size() - 1);
        counter.setVisible(photos.size() > 1);
        counter.setText(photos.isEmpty() ? "Sin fotografías" : (selected + 1) + " / " + photos.size());
        counter.getAccessibleContext().setAccessibleName("Fotografía " + (selected + 1) + " de " + photos.size());
        canvas.revalidate();
        canvas.image = null;
        canvas.setState(photos.isEmpty() ? "empty" : "loading");
        if (photos.isEmpty()) return;
        String file = photos.get(selected);
        loader = new SwingWorker<>() {
            @Override
            protected PhotoImages.Preview doInBackground() throws Exception {
                return PhotoImages.load(Path.of(file), expanded ? 2400 : 1200, expanded ? 1800 : 900);
            }

            @Override
            protected void done() {
                if (request != generation) return;
                try {
                    canvas.image = get().image();
                    canvas.setState("loaded");
                } catch (CancellationException ignored) {
                    // Navigation superseded this preview.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    canvas.setState("missing");
                } catch (ExecutionException failure) {
                    canvas.setState("missing");
                }
            }
        };
        loader.execute();
    }

    private static void bind(JComponent target, int condition, String key, String name, Runnable action) {
        target.getInputMap(condition).put(KeyStroke.getKeyStroke(key), name);
        target.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    private static JButton navigationButton(boolean forward) {
        JButton button = UiKit.secondaryButton("");
        button.setIcon(new Icon() {
            public int getIconWidth() { return 16; }
            public int getIconHeight() { return 16; }
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.translate(x, y);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(component.isEnabled() ? Color.WHITE : new Color(150, 158, 153));
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int tip = forward ? 10 : 6;
                    int end = forward ? 6 : 10;
                    g.drawLine(end, 3, tip, 8);
                    g.drawLine(tip, 8, end, 13);
                } finally { g.dispose(); }
            }
        });
        button.setBackground(new Color(43, 53, 47));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(true);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.putClientProperty("JButton.minimumWidth", 0);
        button.setPreferredSize(new Dimension(36, 36));
        return button;
    }

    private static final class PhotoCounter extends JLabel {
        private PhotoCounter() {
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(12f));
            setBorder(new javax.swing.border.EmptyBorder(4, 10, 4, 10));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(25, 33, 28, 205));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private final class PhotoCanvas extends JPanel {
        private BufferedImage image;
        private String message;

        private PhotoCanvas() {
            super(null);
            setOpaque(false);
            setName("eventPhotoPreview");
            setPreferredSize(new Dimension(320, 210));
            setMinimumSize(new Dimension(100, 160));
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) { repaint(); }
                @Override public void focusLost(FocusEvent event) { repaint(); }
            });
        }

        @Override public void doLayout() {
            int middle = (getHeight() - 36) / 2;
            previous.setBounds(12, middle, 36, 36);
            next.setBounds(getWidth() - 48, middle, 36, 36);
            Dimension badge = counter.getPreferredSize();
            counter.setBounds((getWidth() - badge.width) / 2, getHeight() - badge.height - 10,
                    badge.width, badge.height);
            if (add != null) add.setBounds(getWidth() - 44, 10, 34, 34);
            if (remove != null) remove.setBounds(getWidth() - (add == null ? 44 : 84), 10, 34, 34);
        }

        private void setState(String state) {
            putClientProperty("photoState", state);
            message = switch (state) {
                case "empty" -> "Sin fotografías";
                case "loading" -> "Cargando fotografía…";
                case "missing" -> "Fotografía no disponible";
                default -> null;
            };
            boolean clickable = "loaded".equals(state) && onOpen != null;
            setCursor(Cursor.getPredefinedCursor(clickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            setToolTipText(clickable ? "Ampliar fotografía" : null);
            setFocusable(clickable || expanded);
            getAccessibleContext().setAccessibleName(message == null
                    ? (clickable ? "Ampliar fotografía" : "Fotografía del ave") : message);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(expanded ? UiKit.backgroundColor() : UiKit.surfaceRaisedColor());
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                // Keep the scroll viewport's clip: the photo must not paint over the fixed Save bar.
                g.clip(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 16, 16));
                if (image != null) {
                    double scaleX = (double) getWidth() / image.getWidth();
                    double scaleY = (double) getHeight() / image.getHeight();
                    // Cover only the thumbnail. The viewer always contains the whole original image.
                    double scale = expanded ? Math.min(scaleX, scaleY) : Math.max(scaleX, scaleY);
                    int width = Math.max(1, (int) Math.ceil(image.getWidth() * scale));
                    int height = Math.max(1, (int) Math.ceil(image.getHeight() * scale));
                    int x = (getWidth() - width) / 2;
                    int y = (getHeight() - height) / 2;
                    Shape frameClip = g.getClip();
                    if (expanded) g.clip(new RoundRectangle2D.Double(x, y, width, height, 12, 12));
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(image, x, y, width, height, null);
                    g.setClip(frameClip);
                } else if (message != null) {
                    g.setColor(UiKit.mutedColor());
                    int x = (getWidth() - 40) / 2;
                    int y = getHeight() / 2 - 35;
                    g.drawRoundRect(x, y, 40, 28, 6, 6);
                    g.drawOval(x + 13, y + 6, 14, 14);
                    var metrics = g.getFontMetrics();
                    g.drawString(message, Math.max(6, (getWidth() - metrics.stringWidth(message)) / 2), y + 53);
                }
                if (isFocusOwner()) {
                    g.setColor(UiKit.accentColor());
                    g.setStroke(new BasicStroke(2f));
                    g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 14, 14);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
