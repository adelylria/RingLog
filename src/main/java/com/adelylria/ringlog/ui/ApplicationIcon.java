package com.adelylria.ringlog.ui;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JFrame;

/** Provides RingLog's visual identity to every top-level application window. */
public final class ApplicationIcon {

    static final String RESOURCE = "/icons/ringlog.png";
    private static final int[] WINDOWS_ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
    private static final List<Image> ICONS = loadIcons();

    private ApplicationIcon() {
    }

    public static void applyTo(JFrame frame) {
        if (!ICONS.isEmpty()) {
            frame.setIconImages(ICONS);
        }
    }

    static List<Image> images() {
        return ICONS;
    }

    private static List<Image> loadIcons() {
        try {
            BufferedImage source = ImageIO.read(ApplicationIcon.class.getResource(RESOURCE));
            if (source == null) {
                throw new IOException("Unsupported application icon resource");
            }
            List<Image> icons = new ArrayList<>(WINDOWS_ICON_SIZES.length);
            for (int size : WINDOWS_ICON_SIZES) {
                icons.add(scale(source, size));
            }
            return List.copyOf(icons);
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("RingLog could not load its application icon: "
                    + exception.getMessage());
            return List.of();
        }
    }

    private static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            graphics.drawImage(source, 0, 0, size, size, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }
}
