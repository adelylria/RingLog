package com.adelylria.ringlog.ui;

import java.awt.Dimension;
import java.nio.file.Path;

import javax.swing.JFrame;

/** Window host for RingLog's integrated startup failure experience. */
public final class StartupErrorFrame extends JFrame {

    public StartupErrorFrame(Throwable failure, Path logFile) {
        setTitle("RingLog · Inicio seguro");
        ApplicationIcon.applyTo(this);
        setContentPane(new StartupErrorPanel(failure, logFile));
        setSize(980, 640);
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }
}
