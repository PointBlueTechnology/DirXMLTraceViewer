package com.pointbluetech.dirxml.trace.ldap;

import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPException;
import com.novell.ldap.events.LDAPEvent;
import com.novell.ldap.events.LDAPEventListener;
import com.novell.ldap.events.LDAPExceptionEvent;
import com.novell.ldap.events.edir.EdirEventConstant;
import com.novell.ldap.events.edir.EdirEventIntermediateResponse;
import com.novell.ldap.events.edir.EdirEventSource;
import com.novell.ldap.events.edir.EdirEventSpecifier;
import com.novell.ldap.events.edir.eventdata.DebugEventData;
import com.novell.ldap.events.edir.eventdata.DebugParameter;
import com.pointbluetech.dirxml.trace.model.RawTraceEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Streams DirXML trace (DSTrace categories DirXML and DirXML Drivers) through the eDirectory LDAP
 * event monitoring extension. Uses its own connection, since the event registration occupies it.
 */
public final class LdapTraceEventSource implements TraceEventSource {

    private final ConnectionSettings settings;
    private final String serverName;
    private LDAPConnection conn;
    private EdirEventSource source;
    private LDAPEventListener listener;

    /** @param serverName tags each event with the server it came from */
    public LdapTraceEventSource(ConnectionSettings settings, String serverName) {
        this.settings = settings;
        this.serverName = serverName;
    }

    @Override
    public void start(Consumer<RawTraceEvent> onEvent, Consumer<Exception> onError) throws LDAPException {
        conn = LdapSession.connect(settings);

        EdirEventSpecifier[] specifier = {
                new EdirEventSpecifier(EdirEventConstant.EVT_DB_DIRXML, EdirEventConstant.EVT_STATUS_ALL),
                new EdirEventSpecifier(EdirEventConstant.EVT_DB_DIRXML_DRIVERS, EdirEventConstant.EVT_STATUS_ALL),
        };
        listener = new LDAPEventListener() {
            @Override
            public void ldapEventNotification(LDAPEvent evt) {
                if (evt.getContainedEventInformation() instanceof EdirEventIntermediateResponse r
                        && r.getResponsedata() instanceof DebugEventData d) {
                    onEvent.accept(new RawTraceEvent(serverName, r.getEventtype(), System.currentTimeMillis(),
                            d.getPerpetratorDN(), d.getFormatString(), parameters(d)));
                }
            }

            @Override
            public void ldapExceptionNotification(LDAPExceptionEvent evt) {
                LDAPException e = evt.getLDAPException();
                onError.accept(new LDAPException(serverName + ": " + e.getMessage(), e.getResultCode(),
                        e.getLDAPErrorMessage()));
            }
        };
        try {
            source = new EdirEventSource();
            source.registerforEvent(specifier, conn, listener);
        } catch (LDAPException e) {
            close();
            throw e;
        }
    }

    private static List<Object> parameters(DebugEventData d) {
        List<?> params = d.getParameters();
        List<Object> values = new ArrayList<>(params == null ? 0 : params.size());
        if (params != null) {
            for (Object p : params) {
                values.add(p instanceof DebugParameter dp ? dp.getData() : p);
            }
        }
        return values;
    }

    @Override
    public void close() {
        if (source != null && listener != null) {
            try {
                source.removeListener(listener);
            } catch (LDAPException ignored) {
            }
        }
        source = null;
        listener = null;
        LdapSession.safeDisconnect(conn);
        conn = null;
    }
}
