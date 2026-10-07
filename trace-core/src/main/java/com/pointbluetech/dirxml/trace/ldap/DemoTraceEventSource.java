package com.pointbluetech.dirxml.trace.ldap;

import com.pointbluetech.dirxml.trace.model.RawTraceEvent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Synthetic trace for trying the viewer without an Identity Vault ({@code --demo}). */
public final class DemoTraceEventSource implements TraceEventSource {

    private static final ServerInfo IDM1 = new ServerInfo("cn=idm1,ou=servers,o=system", "idm1", "idm1");
    private static final ServerInfo IDM2 = new ServerInfo("cn=idm2,ou=servers,o=system", "idm2", "idm2");

    public static final List<DirXmlObject> DRIVER_SETS = List.of(new DirXmlObject(
            DirXmlObject.Kind.DRIVER_SET, "cn=DriverSet,o=system", "DriverSet", "DriverSet", List.of(IDM1, IDM2),
            List.of(status(IDM1, DriverStatus.UNKNOWN, 0), status(IDM2, DriverStatus.UNKNOWN, 0)), List.of(
            driver("AD", status(IDM1, DriverStatus.STATE_RUNNING, 3), status(IDM2, DriverStatus.STATE_STOPPED, 0)),
            driver("HR JDBC", status(IDM1, DriverStatus.STATE_RUNNING, 3), status(IDM2, DriverStatus.STATE_RUNNING, 1)))));

    private static DriverStatus status(ServerInfo s, int state, int level) {
        return new DriverStatus(s, state, level, null);
    }

    private static DirXmlObject driver(String name, DriverStatus... statuses) {
        return new DirXmlObject(DirXmlObject.Kind.DRIVER, "cn=" + name + ",cn=DriverSet,o=system", name, name,
                List.of(), List.of(statuses), List.of());
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss.SSS");

    private ScheduledExecutorService timer;
    private int step;

    @Override
    public void start(Consumer<RawTraceEvent> onEvent, Consumer<Exception> onError) {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "demo-trace");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(() -> emit(onEvent), 200, 700, TimeUnit.MILLISECONDS);
    }

    private void emit(Consumer<RawTraceEvent> onEvent) {
        String ts = LocalDateTime.now().format(TS);
        int user = 1000 + step;
        switch (step++ % 6) {
            case 0 -> {
                send(onEvent, "[" + ts + "]:AD ST:Received event from eDirectory.", List.of());
                send(onEvent, "%s", List.of("""
                        <nds dtdversion="4.0" ndsversion="8.x">
                          <source>
                            <product edition="Advanced" version="4.10.0.0">DirXML</product>
                            <contact>NetIQ Corporation</contact>
                          </source>
                          <input>
                            <modify class-name="User" event-id="0" qualified-src-dn="O=data\\OU=users\\CN=jdoe%d" src-dn="\\TREE\\data\\users\\jdoe%d">
                              <!-- synthetic demo event -->
                              <modify-attr attr-name="Telephone Number">
                                <remove-all-values/>
                                <add-value>
                                  <value type="teleNumber">+1 555 0100</value>
                                </add-value>
                              </modify-attr>
                            </modify>
                          </input>
                        </nds>""".replace("%d", Integer.toString(user))));
            }
            case 1 -> {
                send(onEvent, "[%s]:AD ST:Applying policy: %+C%14Csub-ctp-DefaultValues%-C.", List.of(ts));
                send(onEvent, "[" + ts + "]:AD ST:  Applying to modify #1.", List.of());
                send(onEvent, "[" + ts + "]:AD ST:    Evaluating selection criteria for rule '%+C%13CSet default department%-C'.", List.of());
                send(onEvent, "[" + ts + "]:AD ST:      (if-class-name equal \"User\") = TRUE.", List.of());
            }
            case 2 -> {
                send(onEvent, "[" + ts + "]:HR JDBC PT:Polling for changes.", List.of());
                send(onEvent, "[" + ts + "]:HR JDBC PT:Sending to eDirectory:", List.of());
                send(onEvent, """
                        <nds dtdversion="4.0">
                          <input>
                            <add class-name="User" src-dn="EMPID=%d">
                              <add-attr attr-name="Surname"><value>Doe</value></add-attr>
                            </add>
                          </input>
                        </nds>""", List.of(user));
            }
            case 3 -> {
                send(onEvent, "[" + ts + "]:AD ST:SubmitCommand", List.of());
                send(onEvent, """
                        <nds dtdversion="4.0">
                          <output>
                            <status event-id="0" level="error">Code(-9010) An exception occurred: novell.jclient.JCException: modifyEntry -601 ERR_NO_SUCH_ENTRY</status>
                          </output>
                        </nds>""", List.of());
            }
            case 4 -> {
                send(onEvent, "[" + ts + "]:HR JDBC PT:", List.of());
                send(onEvent, """
                        <nds dtdversion="4.0">
                          <output>
                            <status event-id="hr-%d" level="success"></status>
                          </output>
                        </nds>""", List.of(user));
            }
            default -> {
                send(onEvent, "[" + ts + "]:DirXML:Driver set heartbeat; %d drivers running.", List.of(2));
                send(onEvent, "[" + ts + "]:AD ST:<status level=\"warning\">Association is missing; object will be matched.</status>", List.of());
            }
        }
    }

    private static void send(Consumer<RawTraceEvent> onEvent, String format, List<Object> params) {
        onEvent.accept(new RawTraceEvent(214, System.currentTimeMillis(), "", format, params));
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }
}
