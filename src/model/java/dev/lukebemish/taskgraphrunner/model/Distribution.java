package dev.lukebemish.taskgraphrunner.model;

import java.util.Locale;

public enum Distribution {
    CLIENT,
    SERVER,
    JOINED;

    Input.DirectInput direct() {
        return new Input.DirectInput(new Value.DirectStringValue(this.name().toLowerCase(Locale.ROOT)));
    }

    public static Distribution fromString(String value) {
        return Distribution.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
