package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;

abstract class GsonAdapter<T> extends TypeAdapter<T> {
    protected static final Gson GSON = new Gson();
}
