package com.adelylria.ringlog.ui.components;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.swing.JButton;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import com.adelylria.ringlog.ui.theme.UiKit;

/** Compact interactive OpenStreetMap preview for one recorded location. */
public final class LocationMapPanel extends JPanel {

    private static final int MAP_HEIGHT = 190;
    private static final int TILE_SIZE = 256;
    private static final int MIN_ZOOM = 3;
    private static final int MAX_ZOOM = 18;
    private static final int MAX_CONCURRENT_TILE_REQUESTS = 12;
    private static final URI ATTRIBUTION_URI = URI.create(
            "https://www.openstreetmap.org/copyright"
    );
    private static final ExecutorService TILE_EXECUTOR = Executors.newFixedThreadPool(
            3,
            new DaemonThreadFactory()
    );

    private final double latitude;
    private final double longitude;
    private final OpenStreetMapTileStore tileStore;

    public LocationMapPanel(double latitude, double longitude, Path cacheDirectory) {
        if (!validCoordinates(latitude, longitude)) {
            throw new IllegalArgumentException("Las coordenadas del mapa no son válidas.");
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.tileStore = new OpenStreetMapTileStore(cacheDirectory);
        initialize();
    }

    public static boolean validCoordinates(Double latitude, Double longitude) {
        return latitude != null
                && longitude != null
                && Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -85.05112878
                && latitude <= 85.05112878
                && longitude >= -180d
                && longitude <= 180d;
    }

    private void initialize() {
        setName("locationMap");
        setLayout(new BorderLayout(0, 12));
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, MAP_HEIGHT));

