package com.pointbluetech.dirxml.trace.ui;

import com.pointbluetech.dirxml.trace.ldap.AcceptedCertificates;
import com.pointbluetech.dirxml.trace.ldap.CertificateTrust;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** The "accept this certificate?" prompt and the Accepted Certificates manager. */
final class CertificateDialogs {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private CertificateDialogs() {
    }

    /** A {@link CertificateTrust.Prompt} that shows a modal dialog over {@code owner}. */
    static CertificateTrust.Prompt prompt(JFrame owner) {
        return request -> {
            AtomicReference<CertificateTrust.Decision> result = new AtomicReference<>(CertificateTrust.Decision.REJECT);
            Runnable ask = () -> result.set(ask(owner, request));
            if (SwingUtilities.isEventDispatchThread()) {
                ask.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(ask);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.lang.reflect.InvocationTargetException e) {
                    // leave REJECT
                }
            }
            return result.get();
        };
    }

    private static CertificateTrust.Decision ask(JFrame owner, CertificateTrust.Request r) {
        X509Certificate c = r.certificate();
        String server = AcceptedCertificates.serverKey(r.host(), r.port());
        StringBuilder html = new StringBuilder("<html><body style='width:520px'>");
        if (r.previous() != null) {
            html.append("<p><b><font color='#e06c75'>The certificate for ").append(esc(server))
                    .append(" has changed</font></b> since you accepted it on ")
                    .append(DATE.format(r.previous().acceptedAt()))
                    .append(". This is expected if the server's certificate was renewed or replaced; otherwise "
                            + "someone may be intercepting the connection.</p><br>");
        } else {
            html.append("<p><b>The certificate presented by ").append(esc(server))
                    .append(" is not trusted.</b></p><br>");
        }
        html.append("<p>").append(esc(reason(r, c))).append("</p>")
                .append("<p><font size='-2' color='#8a8f98'>").append(esc(r.problem())).append("</font></p><br>")
                .append("<table cellpadding='2'>")
                .append(row("Subject", c.getSubjectX500Principal().getName()))
                .append(row("Issuer", c.getIssuerX500Principal().getName()))
                .append(row("Valid", DATE.format(c.getNotBefore().toInstant()) + " to " + DATE.format(c.getNotAfter().toInstant())))
                .append(row("SHA-256", wrap(AcceptedCertificates.sha256(c))));
        if (r.previous() != null) {
            html.append(row("Previously", wrap(r.previous().sha256())));
        }
        html.append("</table><br><p>Only continue if you recognize this certificate, e.g. by comparing the "
                + "fingerprint with the one on the server.</p></body></html>");

        Object[] options = {"Trust and Remember", "Trust This Time", "Cancel"};
        int choice = JOptionPane.showOptionDialog(owner, new JLabel(html.toString()), "Untrusted Server Certificate",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
        return switch (choice) {
            case 0 -> CertificateTrust.Decision.REMEMBER;
            case 1 -> CertificateTrust.Decision.ONCE;
            default -> CertificateTrust.Decision.REJECT;
        };
    }

    /** A plain-language reason for the common cases; the technical message is shown as well. */
    private static String reason(CertificateTrust.Request r, X509Certificate c) {
        String p = r.problem() == null ? "" : r.problem().toLowerCase(java.util.Locale.ROOT);
        if (c.getNotAfter().before(new java.util.Date())) {
            return "It expired on " + DATE.format(c.getNotAfter().toInstant()) + ".";
        }
        if (c.getNotBefore().after(new java.util.Date())) {
            return "It is not valid until " + DATE.format(c.getNotBefore().toInstant()) + ".";
        }
        if (p.contains("subject alternative name") || p.contains("no name matching") || p.contains("hostname")) {
            return "It was issued for a different name than \"" + r.host() + "\".";
        }
        if (p.contains("unable to find valid certification path") || p.contains("pkix path")) {
            return c.getSubjectX500Principal().equals(c.getIssuerX500Principal())
                    ? "It is self-signed, so no certificate authority vouches for it."
                    : "It was issued by a certificate authority this computer doesn't trust, such as an "
                    + "eDirectory tree's own CA.";
        }
        return "The system could not verify it.";
    }

    /** File → Accepted Certificates…: lists remembered certificates and removes them. */
    static void manage(JFrame owner) {
        AcceptedCertificates store = CertificateTrust.store();
        JDialog d = new JDialog(owner, "Accepted Certificates", true);
        CertTableModel model = new CertTableModel(store.list());
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(380);

        JLabel note = new JLabel("<html>Certificates you chose to trust for servers the system doesn't trust. "
                + "Removing one means you'll be asked again on the next connection.</html>");
        note.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton remove = new JButton("Remove");
        JButton removeAll = new JButton("Remove All");
        JButton close = new JButton("Close");
        Runnable refresh = () -> {
            model.setEntries(store.list());
            remove.setEnabled(false);
            removeAll.setEnabled(model.getRowCount() > 0);
        };
        table.getSelectionModel().addListSelectionListener(e -> remove.setEnabled(table.getSelectedRowCount() > 0));
        remove.addActionListener(e -> {
            for (int row : table.getSelectedRows()) {
                store.remove(model.entry(table.convertRowIndexToModel(row)).server());
            }
            refresh.run();
        });
        removeAll.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(d, "Remove all " + model.getRowCount() + " accepted certificates?",
                    "Accepted Certificates", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                store.clear();
                CertificateTrust.clearSession();
                refresh.run();
            }
        });
        close.addActionListener(e -> d.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(remove);
        buttons.add(removeAll);
        buttons.add(close);
        d.getContentPane().add(note, BorderLayout.NORTH);
        d.getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(close);
        refresh.run();
        d.setSize(new Dimension(900, 320));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    private static final class CertTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Server", "Subject", "SHA-256 fingerprint", "Expires", "Accepted"};
        private List<AcceptedCertificates.Entry> entries;

        CertTableModel(List<AcceptedCertificates.Entry> entries) {
            this.entries = entries;
        }

        void setEntries(List<AcceptedCertificates.Entry> e) {
            entries = e;
            fireTableDataChanged();
        }

        AcceptedCertificates.Entry entry(int row) {
            return entries.get(row);
        }

        @Override public int getRowCount() { return entries.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int c) { return COLUMNS[c]; }

        @Override
        public Object getValueAt(int row, int col) {
            AcceptedCertificates.Entry e = entries.get(row);
            return switch (col) {
                case 0 -> e.server();
                case 1 -> e.subject();
                case 2 -> e.sha256();
                case 3 -> date(e.notAfter());
                default -> date(e.acceptedAt());
            };
        }

        private static String date(Instant i) {
            return i.toEpochMilli() == 0 ? "" : DATE.format(i);
        }
    }

    private static String row(String label, String value) {
        return "<tr><td valign='top'><b>" + label + "</b></td><td>" + (value.startsWith("<") ? value : esc(value)) + "</td></tr>";
    }

    /** Fingerprints on two lines, in a fixed-width font. */
    private static String wrap(String fingerprint) {
        int mid = fingerprint.length() / 2;
        int cut = fingerprint.indexOf(':', mid) + 1;
        return "<tt>" + esc(fingerprint.substring(0, cut)) + "<br>" + esc(fingerprint.substring(cut)) + "</tt>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
