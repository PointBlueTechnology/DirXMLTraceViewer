package com.pointbluetech.dirxml.trace.ldap;

/**
 * A driver's (or driver set's) state and trace level as seen by one server. Both values are
 * per-server in eDirectory, so they must be read over a connection to that server.
 *
 * @param state      engine driver state (see {@link #stateLabel()}), or {@link #UNKNOWN}
 * @param traceLevel trace level attribute value, or {@link #UNKNOWN} if not set
 * @param error      why the values could not be read, or null
 */
public record DriverStatus(ServerInfo server, int state, int traceLevel, String error) {

    public static final int UNKNOWN = -1;

    public static final int STATE_STOPPED = 0;
    public static final int STATE_STARTING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_SHUTTING_DOWN = 3;
    public static final int STATE_GETTING_SCHEMA = 4;

    public static DriverStatus failed(ServerInfo server, String error) {
        return new DriverStatus(server, UNKNOWN, UNKNOWN, error);
    }

    /** The engine treats a missing trace level as 0 (off). */
    public int effectiveTraceLevel() {
        return Math.max(0, traceLevel);
    }

    public String stateLabel() {
        if (error != null) return "unavailable";
        return switch (state) {
            case STATE_STOPPED -> "stopped";
            case STATE_STARTING -> "starting";
            case STATE_RUNNING -> "running";
            case STATE_SHUTTING_DOWN -> "shutting down";
            case STATE_GETTING_SCHEMA -> "getting schema";
            case UNKNOWN -> "state unknown";
            default -> "state " + state;
        };
    }
}