        showMap();
    }

    private void showMap() {
        MapCanvas canvas = new MapCanvas(latitude, longitude, tileStore);
        JPanel mapView = new JPanel(new BorderLayout());
        mapView.setName("loadedLocationMap");
        mapView.setOpaque(false);
        JButton attribution = linkButton("© OpenStreetMap contributors");
        attribution.setName("openStreetMapAttribution");
        attribution.addActionListener(event -> browse(ATTRIBUTION_URI));
        mapView.add(new MapLayer(canvas, attribution), BorderLayout.CENTER);
        add(mapView, BorderLayout.CENTER);
        canvas.startLoading();
    }

    private static JButton linkButton(String text) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBorder(new EmptyBorder(3, 6, 3, 6));
        button.setBackground(new Color(250, 250, 247, 220));
        button.setForeground(new Color(55, 62, 58));
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 10f));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static void browse(URI uri) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (IOException | RuntimeException ignored) {
            // The embedded detail remains usable even if Windows cannot open a browser.
        }
    }

    private static final class MapCanvas extends JPanel {

        private final double latitude;
        private final double longitude;
        private final OpenStreetMapTileStore tileStore;
        private final Map<TileKey, BufferedImage> images = new ConcurrentHashMap<>();
        private final Set<TileKey> loading = ConcurrentHashMap.newKeySet();
        private final Set<TileKey> failures = ConcurrentHashMap.newKeySet();
        private String overlayMessage = "Cargando mapa…";
        private int zoom = 15;
        private double centerWorldX;
        private double centerWorldY;
        private Point dragOrigin;
        private double dragCenterWorldX;
        private double dragCenterWorldY;

        private MapCanvas(
                double latitude,
                double longitude,
                OpenStreetMapTileStore tileStore
        ) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.tileStore = tileStore;
            setName("openStreetMapCanvas");
            setPreferredSize(new Dimension(720, MAP_HEIGHT));
            setMinimumSize(new Dimension(260, 150));
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Arrastra para mover el mapa y usa la rueda para cambiar la escala");
            centerWorldX = longitudeToWorldX(longitude, zoom);
            centerWorldY = latitudeToWorldY(latitude, zoom);
            installMouseNavigation();
        }

        private void startLoading() {
            repaint();
        }

        private void changeZoom(int delta, Point anchor) {
            int newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + delta));
            if (newZoom == zoom) {
                return;
            }
            double scale = Math.scalb(1d, newZoom - zoom);
            double offsetX = anchor.x - getWidth() / 2d;
            double offsetY = anchor.y - getHeight() / 2d;
            centerWorldX = (centerWorldX + offsetX) * scale - offsetX;
            centerWorldY = (centerWorldY + offsetY) * scale - offsetY;
            zoom = newZoom;
            normalizeCenter();
            failures.clear();
            repaint();
        }

        private void installMouseNavigation() {
            MouseAdapter navigation = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    if (!SwingUtilities.isLeftMouseButton(event)) {
                        return;
                    }
                    dragOrigin = event.getPoint();
                    dragCenterWorldX = centerWorldX;
                    dragCenterWorldY = centerWorldY;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    if (dragOrigin == null) {
                        return;
                    }
                    centerWorldX = dragCenterWorldX - (event.getX() - dragOrigin.x);
                    centerWorldY = dragCenterWorldY - (event.getY() - dragOrigin.y);
                    normalizeCenter();
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    dragOrigin = null;
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }

                @Override
                public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)
                            && event.getClickCount() == 2) {
                        changeZoom(1, event.getPoint());
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent event) {
                    changeZoom(-event.getWheelRotation(), event.getPoint());
                }
            };
            addMouseListener(navigation);
            addMouseMotionListener(navigation);
            addMouseWheelListener(navigation);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );
                Shape oldClip = g.getClip();
                RoundRectangle2D clip = new RoundRectangle2D.Double(
                        0,
                        0,
                        Math.max(0, getWidth() - 1),
                        Math.max(0, getHeight() - 1),
                        18,
                        18
                );
                g.clip(clip);
                g.setColor(UiKit.surfaceRaisedColor());
                g.fill(clip);
                paintTiles(g);
                paintMarker(g);
                paintOverlayMessage(g);
                g.setClip(oldClip);
                g.setColor(UiKit.borderColor());
                g.draw(clip);
            } finally {
                g.dispose();
            }
        }

        private void paintTiles(Graphics2D g) {
            double left = centerWorldX - getWidth() / 2d;
            double top = centerWorldY - getHeight() / 2d;
            int firstX = (int) Math.floor(left / TILE_SIZE);
            int lastX = (int) Math.floor((left + getWidth()) / TILE_SIZE);
            int firstY = (int) Math.floor(top / TILE_SIZE);
            int lastY = (int) Math.floor((top + getHeight()) / TILE_SIZE);
            int tileLimit = 1 << zoom;

            for (int tileY = firstY; tileY <= lastY; tileY++) {
                if (tileY < 0 || tileY >= tileLimit) {
                    continue;
                }
                for (int tileX = firstX; tileX <= lastX; tileX++) {
                    int wrappedX = Math.floorMod(tileX, tileLimit);
                    TileKey key = new TileKey(zoom, wrappedX, tileY);
                    int drawX = (int) Math.round(tileX * TILE_SIZE - left);
                    int drawY = (int) Math.round(tileY * TILE_SIZE - top);
                    BufferedImage image = images.get(key);
                    if (image == null) {
                        paintTilePlaceholder(g, drawX, drawY);
                        request(key);
                    } else {
                        g.drawImage(image, drawX, drawY, TILE_SIZE, TILE_SIZE, null);
                    }
                }
            }
        }

        private void request(TileKey key) {
            if (failures.contains(key)
                    || loading.contains(key)
                    || loading.size() >= MAX_CONCURRENT_TILE_REQUESTS
                    || !loading.add(key)) {
                return;
            }
            TILE_EXECUTOR.execute(() -> {
                try {
                    BufferedImage image = tileStore.load(key.zoom(), key.x(), key.y());
                    images.put(key, image);
                    SwingUtilities.invokeLater(() -> {
                        overlayMessage = null;
                        repaint();
                    });
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    markFailure(key);
                } catch (IOException | RuntimeException exception) {
                    markFailure(key);
                } finally {
                    loading.remove(key);
                }
            });
        }

        private void markFailure(TileKey key) {
            failures.add(key);
            SwingUtilities.invokeLater(() -> {
                if (images.isEmpty()) {
                    overlayMessage = "Mapa no disponible sin conexión";
                }
                repaint();
            });
        }

        private static void paintTilePlaceholder(Graphics2D g, int x, int y) {
            g.setColor(UiKit.surfaceRaisedColor());
            g.fillRect(x, y, TILE_SIZE, TILE_SIZE);
            g.setColor(UiKit.borderColor());
            g.drawRect(x, y, TILE_SIZE, TILE_SIZE);
        }

        private void paintMarker(Graphics2D g) {
            double worldSize = (1 << zoom) * (double) TILE_SIZE;
            double markerOffsetX = longitudeToWorldX(longitude, zoom) - centerWorldX;
            if (markerOffsetX > worldSize / 2d) {
                markerOffsetX -= worldSize;
            } else if (markerOffsetX < -worldSize / 2d) {
                markerOffsetX += worldSize;
            }
            int centerX = (int) Math.round(getWidth() / 2d + markerOffsetX);
            int centerY = (int) Math.round(
                    getHeight() / 2d + latitudeToWorldY(latitude, zoom) - centerWorldY
            );
            if (centerX < -24 || centerX > getWidth() + 24
                    || centerY < -24 || centerY > getHeight() + 24) {
                return;
            }
            g.setColor(new Color(0, 0, 0, 70));
            g.fill(new Ellipse2D.Double(centerX - 13, centerY - 7, 26, 12));
            g.setStroke(new BasicStroke(3f));
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(centerX - 10, centerY - 22, 20, 20));
            g.setColor(UiKit.accentColor());
            g.draw(new Ellipse2D.Double(centerX - 10, centerY - 22, 20, 20));
            g.fill(new Ellipse2D.Double(centerX - 4, centerY - 16, 8, 8));
            g.drawLine(centerX, centerY - 2, centerX, centerY + 7);
        }

        private void paintOverlayMessage(Graphics2D g) {
            if (overlayMessage == null) {
                return;
            }
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            int textWidth = g.getFontMetrics().stringWidth(overlayMessage);
            int x = Math.max(10, (getWidth() - textWidth) / 2);
            int y = getHeight() / 2;
            g.setColor(new Color(250, 250, 247, 220));
            g.fillRoundRect(x - 9, y - 17, textWidth + 18, 28, 12, 12);
            g.setColor(new Color(55, 62, 58));
            g.drawString(overlayMessage, x, y + 2);
        }

        private void normalizeCenter() {
            double worldSize = (1 << zoom) * (double) TILE_SIZE;
            centerWorldX %= worldSize;
            if (centerWorldX < 0d) {
                centerWorldX += worldSize;
            }
            double halfViewport = Math.min(worldSize / 2d, getHeight() / 2d);
            centerWorldY = Math.max(
                    halfViewport,
                    Math.min(worldSize - halfViewport, centerWorldY)
            );
        }

        private static double longitudeToWorldX(double longitude, int zoom) {
            return (longitude + 180d) / 360d * (1 << zoom) * TILE_SIZE;
        }

        private static double latitudeToWorldY(double latitude, int zoom) {
            double radians = Math.toRadians(latitude);
            double mercator = Math.log(Math.tan(radians) + 1d / Math.cos(radians));
            return (1d - mercator / Math.PI) / 2d * (1 << zoom) * TILE_SIZE;
        }
    }

    private record TileKey(int zoom, int x, int y) {
    }

    private static final class MapLayer extends JLayeredPane {

        private final JPanel canvas;
        private final JButton attribution;

        private MapLayer(JPanel canvas, JButton attribution) {
            this.canvas = canvas;
            this.attribution = attribution;
            setOpaque(false);
            setPreferredSize(new Dimension(720, MAP_HEIGHT));
            add(canvas, JLayeredPane.DEFAULT_LAYER);
            add(attribution, JLayeredPane.PALETTE_LAYER);
        }

        @Override
        public void doLayout() {
            canvas.setBounds(0, 0, getWidth(), getHeight());
            Dimension size = attribution.getPreferredSize();
            attribution.setBounds(
                    Math.max(6, getWidth() - size.width - 7),
                    Math.max(6, getHeight() - size.height - 7),
                    size.width,
                    size.height
            );
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {

        private int index;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ringlog-map-tile-" + index++);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    }
}
