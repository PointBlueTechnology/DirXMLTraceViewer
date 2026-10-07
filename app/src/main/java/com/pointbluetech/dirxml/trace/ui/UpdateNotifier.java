package com.pointbluetech.dirxml.trace.ui;

import com.pointbluetech.dirxml.trace.update.UpdateChecker;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Desktop;
import java.net.URI;
import java.time.Duration;
import java.util.prefs.Preferences;

/**
 * Tells the user about new releases on GitHub: a background check at most once a day (if enabled),
 * and Help → Check for Updates… on demand. It only notifies; downloading is left to the browser.
 */
final class UpdateNotifier {

    private static final String PREF_ENABLED = "updateCheck.enabled";
    private static final String PREF_LAST = "updateCheck.lastCheck";
    private static final String PREF_SKIPPED = "updateCheck.skippedVersion";
    private static final Duration INTERVAL = Duration.ofDays(1);

    private final JFrame owner;
    private final Preferences prefs = Preferences.userNodeForPackage(UpdateNotifier.class);
    /** Version of the running build (from the jar manifest), or null when run from an IDE. */
    private final String currentVersion = MainFrame.class.getPackage().getImplementationVersion();

    UpdateNotifier(JFrame owner) {
        this.owner = owner;
    }

    String currentVersion() {
        return currentVersion;
    }

    boolean automaticChecksEnabled() {
        return prefs.getBoolean(PREF_ENABLED, true);
    }

    void setAutomaticChecksEnabled(boolean enabled) {
        prefs.putBoolean(PREF_ENABLED, enabled);
    }

    /** Called at startup: checks in the background, a few seconds in, if due. Silent unless there is news. */
    void checkAutomaticallyIfDue() {
        long last = prefs.getLong(PREF_LAST, 0);
        if (!automaticChecksEnabled() || currentVersion == null
                || System.currentTimeMillis() - last < INTERVAL.toMillis()) {
            return;
        }
        Timer t = new Timer(3_000, e -> check(false));
        t.setRepeats(false);
        t.start();
    }

    /** Help → Check for Updates…: always reports the outcome. */
    void checkNow() {
        check(true);
    }

    private void check(boolean interactive) {
        Thread worker = new Thread(() -> {
            try {
                UpdateChecker.Release latest = UpdateChecker.latest(currentVersion);
                prefs.putLong(PREF_LAST, System.currentTimeMillis());
                SwingUtilities.invokeLater(() -> report(latest, interactive));
            } catch (Exception e) {
                if (interactive) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(owner,
                            "Could not check for updates:\n" + e.getMessage()
                                    + "\n\nReleases are at " + UpdateChecker.RELEASES_PAGE,
                            "Check for Updates", JOptionPane.WARNING_MESSAGE));
                }
                // Automatic checks fail silently (offline, proxy, rate limit) and try again next start.
            }
        }, "update-check");
        worker.setDaemon(true);
        worker.start();
    }

    private void report(UpdateChecker.Release latest, boolean interactive) {
        String running = currentVersion == null ? "a development build" : currentVersion;
        boolean newer = currentVersion != null && UpdateChecker.isNewer(latest.version(), currentVersion);
        if (!newer) {
            if (interactive) {
                JOptionPane.showMessageDialog(owner, "You have the latest version (" + running + ").",
                        "Check for Updates", JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }
        if (!interactive && latest.version().equals(prefs.get(PREF_SKIPPED, null))) {
            return;
        }
        Object[] options = interactive ? new Object[]{"Download", "Later"} : new Object[]{"Download", "Skip This Version", "Later"};
        int choice = JOptionPane.showOptionDialog(owner,
                new JLabel("<html><b>DirXML Trace Viewer " + latest.version() + " is available.</b><br>"
                        + "You have " + running + ".<br><br>Download opens the release page, where you can "
                        + "see what's new and get the Mac app or the zip.</html>"),
                "Update Available", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            browse(latest.pageUrl());
        } else if (!interactive && choice == 1) {
            prefs.put(PREF_SKIPPED, latest.version());
        }
    }

    void showAbout() {
        String version = currentVersion == null ? "development build" : currentVersion;
        Object[] options = {"Project Page", "OK"};
        int choice = JOptionPane.showOptionDialog(owner,
                new JLabel("<html><b>DirXML Trace Viewer</b> " + version + "<br><br>"
                        + "Viewer for NetIQ / OpenText Identity Manager driver trace.<br>"
                        + "Copyright © 2026 Point Blue Technology. MIT License.<br><br>"
                        + "Java " + System.getProperty("java.version") + "</html>"),
                "About DirXML Trace Viewer", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, options, options[1]);
        if (choice == 0) {
            browse("https://github.com/PointBlueTechnology/DirXMLTraceViewer");
        }
    }

    private void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through to showing the link
        }
        JOptionPane.showMessageDialog(owner, "Open this page in your browser:\n" + url, "DirXML Trace Viewer",
                JOptionPane.INFORMATION_MESSAGE);
    }
}
