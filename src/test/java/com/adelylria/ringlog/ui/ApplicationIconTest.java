package com.adelylria.ringlog.ui;

import java.awt.Image;
import java.util.List;

import javax.swing.JFrame;

public final class ApplicationIconTest {

    private ApplicationIconTest() {
    }

    public static void iconResourceProvidesWindowsSizes() {
        List<Image> images = ApplicationIcon.images();
        require(images.size() >= 7, "RingLog should provide a multiresolution window icon");
        require(hasSize(images, 16) && hasSize(images, 32)
                        && hasSize(images, 48) && hasSize(images, 256),
                "The window icon must cover Windows' common display sizes");
    }

    public static void iconCanBeAppliedToAWindow() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        JFrame frame = new JFrame();
        try {
            ApplicationIcon.applyTo(frame);
            require(!frame.getIconImages().isEmpty(), "Top-level windows must receive the icon");
        } finally {
            frame.dispose();
        }
    }

    private static boolean hasSize(List<Image> images, int expected) {
        return images.stream().anyMatch(image -> image.getWidth(null) == expected
                && image.getHeight(null) == expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        iconResourceProvidesWindowsSizes();
        iconCanBeAppliedToAWindow();
        System.out.println("ApplicationIconTest: PASS");
    }
}
