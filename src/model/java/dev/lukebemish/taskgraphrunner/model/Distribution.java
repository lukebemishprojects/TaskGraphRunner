package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public enum Distribution {
    CLIENT,
    SERVER,
    JOINED;

    Input.DirectInput direct() {
        return new Input.DirectInput(this.name().toLowerCase(Locale.ROOT));
    }

    public static Distribution fromString(String value) {
        return Distribution.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
