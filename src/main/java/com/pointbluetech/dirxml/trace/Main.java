package com.pointbluetech.dirxml.trace;

import com.formdev.flatlaf.FlatDarkLaf;
import com.pointbluetech.dirxml.trace.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Insets;
import java.util.Arrays;

/** Entry point. Pass {@code --demo} to stream synthetic trace without an Identity Vault. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        boolean demo = Arrays.asList(args).contains("--demo");
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "DirXML Trace Viewer");
        SwingUtilities.invokeLater(() -> {
            FlatDarkLaf.setup();
            // Roomier toolbar than FlatLaf's compact default.
            UIManager.put("ToolBar.buttonMargins", new Insets(5, 9, 5, 9));
            MainFrame frame = new MainFrame(demo);
            frame.setVisible(true);
            frame.startup();
        });
    }
}
