package com.pointbluetech.dirxml.trace.ui;

import com.pointbluetech.dirxml.trace.ldap.ConnectionSettings;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.prefs.Preferences;

/** Collects LDAP connection details. Everything except the password is remembered. */
final class ConnectDialog extends JDialog {

    private static final String PREF_LEGACY_CIPHERS = "legacyCiphers";

    /** Whether the user last chose legacy RSA ciphers; applied at startup, before TLS initializes. */
    static boolean legacyCiphersRemembered() {
        return Preferences.userNodeForPackage(ConnectDialog.class).getBoolean(PREF_LEGACY_CIPHERS, true);
    }

    private final Preferences prefs = Preferences.userNodeForPackage(ConnectDialog.class);
    private final JTextField host = new JTextField(prefs.get("host", ""), 24);
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(prefs.getInt("port", 636), 1, 65535, 1));
    private final JCheckBox ssl = new JCheckBox("Use LDAPS (SSL/TLS)", prefs.getBoolean("ssl", true));
    private final JCheckBox trustAll = new JCheckBox("Trust any server certificate", prefs.getBoolean("trustAll", true));
    private final JCheckBox legacyCiphers = new JCheckBox("Allow legacy RSA ciphers",
            prefs.getBoolean(PREF_LEGACY_CIPHERS, true)); // most eDirectory LDAPS listeners offer only RSA key exchange
    private final JTextField bindDn = new JTextField(prefs.get("bindDn", "cn=admin,ou=sa,o=system"), 24);
    private final JPasswordField password = new JPasswordField(24);
    private final JTextField searchBase = new JTextField(prefs.get("searchBase", ""), 24);
    private ConnectionSettings result;

    ConnectDialog(JFrame owner) {
        super(owner, "Connect to Identity Vault", true);
        searchBase.setToolTipText("Where to look for driver sets, e.g. o=system. Leave blank to search the whole tree.");
        trustAll.setToolTipText("Skip certificate validation. Convenient for lab servers with self-signed certificates.");
        ssl.addActionListener(e -> {
            int p = (Integer) port.getValue();
            if (ssl.isSelected() && p == 389) port.setValue(636);
            if (!ssl.isSelected() && p == 636) port.setValue(389);
            trustAll.setEnabled(ssl.isSelected());
            legacyCiphers.setEnabled(ssl.isSelected());
        });
        trustAll.setEnabled(ssl.isSelected());
        legacyCiphers.setEnabled(ssl.isSelected());
        legacyCiphers.setToolTipText("<html>Re-enable TLS_RSA_* suites (no forward secrecy), which newer Java disables.<br>"
                + "Needed for eDirectory servers that offer only e.g. AES256-GCM-SHA384.<br>"
                + "Stays in effect until the viewer is restarted.</html>");

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        int row = 0;
        addRow(form, row++, "Host:", host);
        addRow(form, row++, "Port:", port);
        addRow(form, row++, "", ssl);
        addRow(form, row++, "", trustAll);
        addRow(form, row++, "", legacyCiphers);
        addRow(form, row++, "Bind DN:", bindDn);
        addRow(form, row++, "Password:", password);
        addRow(form, row, "Search base:", searchBase);

        JButton ok = new JButton("Connect");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> accept());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().setDefaultButton(ok);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
        if (!host.getText().isBlank()) {
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowOpened(java.awt.event.WindowEvent e) {
                    password.requestFocusInWindow();
                }
            });
        }
    }

    /** Shows the dialog; returns null if cancelled. */
    ConnectionSettings showDialog() {
        setVisible(true);
        return result;
    }

    private void accept() {
        if (host.getText().isBlank() || bindDn.getText().isBlank()) {
            JOptionPane.showMessageDialog(this, "Host and bind DN are required.", getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        prefs.put("host", host.getText().trim());
        prefs.putInt("port", (Integer) port.getValue());
        prefs.putBoolean("ssl", ssl.isSelected());
        prefs.putBoolean("trustAll", trustAll.isSelected());
        prefs.putBoolean(PREF_LEGACY_CIPHERS, legacyCiphers.isSelected());
        prefs.put("bindDn", bindDn.getText().trim());
        prefs.put("searchBase", searchBase.getText().trim());
        result = new ConnectionSettings(host.getText().trim(), (Integer) port.getValue(), ssl.isSelected(),
                trustAll.isSelected(), legacyCiphers.isSelected(), bindDn.getText().trim(), password.getPassword(), searchBase.getText().trim());
        dispose();
    }

    private static void addRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.LINE_END;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = field instanceof JCheckBox ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
    }
}
