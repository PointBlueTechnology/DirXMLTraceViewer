package com.pointbluetech.dirxml.trace.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable view filter.
 *
 * @param channels    channels to show
 * @param driverNames lower-cased driver trace names to show, or null for all drivers
 * @param driverDns   normalized driver DNs, matched against the event's perpetrator DN
 * @param text        case-insensitive substring the message must contain, or null
 */
public record TraceFilter(Set<Channel> channels, Set<String> driverNames, Set<String> driverDns, String text) {

    public static final TraceFilter ALL = new TraceFilter(EnumSet.allOf(Channel.class), null, Set.of(), null);

    public TraceFilter {
        channels = channels.isEmpty() ? EnumSet.noneOf(Channel.class) : EnumSet.copyOf(channels);
        driverNames = driverNames == null ? null
                : driverNames.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        driverDns = driverDns.stream().map(TraceFilter::normalizeDn).collect(Collectors.toUnmodifiableSet());
        text = text == null || text.isBlank() ? null : text.toLowerCase(Locale.ROOT);
    }

    public boolean matches(TraceRecord r) {
        if (!channels.contains(r.channel())) {
            return false;
        }
        if (driverNames != null) {
            boolean byName = r.driverName() != null && driverNames.contains(r.driverName().toLowerCase(Locale.ROOT));
            boolean byDn = !r.perpetratorDN().isEmpty() && driverDns.contains(normalizeDn(r.perpetratorDN()));
            if (!byName && !byDn) {
                return false;
            }
        }
        return text == null || r.text().toLowerCase(Locale.ROOT).contains(text);
    }

    public TraceFilter withChannels(Set<Channel> c) {
        return new TraceFilter(c, driverNames, driverDns, text);
    }

    public TraceFilter withDrivers(Set<String> names, Set<String> dns) {
        return new TraceFilter(channels, names, dns, text);
    }

    public TraceFilter withText(String t) {
        return new TraceFilter(channels, driverNames, driverDns, t);
    }

    static String normalizeDn(String dn) {
        return dn.toLowerCase(Locale.ROOT).replaceAll("\\s*([,=])\\s*", "$1").trim();
    }
}
