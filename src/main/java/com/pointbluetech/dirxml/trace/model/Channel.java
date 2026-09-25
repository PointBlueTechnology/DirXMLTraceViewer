package com.pointbluetech.dirxml.trace.model;

/** The driver channel a trace message came from. */
public enum Channel {
    SUBSCRIBER("Subscriber"),
    PUBLISHER("Publisher"),
    SERVICE("Service"),
    OTHER("Engine / Other");

    private final String label;

    Channel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Maps the thread tag that follows the driver name in a trace header: {@code ST}, {@code PT}, and
     * {@code SST} for the subscriber service channel (which the server writes to its own trace file).
     */
    public static Channel fromThreadTag(String tag) {
        if (tag == null) {
            return OTHER;
        }
        return switch (tag) {
            case "ST" -> SUBSCRIBER;
            case "PT" -> PUBLISHER;
            case "SST" -> SERVICE;
            default -> OTHER;
        };
    }
}
