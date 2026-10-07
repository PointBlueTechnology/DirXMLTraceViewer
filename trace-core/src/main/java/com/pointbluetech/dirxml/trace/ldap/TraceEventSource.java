package com.pointbluetech.dirxml.trace.ldap;

import com.pointbluetech.dirxml.trace.model.RawTraceEvent;

import java.util.function.Consumer;

/** A live stream of DSTrace events. Callbacks arrive on a background thread. */
public interface TraceEventSource extends AutoCloseable {

    void start(Consumer<RawTraceEvent> onEvent, Consumer<Exception> onError) throws Exception;

    @Override
    void close();
}
