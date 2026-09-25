package com.pointbluetech.dirxml.trace.ui;

import com.pointbluetech.dirxml.trace.ldap.CertificateTrust;
import com.pointbluetech.dirxml.trace.ldap.ConnectionSettings;
import com.pointbluetech.dirxml.trace.ldap.DemoTraceEventSource;
import com.pointbluetech.dirxml.trace.ldap.DirXmlObject;
import com.pointbluetech.dirxml.trace.ldap.DriverStatus;
import com.pointbluetech.dirxml.trace.ldap.LegacyTls;
import com.pointbluetech.dirxml.trace.ldap.ServerInfo;
import com.pointbluetech.dirxml.trace.ldap.TraceEventSource;
import com.pointbluetech.dirxml.trace.ldap.VaultConnection;
import com.pointbluetech.dirxml.trace.model.Channel;
import com.pointbluetech.dirxml.trace.model.RawTraceEvent;
import com.pointbluetech.dirxml.trace.model.TraceFileReader;
import com.pointbluetech.dirxml.trace.model.TraceFileWriter;
import com.pointbluetech.dirxml.trace.model.TraceFilter;
import com.pointbluetech.dirxml.trace.model.TraceParser;
import com.pointbluetech.dirxml.trace.model.TraceRecord;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Main window: driver tree with per-server status, trace-level and start/stop controls on the left,
 * live trace on the right.
 */
public final class MainFrame extends JFrame {

    /** Default for records kept in memory (all drivers, unfiltered) so the view can be re-filtered. */
    private static final int DEFAULT_STORED = 50_000;
    /** Default for records kept in the text pane. */
    private static final int DEFAULT_DISPLAYED = 15_000;
    /** Records moved to the view per timer tick, so a burst cannot freeze the window. */
    private static final int MAX_PER_TICK = 5_000;
    /** While paused nothing is dropped, unless the heap gets this full. */
    private static final double PAUSED_HEAP_LIMIT = 0.85;
    private static final int MAX_TRACE_LEVEL = 10;
    private static final String ALL_SERVERS = "All servers";

    private final Preferences prefs = Preferences.userNodeForPackage(MainFrame.class);
    private final boolean demo;
    private final com.pointbluetech.dirxml.trace.StartupOptions options;

    // Pipeline: LDAP listener threads -> processor thread (parse + render) -> processed ->
    // (Swing timer on the EDT) -> store / view / file. Rebuilds of the view render on another thread.
    private final ExecutorService processor = daemonThread("trace-processor");
    private final ExecutorService renderer = daemonThread("trace-render");
    private final TraceParser parser = new TraceParser();      // processor thread only
    private long nextSeq;                                      // processor thread only
    private final ConcurrentLinkedQueue<TraceEntry> processed = new ConcurrentLinkedQueue<>();
    private final Deque<TraceEntry> store = new ArrayDeque<>();
    private final Timer drainTimer = new Timer(100, e -> drain());
    private TraceFilter filter = TraceFilter.ALL;
    /** Copy of {@link #filter} for the processor thread, which pre-renders only what will be shown. */
    private volatile TraceFilter liveFilter = TraceFilter.ALL;
    private long received;
    private int maxStored = prefs.getInt("maxStored", DEFAULT_STORED);
    private long droppedWhilePaused;
    private int rebuildGeneration;
    private boolean rebuilding;

    /** All LDAP work runs here, one task at a time, since the connections are not shared safely. */
    private final ExecutorService ldap = daemonThread("ldap");
    private VaultConnection vault;
    private TraceEventSource demoSource;
    /** The trace file being viewed instead of a live stream, or null. */
    private java.nio.file.Path openFile;
    private TraceFileWriter recorder;

    private final TraceView view = new TraceView(prefs.getInt("maxDisplayed", DEFAULT_DISPLAYED));
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Not connected");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree tree = new JTree(treeModel);
    private final Map<Channel, JCheckBox> channelBoxes = new EnumMap<>(Channel.class);
    private final JTextField textFilter = new JTextField(16);
    private final JToggleButton pauseButton = new JToggleButton("Pause");
    private final JToggleButton recordButton = new JToggleButton("Record to File…");
    private final JLabel status = new JLabel(" ");
    private final JLabel showing = new JLabel(" ");
    private final FindBar findBar = new FindBar(view.textPane(), () -> {
        // Searching a moving target is frustrating; freeze the view while finding.
        if (!pauseButton.isSelected()) pauseButton.doClick();
    });

    private final JLabel controlTarget = new JLabel("Select a driver");
    private final JLabel controlStatus = new JLabel(" ");
    private final JComboBox<Object> serverChoice = new JComboBox<>();
    private final JSpinner levelSpinner = new JSpinner(new SpinnerNumberModel(0, 0, MAX_TRACE_LEVEL, 1));
    private final JButton levelApply = new JButton("Apply");
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JButton restartButton = new JButton("Restart");

    private final UpdateNotifier updates = new UpdateNotifier(this);

    private final Action connectAction = action("Connect…", KeyEvent.VK_N, e -> connect());
    private final Action openFileAction = action("Open Trace File…", KeyEvent.VK_O, e -> chooseTraceFile());
    private final Action disconnectAction = action("Disconnect", 0, e -> disconnect());
    private final Action refreshAllAction = action("Refresh Status", 0, e -> refreshAll());
    private final Action clearAction = action("Clear", KeyEvent.VK_K, e -> clearTrace());

    public MainFrame(boolean demo) {
        this(demo ? com.pointbluetech.dirxml.trace.StartupOptions.demoMode() : com.pointbluetech.dirxml.trace.StartupOptions.dialog());
    }

