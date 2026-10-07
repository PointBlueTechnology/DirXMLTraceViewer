package com.pointbluetech.dirxml.trace.model;

import java.util.List;

/**
 * One DSTrace debug event as delivered by eDirectory, before formatting.
 *
 * @param server        name of the server the event came from ("" when there is only one source)
 * @param eventType     eDirectory event type (e.g. EVT_DB_DIRXML = 214, EVT_DB_DIRXML_DRIVERS = 217)
 * @param receivedAt    local wall-clock time the event was received
 * @param perpetratorDN DN reported by the server as the source of the event (may be empty)
 * @param formatString  DSTrace printf-style format string
 * @param parameters    typed parameter values (String, Integer, byte[], or other objects)
 */
public record RawTraceEvent(String server, int eventType, long receivedAt, String perpetratorDN,
                            String formatString, List<Object> parameters) {

    public RawTraceEvent {
        server = server == null ? "" : server;
        perpetratorDN = perpetratorDN == null ? "" : perpetratorDN;
        formatString = formatString == null ? "" : formatString;
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public RawTraceEvent(int eventType, long receivedAt, String perpetratorDN, String formatString,
                         List<Object> parameters) {
        this("", eventType, receivedAt, perpetratorDN, formatString, parameters);
    }
}
