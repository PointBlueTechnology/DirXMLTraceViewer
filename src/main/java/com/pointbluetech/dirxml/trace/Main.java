package com.pointbluetech.dirxml.trace;

import com.formdev.flatlaf.FlatDarkLaf;
import com.pointbluetech.dirxml.trace.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Insets;
import java.util.Arrays;

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
            MainFrame frame = new MainFrame(options);
            frame.setVisible(true);
            frame.startup();
        });
    }
}