    /** What the command line asked for; see {@link com.pointbluetech.dirxml.trace.StartupOptions}. */
    public MainFrame(com.pointbluetech.dirxml.trace.StartupOptions options) {
        super("DirXML Trace Viewer");
        this.options = options;
        this.demo = options.demo();
        boolean legacy = options.settings() != null ? options.settings().legacyCiphers() : ConnectDialog.legacyCiphersRemembered();
        if (!demo && legacy) {
            LegacyTls.enable();
        }
        CertificateTrust.setPrompt(CertificateDialogs.prompt(this));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        setJMenuBar(buildMenu());
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), buildRight());
        split.setDividerLocation(prefs.getInt("divider", 380));
        split.setContinuousLayout(true);
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                e -> prefs.putInt("divider", split.getDividerLocation()));
        getContentPane().add(buildToolBar(), BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);

        setSize(prefs.getInt("width", 1400), prefs.getInt("height", 860));
        setLocationRelativeTo(null);
        disconnectAction.setEnabled(false);
        refreshAllAction.setEnabled(false);
        updateControls();
        updateShowing();
        updateStatus();
        drainTimer.start();
        getRootPane().setTransferHandler(new javax.swing.TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                try {
                    List<?> files = (List<?>) support.getTransferable()
                            .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty() && files.get(0) instanceof File f) {
                        SwingUtilities.invokeLater(() -> openTraceFile(f));
                        return true;
                    }
                } catch (Exception ignored) {
                }
                return false;
            }
        });
    }

    /** Called once the window is visible. */
    public void startup() {
        updates.checkAutomaticallyIfDue();
        if (options.openFile() != null) {
            openTraceFile(options.openFile().toFile());
        } else if (options.settings() != null) {
            connect(options.settings(), options.driver());
        } else if (demo) {
            startDemo();
        } else {
            connect();
        }
    }

    // ---------------------------------------------------------------- layout

    private JMenuBar buildMenu() {
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(connectAction);
        file.add(disconnectAction);
        file.add(openFileAction);
        file.add(action("Accepted Certificates…", 0, e -> CertificateDialogs.manage(this)));
        file.addSeparator();
        file.add(action("Record to File…", KeyEvent.VK_R, e -> recordButton.doClick()));
        file.add(action("Save Displayed Trace As…", KeyEvent.VK_S, e -> saveDisplayed()));
        file.addSeparator();
        file.add(action("Exit", KeyEvent.VK_Q, e -> shutdown()));
        bar.add(file);

        JMenu edit = new JMenu("Edit");
        edit.add(action("Find…", KeyEvent.VK_F, e -> findBar.open()));
        Action findNext = action("Find Next", KeyEvent.VK_G, e -> findBar.step(1));
        Action findPrev = action("Find Previous", 0, e -> findBar.step(-1));
        findPrev.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_G, menuMask | KeyEvent.SHIFT_DOWN_MASK));
        edit.add(findNext);
        edit.add(findPrev);
        bar.add(edit);

        JMenu viewMenu = new JMenu("View");
        JCheckBoxMenuItem wrap = new JCheckBoxMenuItem("Wrap Lines", prefs.getBoolean("wrap", false));
        wrap.addActionListener(e -> {
            view.setWrap(wrap.isSelected());
            prefs.putBoolean("wrap", wrap.isSelected());
        });
        view.setWrap(wrap.isSelected());
        JCheckBoxMenuItem compact = new JCheckBoxMenuItem("Compact Whitespace", prefs.getBoolean("compact", false));
        compact.setToolTipText("Hide blank lines, e.g. the spacing in XSLT policy trace. Recorded files keep the original.");
        compact.addActionListener(e -> {
            view.setCompact(compact.isSelected());
            prefs.putBoolean("compact", compact.isSelected());
            rebuildView();
        });
        view.setCompact(compact.isSelected());
        JCheckBoxMenuItem autoScroll = new JCheckBoxMenuItem("Auto-Scroll", true);
        autoScroll.addActionListener(e -> view.setAutoScroll(autoScroll.isSelected()));
        viewMenu.add(wrap);
        viewMenu.add(compact);
        viewMenu.add(autoScroll);
        viewMenu.addSeparator();
        view.setFontSize(prefs.getFloat("fontSize", 13f));
        Action bigger = action("Larger Font", 0, e -> changeFont(1));
        bigger.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menuMask));
        Action smaller = action("Smaller Font", 0, e -> changeFont(-1));
        smaller.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menuMask));
        viewMenu.add(bigger);
        viewMenu.add(smaller);
        viewMenu.addSeparator();
        refreshAllAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        viewMenu.add(action("Buffer Sizes…", 0, e -> showBufferSettings()));
        viewMenu.addSeparator();
        viewMenu.add(refreshAllAction);
        viewMenu.add(clearAction);
        bar.add(viewMenu);

        JMenu help = new JMenu("Help");
        help.add(action("Check for Updates…", 0, e -> updates.checkNow()));
        JCheckBoxMenuItem autoUpdate = new JCheckBoxMenuItem("Check for Updates Automatically",
                updates.automaticChecksEnabled());
        autoUpdate.setToolTipText("Once a day at startup, ask GitHub whether a newer release exists");
        autoUpdate.addActionListener(e -> updates.setAutomaticChecksEnabled(autoUpdate.isSelected()));
        help.add(autoUpdate);
        help.addSeparator();
        help.add(action("About DirXML Trace Viewer", 0, e -> updates.showAbout()));
        bar.add(help);
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
            Desktop.getDesktop().setAboutHandler(e -> updates.showAbout()); // macOS app menu → About
        }
        return bar;
    }

    private JComponent buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        tb.add(connectAction);
        tb.add(disconnectAction);
        tb.add(openFileAction);
        tb.add(refreshAllAction);
        tb.addSeparator();
        pauseButton.setToolTipText("Stop updating the view. Trace is still collected (and recorded).");
        pauseButton.addActionListener(e -> {
            pauseButton.setText(pauseButton.isSelected() ? "Resume" : "Pause");
            if (!pauseButton.isSelected()) {
                droppedWhilePaused = 0;
                limitStore();
                rebuildView();
            }
            updateStatus();
        });
        tb.add(pauseButton);
        tb.add(clearAction);
        tb.addSeparator();

        tb.add(new JLabel("Channels: "));
        for (Channel c : Channel.values()) {
            JCheckBox box = new JCheckBox(c.label(), true);
            box.addActionListener(e -> filterChanged());
            channelBoxes.put(c, box);
            tb.add(box);
        }
        tb.addSeparator();

        tb.add(new JLabel("Contains: "));
        textFilter.setMaximumSize(new Dimension(220, textFilter.getPreferredSize().height));
        textFilter.setToolTipText("Show only messages containing this text (case-insensitive)");
        textFilter.putClientProperty("JTextField.showClearButton", true);
        Timer debounce = new Timer(300, e -> filterChanged());
        debounce.setRepeats(false);
        textFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        tb.add(textFilter);
        tb.addSeparator();
        Action find = action("Find…", 0, e -> findBar.open());
        find.putValue(Action.SHORT_DESCRIPTION, "Find text in the displayed trace (" + (System.getProperty("os.name").startsWith("Mac") ? "⌘F" : "Ctrl+F") + ")");
        tb.add(find);
        tb.add(Box.createHorizontalGlue());

        recordButton.setToolTipText("Write the trace to a file as it is displayed");
        recordButton.addActionListener(e -> {
            if (recordButton.isSelected()) {
                startRecording();
            } else {
                stopRecording();
            }
        });
        tb.add(recordButton);
        return tb;
    }

    private JComponent buildLeft() {
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new DriverTreeRenderer());
        tree.addTreeSelectionListener(e -> selectionChanged());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Driver control"), BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 4;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(2, 2, 2, 2);
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(controlTarget, c);
        panel.add(controlStatus, c);

        c.gridy = 2;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Server:"), c);
        c.gridx = 1;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(serverChoice, c);

        c.gridy = 3;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Trace level:"), c);
        c.gridx = 1;
        panel.add(levelSpinner, c);
        c.gridx = 2;
        panel.add(levelApply, c);
        c.gridx = 3;
        panel.add(refreshButton, c);

        JPanel lifecycle = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        lifecycle.add(startButton);
        lifecycle.add(stopButton);
        lifecycle.add(restartButton);
        c.gridy = 4;
        c.gridx = 0;
        c.gridwidth = 4;
        panel.add(lifecycle, c);

        serverChoice.setToolTipText("Trace level and start/stop apply to this server, or to every server in the driver set");
        serverChoice.addActionListener(e -> syncSpinnerToServer());
        levelSpinner.setToolTipText("0 = off, 1-5 = increasingly detailed, higher levels add engine internals");
        levelApply.setToolTipText("Write the trace level on the chosen server(s)");
        levelApply.addActionListener(e -> applyTraceLevel());
        refreshButton.setToolTipText("Re-read state and trace level from every server");
        refreshButton.addActionListener(e -> refreshSelected());
        startButton.addActionListener(e -> lifecycle(Lifecycle.START));
        stopButton.addActionListener(e -> lifecycle(Lifecycle.STOP));
        restartButton.addActionListener(e -> lifecycle(Lifecycle.RESTART));

        JPanel left = new JPanel(new BorderLayout());
        left.add(new JScrollPane(tree), BorderLayout.CENTER);
        left.add(panel, BorderLayout.SOUTH);
        left.setMinimumSize(new Dimension(240, 100));
        return left;
    }

    private JComponent buildRight() {
        JPanel right = new JPanel(new BorderLayout());
        showing.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        right.add(showing, BorderLayout.NORTH);
        right.add(view, BorderLayout.CENTER);
        right.add(findBar, BorderLayout.SOUTH);
        return right;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        bar.add(status);
        return bar;
    }

    // ---------------------------------------------------------------- background work

    /** Runs LDAP work off the EDT (serialized), then hands the result back on the EDT. */
    private <T> void background(Callable<T> work, Consumer<T> onSuccess, String errorMessage, Runnable always) {
        ldap.submit(() -> {
            T result = null;
            Exception failure = null;
            try {
                result = work.call();
            } catch (Exception e) {
                failure = e;
            }
            T r = result;
            Exception f = failure;
            SwingUtilities.invokeLater(() -> {
                try {
                    if (f != null) {
                        showError(errorMessage, f);
                    } else {
                        onSuccess.accept(r);
                    }
                } finally {
                    if (always != null) always.run();
                }
            });
        });
    }

    // ---------------------------------------------------------------- connection

    private void connect() {
        ConnectionSettings settings = new ConnectDialog(this).showDialog();
        if (settings == null) {
            return;
        }
        connect(settings, null);
    }

    /** Connect with the given settings; {@code selectDriver} names the driver to select once the tree is built (null: the root). */
    private void connect(ConnectionSettings settings, String selectDriver) {
        disconnect();
        connectAction.setEnabled(false);
        status.setText("Connecting to " + settings.display() + " and the servers in its driver sets…");
        record Connected(VaultConnection vault, List<DirXmlObject> sets, Map<String, String> traceFailures) {
        }
        background(() -> {
            VaultConnection v = VaultConnection.open(settings);
            try {
                List<DirXmlObject> sets = v.discover();
                return new Connected(v, sets, v.startTrace(this::accept, this::streamError));
            } catch (Exception e) {
                v.close();
                throw e;
            }
        }, c -> {
            vault = c.vault();
            view.setTagServer(vault.servers().size() > 1);
            setTitle("DirXML Trace Viewer — " + settings.display());
            populateTree(settings.display(), c.sets());
            if (selectDriver != null && !selectDriver.isBlank() && !selectDriver(selectDriver)) {
                status.setText("Driver \"" + selectDriver + "\" was not found under \"" + settings.searchBase() + "\"; showing every driver.");
            }
            disconnectAction.setEnabled(true);
            refreshAllAction.setEnabled(true);
            if (c.sets().isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "Connected, but no driver sets were found under \"" + settings.searchBase()
                                + "\".\nTrace is still streaming for all drivers on this server.",
                        "No driver sets", JOptionPane.INFORMATION_MESSAGE);
            }
            if (!c.traceFailures().isEmpty()) {
                StringBuilder sb = new StringBuilder("Trace is not streaming from these servers:\n");
                c.traceFailures().forEach((s, why) -> sb.append("\n").append(s).append(": ").append(why));
                JOptionPane.showMessageDialog(this, sb.toString(), "Some servers unavailable",
                        JOptionPane.WARNING_MESSAGE);
            }
        }, "Could not connect to " + settings.display(), () -> {
            connectAction.setEnabled(true);
            updateStatus();
        });
    }

    private void startDemo() {
        demoSource = new DemoTraceEventSource();
        try {
            demoSource.start(this::accept, this::streamError);
        } catch (Exception e) {
            showError("Demo failed", e);
        }
        setTitle("DirXML Trace Viewer — demo");
        populateTree("Demo", DemoTraceEventSource.DRIVER_SETS);
        disconnectAction.setEnabled(true);
        updateStatus();
    }

    private void disconnect() {
        openFile = null;
        if (demoSource != null) {
            demoSource.close();
            demoSource = null;
        }
        if (vault != null) {
            VaultConnection v = vault;
            vault = null;
            ldap.submit(v::close);
        }
        populateTree("Not connected", List.of());
        setTitle("DirXML Trace Viewer");
        disconnectAction.setEnabled(false);
        refreshAllAction.setEnabled(false);
        updateStatus();
    }

    private void streamError(Exception e) {
        SwingUtilities.invokeLater(() -> {
            status.setText("Trace stream error: " + e.getMessage());
            status.setForeground(TracePalette.TOKENS.get(com.pointbluetech.dirxml.trace.model.TraceHighlighter.Token.STATUS_ERROR));
        });
    }

    /** Select the driver named {@code name} (its cn or its trace name, case-insensitively); false when no such driver is in the tree. */
    private boolean selectDriver(String name) {
        for (int i = 0; i < treeRoot.getChildCount(); i++) {
            DefaultMutableTreeNode setNode = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
            for (int j = 0; j < setNode.getChildCount(); j++) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) setNode.getChildAt(j);
                if (n.getUserObject() instanceof DirXmlObject o && o.kind() == DirXmlObject.Kind.DRIVER
                        && (name.equalsIgnoreCase(o.name()) || name.equalsIgnoreCase(o.traceName()) || name.equalsIgnoreCase(o.dn()))) {
                    tree.setSelectionPath(new javax.swing.tree.TreePath(n.getPath()));
                    tree.scrollPathToVisible(new javax.swing.tree.TreePath(n.getPath()));
                    return true;
                }
            }
        }
        return false;
    }

    private void populateTree(String rootLabel, List<DirXmlObject> sets) {
        treeRoot.removeAllChildren();
        treeRoot.setUserObject(rootLabel);
        for (DirXmlObject set : sets) {
            DefaultMutableTreeNode setNode = new DefaultMutableTreeNode(set);
            for (DirXmlObject d : set.drivers()) {
                setNode.add(new DefaultMutableTreeNode(d, false));
            }
            treeRoot.add(setNode);
        }
        treeModel.reload();
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
        tree.setSelectionRow(0);
    }

    // ---------------------------------------------------------------- trace pipeline

    /** Called on LDAP listener threads: parse and pre-render on the processor thread. */
    private void accept(RawTraceEvent ev) {
        processor.execute(() -> {
            TraceEntry e = new TraceEntry(nextSeq++, parser.parse(ev));
            if (liveFilter.matches(e.record)) {
                e.render(view.compact(), view.tagServer());
            }
            processed.add(e);
        });
    }

    private void drain() {
        List<TraceEntry> shown = new ArrayList<>();
        int n = 0;
        TraceEntry e;
        while (n < MAX_PER_TICK && (e = processed.poll()) != null) {
            n++;
            received++;
            store.addLast(e);
            if (filter.matches(e.record)) {
                shown.add(e);
            }
        }
        if (n == 0) {
            return;
        }
        limitStore();
        if (recorder != null && !shown.isEmpty()) {
            try {
                for (TraceEntry s : shown) {
                    recorder.write(s.record, view.tagServer());
                }
                recorder.flush();
            } catch (IOException ex) {
                stopRecording();
                showError("Writing the trace file failed; recording stopped", ex);
            }
        }
        if (!pauseButton.isSelected() && !rebuilding) {
            view.append(shown);
        }
        updateStatus();
    }

    /**
     * Keeps the in-memory buffer at its limit, except while paused: then everything is kept so the
     * trace being read is not lost, unless the heap is nearly full.
     */
    private void limitStore() {
        if (!pauseButton.isSelected() && openFile == null) {
            while (store.size() > maxStored) {
                store.removeFirst();
            }
            return;
        }
        if (store.size() <= maxStored) {
            return;
        }
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        if (used > rt.maxMemory() * PAUSED_HEAP_LIMIT) {
            int drop = Math.max(1_000, store.size() / 10);
            for (int i = 0; i < drop && !store.isEmpty(); i++) {
                store.removeFirst();
            }
            droppedWhilePaused += drop;
        }
    }

    private void setFilter(TraceFilter f) {
        filter = f;
        liveFilter = f;
    }

    private void filterChanged() {
        EnumSet<Channel> channels = EnumSet.noneOf(Channel.class);
        channelBoxes.forEach((c, box) -> {
            if (box.isSelected()) channels.add(c);
        });
        setFilter(filter.withChannels(channels).withText(textFilter.getText()));
        updateShowing();
        rebuildView();
    }

    /**
     * Rebuilds the view for the current filter. The matching records are collected here, rendered
     * into a new document on the render thread, and swapped in; records that arrive meanwhile are
     * appended afterwards.
     */
    private void rebuildView() {
        if (pauseButton.isSelected()) {
            return;
        }
        int gen = ++rebuildGeneration;
        rebuilding = true;
        TraceFilter f = filter;
        Deque<TraceEntry> matching = new ArrayDeque<>();
        int limit = view.maxRecords();
        for (var it = store.descendingIterator(); it.hasNext() && matching.size() < limit; ) {
            TraceEntry e = it.next();
            if (f.matches(e.record)) {
                matching.addFirst(e);
            }
        }
        long upTo = store.isEmpty() ? -1 : store.peekLast().seq;
        renderer.execute(() -> {
            TraceView.Built built = view.build(matching);
            SwingUtilities.invokeLater(() -> {
                if (gen != rebuildGeneration) {
                    return; // superseded by a newer rebuild
                }
                view.install(built);
                rebuilding = false;
                Deque<TraceEntry> later = new ArrayDeque<>();
                for (var it = store.descendingIterator(); it.hasNext(); ) {
                    TraceEntry e = it.next();
                    if (e.seq <= upTo) break;
                    if (filter.matches(e.record)) later.addFirst(e);
                }
                view.append(later);
                updateStatus();
            });
        });
    }

    private void clearTrace() {
        store.clear();
        processed.clear();
        processor.execute(parser::reset);
        rebuildGeneration++;
        rebuilding = false;
        view.clear();
        updateStatus();
    }

    private void chooseTraceFile() {
        JFileChooser fc = new JFileChooser(prefs.get("lastTraceDir", prefs.get("lastDir", System.getProperty("user.home"))));
        fc.setDialogTitle("Open Trace File");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            prefs.put("lastTraceDir", fc.getSelectedFile().getParent());
            openTraceFile(fc.getSelectedFile());
        }
    }

    /**
     * Shows a trace file written on the server instead of a live stream. The whole file is kept in
     * memory (so every filter works on all of it) unless the heap gets nearly full.
     */
    private void openTraceFile(File f) {
        if (vault != null && JOptionPane.showConfirmDialog(this,
                "Disconnect from " + vault.settings().display() + " and open " + f.getName() + "?",
                "Open Trace File", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        disconnect();
        clearTrace();
        java.nio.file.Path path = f.toPath();
        openFile = path;
        setTitle("DirXML Trace Viewer — " + f.getName());
        populateTree(f.getName(), List.of());
        openFileAction.setEnabled(false);
        long size = Math.max(1, f.length());
        status.setText("Loading " + f.getName() + "…");
        Thread loader = new Thread(() -> {
            List<TraceEntry> entries = new ArrayList<>();
            java.util.Set<String> drivers = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            Runtime rt = Runtime.getRuntime();
            boolean complete;
            Exception failure = null;
            try {
                complete = TraceFileReader.read(path, r -> {
                    entries.add(new TraceEntry(entries.size(), r));
                    if (r.driverName() != null) drivers.add(r.driverName());
                }, bytes -> SwingUtilities.invokeLater(() -> {
                    if (openFile == path) {
                        status.setText("Loading " + f.getName() + "… " + (int) (bytes * 100 / size) + "% · "
                                + String.format("%,d", entries.size()) + " messages");
                    }
                }), () -> openFile == path && (entries.size() % 1000 != 0
                        || rt.totalMemory() - rt.freeMemory() < rt.maxMemory() * PAUSED_HEAP_LIMIT));
            } catch (Exception e) {
                complete = false;
                failure = e;
            }
            boolean whole = complete;
            Exception error = failure;
            SwingUtilities.invokeLater(() -> {
                openFileAction.setEnabled(true);
                if (openFile != path) {
                    return; // something else was opened meanwhile
                }
                if (error != null) {
                    showError("Could not read " + f, error);
                }
                store.addAll(entries);
                received = entries.size();
                if (pauseButton.isSelected()) {
                    pauseButton.doClick(); // resuming also rebuilds the view
                }
                List<DirXmlObject> found = new ArrayList<>();
                for (String d : drivers) {
                    found.add(new DirXmlObject(DirXmlObject.Kind.DRIVER, "", d, d, List.of(), List.of(), List.of()));
                }
                populateTree(f.getName(), List.of(new DirXmlObject(DirXmlObject.Kind.DRIVER_SET, "",
                        "Drivers in file", "", List.of(), List.of(), found)));
                rebuildView();
                if (error == null && !whole) {
                    JOptionPane.showMessageDialog(this, "Memory is nearly full, so only the first "
                                    + String.format("%,d", entries.size()) + " messages of " + f.getName()
                                    + " were loaded.\nStart the viewer with a larger heap (e.g. java -Xmx4g -jar …) to load it all.",
                            "File partly loaded", JOptionPane.WARNING_MESSAGE);
                }
            });
        }, "file-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void showBufferSettings() {
        JSpinner stored = new JSpinner(new SpinnerNumberModel(maxStored, 1_000, 5_000_000, 10_000));
        JSpinner displayed = new JSpinner(new SpinnerNumberModel(view.maxRecords(), 1_000, 500_000, 5_000));
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.LINE_START;
        c.gridy = 0;
        p.add(new JLabel("Messages kept in memory:"), c);
        p.add(stored, c);
        c.gridy = 1;
        p.add(new JLabel("Messages shown in the view:"), c);
        p.add(displayed, c);
        c.gridy = 2;
        c.gridwidth = 2;
        p.add(new JLabel("<html><font color='#8a8f98'>Memory holds every driver's trace so filters can be changed<br>"
                + "without losing history. While paused, nothing is dropped unless the<br>"
                + "Java heap (max " + heapMb + " MB) is nearly full. Large views scroll less smoothly.</font></html>"), c);
        if (JOptionPane.showConfirmDialog(this, p, "Buffer Sizes", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        maxStored = (Integer) stored.getValue();
        int shown = (Integer) displayed.getValue();
        prefs.putInt("maxStored", maxStored);
        prefs.putInt("maxDisplayed", shown);
        view.setMaxRecords(shown);
        limitStore();
        rebuildView();
    }

    // ---------------------------------------------------------------- selection & driver control

    private DefaultMutableTreeNode selectedNode() {
        TreePath p = tree.getSelectionPath();
        return p == null ? null : (DefaultMutableTreeNode) p.getLastPathComponent();
    }

    private DirXmlObject selectedObject() {
        DefaultMutableTreeNode n = selectedNode();
        return n != null && n.getUserObject() instanceof DirXmlObject o ? o : null;
    }

    /** The servers of the driver set a node belongs to. */
    private static List<ServerInfo> serversOf(DefaultMutableTreeNode node) {
        for (DefaultMutableTreeNode n = node; n != null; n = (DefaultMutableTreeNode) n.getParent()) {
            if (n.getUserObject() instanceof DirXmlObject o && o.kind() == DirXmlObject.Kind.DRIVER_SET) {
                return o.servers();
            }
        }
        return List.of();
    }

    private void selectionChanged() {
        DirXmlObject o = selectedObject();
        if (o != null && o.kind() == DirXmlObject.Kind.DRIVER) {
            setFilter(filter.withDrivers(Set.of(o.traceName()), Set.of(o.dn())));
        } else {
            setFilter(filter.withDrivers(null, Set.of()));
        }
        List<ServerInfo> servers = o == null ? List.of() : serversOf(selectedNode());
        Object previous = serverChoice.getSelectedItem();
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
        if (servers.size() > 1) model.addElement(ALL_SERVERS);
        servers.forEach(model::addElement);
        serverChoice.setModel(model);
        if (previous != null && model.getIndexOf(previous) >= 0) {
            serverChoice.setSelectedItem(previous);
        }
        showControls(o);
        updateShowing();
        rebuildView();
    }

    private void showControls(DirXmlObject o) {
        if (o == null) {
            controlTarget.setText("Select a driver or driver set");
            controlStatus.setText(" ");
        } else {
            controlTarget.setText((o.kind() == DirXmlObject.Kind.DRIVER ? "Driver: " : "Driver set: ") + o.name());
            StringBuilder sb = new StringBuilder("<html>");
            if (o.statuses().isEmpty()) {
                sb.append(openFile != null ? "From a trace file; no live status" : "No servers listed for this driver set");
            }
            for (DriverStatus s : o.statuses()) {
                sb.append(escape(s.server().name())).append(": ");
                if (s.error() != null) {
                    sb.append("<font color='#e06c75'>").append(escape(s.error())).append("</font>");
                } else {
                    if (o.kind() == DirXmlObject.Kind.DRIVER) {
                        sb.append(stateHtml(s)).append(" · ");
                    }
                    sb.append("trace ").append(s.effectiveTraceLevel());
                    if (s.traceLevel() == DriverStatus.UNKNOWN) sb.append(" (not set)");
                }
                sb.append("<br>");
            }
            controlStatus.setText(sb.append("</html>").toString());
        }
        syncSpinnerToServer();
        updateControls();
    }

    /** Shows the chosen server's trace level (the first server's when "All servers" is chosen). */
    private void syncSpinnerToServer() {
        DirXmlObject o = selectedObject();
        if (o == null) return;
        List<DriverStatus> chosen = chosenStatuses(o);
        if (!chosen.isEmpty()) {
            levelSpinner.setValue(Math.min(MAX_TRACE_LEVEL, chosen.get(0).effectiveTraceLevel()));
        }
    }

    private List<ServerInfo> chosenServers() {
        Object sel = serverChoice.getSelectedItem();
        if (sel instanceof ServerInfo s) return List.of(s);
        List<ServerInfo> all = new ArrayList<>();
        for (int i = 0; i < serverChoice.getItemCount(); i++) {
            if (serverChoice.getItemAt(i) instanceof ServerInfo s) all.add(s);
        }
        return all;
    }

    private List<DriverStatus> chosenStatuses(DirXmlObject o) {
        List<ServerInfo> servers = chosenServers();
        return o.statuses().stream().filter(s -> servers.stream().anyMatch(c -> c.dn().equals(s.server().dn()))).toList();
    }

    private void updateControls() {
        DirXmlObject o = selectedObject();
        boolean connected = vault != null || demoSource != null;
        boolean hasServers = o != null && serverChoice.getItemCount() > 0;
        boolean driver = o != null && o.kind() == DirXmlObject.Kind.DRIVER;
        serverChoice.setEnabled(hasServers);
        levelSpinner.setEnabled(hasServers && connected);
        levelApply.setEnabled(hasServers && connected);
        refreshButton.setEnabled(o != null && vault != null);
        startButton.setEnabled(driver && hasServers && vault != null);
        stopButton.setEnabled(driver && hasServers && vault != null);
        restartButton.setEnabled(driver && hasServers && vault != null);
    }

    private void setControlsBusy(boolean busy) {
        if (busy) {
            for (JComponent c : List.of(serverChoice, levelSpinner, levelApply, refreshButton, startButton, stopButton,
                    restartButton)) {
                c.setEnabled(false);
            }
        } else {
            updateControls();
        }
    }

    private void applyTraceLevel() {
        DefaultMutableTreeNode node = selectedNode();
        DirXmlObject o = selectedObject();
        if (o == null) return;
        int level = (Integer) levelSpinner.getValue();
        List<ServerInfo> targets = chosenServers();
        if (vault == null) { // demo
            List<DriverStatus> updated = o.statuses().stream().map(s -> targets.contains(s.server())
                    ? new DriverStatus(s.server(), s.state(), level, null) : s).toList();
            replaceNode(node, o.withStatuses(updated));
            return;
        }
        VaultConnection v = vault;
        List<ServerInfo> servers = serversOf(node);
        setControlsBusy(true);
        background(() -> {
            for (ServerInfo s : targets) {
                v.setTraceLevel(o, s, level);
            }
            return v.refresh(o, servers);
        }, updated -> replaceNode(node, updated), "Could not set the trace level on " + o.name(),
                () -> setControlsBusy(false));
    }

    private void refreshSelected() {
        DefaultMutableTreeNode node = selectedNode();
        DirXmlObject o = selectedObject();
        if (o == null || vault == null) return;
        VaultConnection v = vault;
        setControlsBusy(true);
        if (o.kind() == DirXmlObject.Kind.DRIVER_SET) {
            background(() -> v.refreshAll(o), set -> replaceSet(node, set), "Could not refresh " + o.name(),
                    () -> setControlsBusy(false));
        } else {
            List<ServerInfo> servers = serversOf(node);
            background(() -> v.refresh(o, servers), d -> replaceNode(node, d), "Could not refresh " + o.name(),
                    () -> setControlsBusy(false));
        }
    }

    private void refreshAll() {
        if (vault == null) return;
        VaultConnection v = vault;
        refreshAllAction.setEnabled(false);
        status.setText("Refreshing driver status from all servers…");
        List<DefaultMutableTreeNode> setNodes = new ArrayList<>();
        for (int i = 0; i < treeRoot.getChildCount(); i++) {
            setNodes.add((DefaultMutableTreeNode) treeRoot.getChildAt(i));
        }
        background(() -> {
            List<DirXmlObject> sets = new ArrayList<>();
            for (DefaultMutableTreeNode n : setNodes) {
                sets.add(v.refreshAll((DirXmlObject) n.getUserObject()));
            }
            return sets;
        }, sets -> {
            for (int i = 0; i < sets.size(); i++) {
                replaceSet(setNodes.get(i), sets.get(i));
            }
        }, "Could not refresh driver status", () -> {
            refreshAllAction.setEnabled(vault != null);
            updateStatus();
        });
    }

    private enum Lifecycle {
        START("Start", "started"), STOP("Stop", "stopped"), RESTART("Restart", "restarted");

        final String verb;
        final String past;

        Lifecycle(String verb, String past) {
            this.verb = verb;
            this.past = past;
        }
    }

    private void lifecycle(Lifecycle op) {
        DefaultMutableTreeNode node = selectedNode();
        DirXmlObject o = selectedObject();
        if (o == null || o.kind() != DirXmlObject.Kind.DRIVER || vault == null) return;
        List<ServerInfo> targets = chosenServers();
        String where = targets.size() == 1 ? targets.get(0).name() : "all " + targets.size() + " servers";
        if (op != Lifecycle.START && JOptionPane.showConfirmDialog(this,
                op.verb + " driver \"" + o.name() + "\" on " + where + "?", op.verb + " Driver",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        VaultConnection v = vault;
        List<ServerInfo> servers = serversOf(node);
        setControlsBusy(true);
        status.setText(op.verb + "ing " + o.name() + " on " + where + "…");
        background(() -> {
            for (ServerInfo s : targets) {
                switch (op) {
                    case START -> v.startDriver(o, s);
                    case STOP -> v.stopDriver(o, s);
                    case RESTART -> v.restartDriver(o, s);
                }
            }
            return v.refresh(o, servers);
        }, updated -> {
            replaceNode(node, updated);
            status.setText(o.name() + " " + op.past + " on " + where);
            // Drivers take a moment to move through starting/shutting down; re-read once they settle.
            Timer later = new Timer(4000, e -> background(() -> v.refresh(o, servers),
                    d -> replaceNode(node, d), "Could not refresh " + o.name(), null));
            later.setRepeats(false);
            later.start();
        }, "Could not " + op.verb.toLowerCase() + " " + o.name() + " on " + where, () -> setControlsBusy(false));
    }

    private void replaceNode(DefaultMutableTreeNode node, DirXmlObject updated) {
        if (node.getParent() == null && node != treeRoot) return; // tree was rebuilt meanwhile
        node.setUserObject(updated);
        treeModel.nodeChanged(node);
        if (node == selectedNode()) {
            showControls(updated);
        }
    }

    private void replaceSet(DefaultMutableTreeNode setNode, DirXmlObject set) {
        if (setNode.getParent() == null) return;
        setNode.setUserObject(set);
        for (int i = 0; i < setNode.getChildCount() && i < set.drivers().size(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) setNode.getChildAt(i);
            child.setUserObject(set.drivers().get(i));
            treeModel.nodeChanged(child);
        }
        treeModel.nodeChanged(setNode);
        DefaultMutableTreeNode sel = selectedNode();
        if (sel != null && sel.getUserObject() instanceof DirXmlObject o) {
            showControls(o);
        }
    }

    // ---------------------------------------------------------------- recording

    private void startRecording() {
        JFileChooser fc = new JFileChooser(prefs.get("lastDir", System.getProperty("user.home")));
        fc.setDialogTitle("Record Trace to File");
        DirXmlObject o = selectedObject();
        String base = o != null && o.kind() == DirXmlObject.Kind.DRIVER ? o.name().replaceAll("[^\\w.-]+", "_") : "dirxml";
        fc.setSelectedFile(new File(base + "-trace.log"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            recordButton.setSelected(false);
            return;
        }
        File f = fc.getSelectedFile();
        prefs.put("lastDir", f.getParent());
        boolean append = false;
        if (f.exists() && f.length() > 0) {
            Object[] options = {"Append", "Overwrite", "Cancel"};
            int choice = JOptionPane.showOptionDialog(this, f.getName() + " already exists.", "Record Trace",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (choice != 0 && choice != 1) {
                recordButton.setSelected(false);
                return;
            }
            append = choice == 0;
        }
        try {
            recorder = new TraceFileWriter(f.toPath(), append);
            recordButton.setText("● Recording — Stop");
        } catch (IOException e) {
            recordButton.setSelected(false);
            showError("Could not open " + f, e);
        }
        updateStatus();
    }

    private void stopRecording() {
        if (recorder != null) {
            try {
                recorder.close();
            } catch (IOException ignored) {
            }
            recorder = null;
        }
        recordButton.setSelected(false);
        recordButton.setText("Record to File…");
        updateStatus();
    }

    private void saveDisplayed() {
        JFileChooser fc = new JFileChooser(prefs.get("lastDir", System.getProperty("user.home")));
        fc.setDialogTitle("Save Displayed Trace");
        fc.setSelectedFile(new File("dirxml-trace.log"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        prefs.put("lastDir", f.getParent());
        if (f.exists() && JOptionPane.showConfirmDialog(this, "Overwrite " + f.getName() + "?", "Save",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Files.writeString(f.toPath(), view.textPane().getText());
        } catch (IOException e) {
            showError("Could not save " + f, e);
        }
    }

    // ---------------------------------------------------------------- misc

    private void updateShowing() {
        DirXmlObject o = selectedObject();
        String who = o != null && o.kind() == DirXmlObject.Kind.DRIVER ? "driver " + o.name() + " (trace name \"" + o.traceName() + "\")"
                : "all drivers";
        List<String> ch = new ArrayList<>();
        filter.channels().forEach(c -> ch.add(c.label()));
        showing.setText("Showing " + who + " · " + (ch.isEmpty() ? "no channels" : String.join(", ", ch))
                + (filter.text() != null ? " · containing \"" + textFilter.getText().trim() + "\"" : ""));
    }

    private void updateStatus() {
        status.setForeground(javax.swing.UIManager.getColor("Label.foreground"));
        String conn;
        if (vault != null) {
            int n = vault.servers().size();
            conn = "Connected: " + vault.settings().display() + (n > 1 ? " (+" + (n - 1) + " more server" + (n > 2 ? "s" : "") + ")" : "");
        } else if (openFile != null) {
            conn = "File: " + openFile.getFileName();
        } else {
            conn = demoSource != null ? "Demo stream" : "Not connected";
        }
        String rec = recorder != null ? " · Recording to " + recorder.path() + " (" + recorder.records() + " records)" : "";
        String paused = "";
        if (pauseButton.isSelected()) {
            paused = droppedWhilePaused > 0
                    ? " (paused; memory nearly full, " + String.format("%,d", droppedWhilePaused) + " oldest dropped)"
                    : " (paused; still collecting)";
        }
        status.setText(conn + " · " + String.format("%,d", received) + " events received · "
                + String.format("%,d", store.size()) + " in memory · "
                + String.format("%,d", view.displayedRecords()) + " displayed" + paused + rec);
    }

    private void changeFont(int delta) {
        float size = Math.max(8f, Math.min(32f, view.fontSize() + delta));
        view.setFontSize(size);
        prefs.putFloat("fontSize", size);
    }

    private void showError(String message, Exception e) {
        Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        if (cause instanceof com.novell.ldap.LDAPException le && le.getLDAPErrorMessage() != null
                && !le.getLDAPErrorMessage().isBlank() && !detail.contains(le.getLDAPErrorMessage())) {
            detail += "\n" + le.getLDAPErrorMessage();
        }
        // JLDAP wraps the real failure (TLS alert, refused socket, unknown host) as its cause.
        for (Throwable c = cause.getCause(); c != null && c != c.getCause(); c = c.getCause()) {
            if (c.getMessage() != null && !detail.contains(c.getMessage())) {
                detail += "\nCaused by: " + c.getClass().getSimpleName() + ": " + c.getMessage();
            }
        }
        if (!LegacyTls.isEnabled() && (detail.contains("Connection or outbound has closed")
                || detail.contains("handshake_failure"))) {
            detail += "\n\nThe server may offer only legacy RSA ciphers. Try \"Allow legacy RSA ciphers\""
                    + "\n(a restart is needed if an LDAPS connection was already attempted).";
        }
        e.printStackTrace();
        JOptionPane.showMessageDialog(this, message + "\n\n" + detail, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void shutdown() {
        prefs.putInt("width", getWidth());
        prefs.putInt("height", getHeight());
        drainTimer.stop();
        stopRecording();
        disconnect();
        dispose();
        System.exit(0);
    }

    private static Action action(String name, int key, Consumer<java.awt.event.ActionEvent> handler) {
        Action a = new AbstractAction(name) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                handler.accept(e);
            }
        };
        if (key != 0) {
            a.putValue(Action.ACCELERATOR_KEY,
                    KeyStroke.getKeyStroke(key, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        }
        return a;
    }

    private static ExecutorService daemonThread(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String stateHtml(DriverStatus s) {
        String color = switch (s.state()) {
            case DriverStatus.STATE_RUNNING -> "#98c379";
            case DriverStatus.STATE_STOPPED -> "#8a8f98";
            default -> "#e5c07b";
        };
        return "<font color='" + color + "'>" + escape(s.stateLabel()) + "</font>";
    }

    /** Shows state and trace level per server next to each driver set / driver. */
    private static final class DriverTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (((DefaultMutableTreeNode) value).getUserObject() instanceof DirXmlObject o) {
                StringBuilder sb = new StringBuilder("<html>").append(escape(o.name()));
                List<String> parts = new ArrayList<>();
                boolean multi = o.statuses().size() > 1;
                for (DriverStatus s : o.statuses()) {
                    String prefix = multi ? escape(s.server().name()) + ": " : "";
                    if (s.error() != null) {
                        parts.add(prefix + "<font color='#e06c75'>unavailable</font>");
                    } else if (o.kind() == DirXmlObject.Kind.DRIVER) {
                        parts.add(prefix + stateHtml(s) + " · trace " + s.effectiveTraceLevel());
                    } else {
                        parts.add(prefix + "trace " + s.effectiveTraceLevel());
                    }
                }
                if (!parts.isEmpty()) {
                    sb.append(" <font color='#8a8f98'>[</font>").append(String.join("<font color='#8a8f98'> | </font>", parts))
                            .append("<font color='#8a8f98'>]</font>");
                }
                setText(sb.append("</html>").toString());
                setToolTipText(o.dn());
            }
            return this;
        }
    }
}
