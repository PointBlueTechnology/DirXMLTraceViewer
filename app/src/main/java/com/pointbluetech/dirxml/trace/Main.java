package com.pointbluetech.dirxml.trace;

import com.formdev.flatlaf.FlatDarkLaf;
import com.pointbluetech.dirxml.trace.ui.MainFrame;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Taskbar;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point. With no arguments the Connect dialog opens; {@code --open FILE} shows a trace
 * file, {@code --connect … --bind-dn …} streams from a vault right away (the password from
 * {@code DIRXML_TRACE_VIEWER_PASSWORD} or {@code --password-stdin}), {@code --demo} streams
 * synthetic trace. See {@link StartupOptions#USAGE}.
 */
public final class Main {

    private static final boolean DEBUG = Boolean.getBoolean("dirxml.debug");

    private Main() {
    }

    public static void main(String[] args) {
        StartupOptions options;
        try {
            options = StartupOptions.parse(args, System.getenv(), StartupOptions::readStdinLine);
        } catch (IllegalArgumentException e) {
            boolean help = Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h");
            (help ? System.out : System.err).println(e.getMessage());
            System.exit(help ? 0 : 2);
            return;
        }
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "DirXML Trace Viewer");
        SwingUtilities.invokeLater(() -> {
            FlatDarkLaf.setup();
            // Roomier toolbar than FlatLaf's compact default.
            UIManager.put("ToolBar.buttonMargins", new Insets(5, 9, 5, 9));
            // Smallest first; setTaskbarIcon uses the last image that loaded.
            List<Image> icons = loadAppIcons(
                    "/icons/app-16.png",
                    "/icons/app-32.png",
                    "/icons/app-64.png",
                    "/icons/app-128.png",
                    "/icons/app-256.png",
                    "/icons/app-512.png");
            setTaskbarIcon(icons);
            MainFrame frame = new MainFrame(options);
            if (!icons.isEmpty()) {
                frame.setIconImages(icons);
            }
            frame.setVisible(true);
            frame.startup();
        });
    }

    /**
     * Loads icon resolutions from the classpath. A missing file, a read failure, or an
     * {@link ImageIO#read(InputStream)} result of null is skipped, so a null image never reaches
     * {@code setIconImages} or the taskbar. Failures are logged when {@code -Ddirxml.debug=true}.
     */
    private static List<Image> loadAppIcons(String... resourcePaths) {
        List<Image> icons = new ArrayList<>();
        for (String path : resourcePaths) {
            try (InputStream is = Main.class.getResourceAsStream(path)) {
                if (is == null) {
                    if (DEBUG) System.err.println("[dirxml.debug] icon not found: " + path);
                    continue;
                }
                Image image = ImageIO.read(is);
                if (image == null) {
                    if (DEBUG) System.err.println("[dirxml.debug] icon not decoded: " + path);
                    continue;
                }
                icons.add(image);
            } catch (Exception e) {
                if (DEBUG) System.err.println("[dirxml.debug] icon " + path + " failed: " + e);
            }
        }
        return icons;
    }

    /**
     * Sets the taskbar or Dock icon wherever {@link Taskbar} supports
     * {@link Taskbar.Feature#ICON_IMAGE}, using the last (highest-resolution) image.
     */
    private static void setTaskbarIcon(List<Image> icons) {
        if (icons.isEmpty()) {
            return;
        }
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(icons.get(icons.size() - 1));
                }
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            if (DEBUG) System.err.println("[dirxml.debug] taskbar icon not set: " + e);
        }
    }
}
