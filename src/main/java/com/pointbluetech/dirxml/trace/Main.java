package com.pointbluetech.dirxml.trace;

import java.awt.Image;
import java.awt.Insets;
import java.awt.Taskbar;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.pointbluetech.dirxml.trace.ui.MainFrame;

/**
 * Entry point. With no arguments the Connect dialog opens; {@code --open FILE} shows a trace
 * file, {@code --connect … --bind-dn …} streams from a vault right away (the password from
 * {@code DIRXML_TRACE_VIEWER_PASSWORD} or {@code --password-stdin}), {@code --demo} streams
 * synthetic trace. See {@link StartupOptions#USAGE}.
 */
public final class Main {

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

            // Load icons in standard resolutions
            List<Image> icons = loadAppIcons(
                "/icons/app-16.png",
                "/icons/app-32.png",
                "/icons/app-64.png",
                "/icons/app-128.png",
                "/icons/app-256.png",
                "/icons/app-512.png"
            );

            // Apply Dock icon for macOS
            setMacDockIcon(icons);

            MainFrame frame = new MainFrame(options);

            // Apply multi-resolution window icons for Windows & Linux
            if (!icons.isEmpty()) {
                frame.setIconImages(icons);
            }
            
            frame.setVisible(true);
            frame.startup();
        });
    }

    /**
     * Helper to safely load multiple icon resolutions from resources.
     */
    private static List<Image> loadAppIcons(String... resourcePaths) {
        List<Image> icons = new ArrayList<>();
        for (String path : resourcePaths) {
            try (InputStream is = Main.class.getResourceAsStream(path)) {
                if (is != null) {
                    icons.add(ImageIO.read(is));
                }
            } catch (Exception ignored) {
                // Skip missing or invalid resolutions
            }
        }
        return icons;
    }

    /**
     * Helper to set macOS Dock Icon using standard Java AWT Desktop/Taskbar API.
     */
    private static void setMacDockIcon(List<Image> icons) {
        if (icons.isEmpty()) return;

        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    // Use highest available resolution for macOS Retina Display
                    Image highestResIcon = icons.get(icons.size() - 1);
                    taskbar.setIconImage(highestResIcon);
                }
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Safe fallback if Taskbar API is not supported on current platform
        }
    }
}
