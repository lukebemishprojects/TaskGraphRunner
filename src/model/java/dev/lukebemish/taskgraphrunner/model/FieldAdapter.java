package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

abstract class FieldAdapter<O> extends GsonAdapter<O> {
    private final Builder<O>.Built built;

    public FieldAdapter() {
        var builder = new Builder<O>();
        var function = this.build(builder);
        this.built = builder.new Built(function);
    }

    @Override
    public void write(JsonWriter out, O value) throws IOException {
        built.write(out, value);
    }

    @Override
    public O read(JsonReader in) throws IOException {
        return built.read(in);
    }

    public static final class Builder<O> {
        private final Map<Key<?>, Function<O, ?>> getters = new IdentityHashMap<>();
        private final Map<String, Key<?>> keys = new HashMap<>();
        private final Map<Key<?>, Type> types = new IdentityHashMap<>();

        private Builder() {}

        private final class Built extends GsonAdapter<O> {
            private final Function<Values, O> builder;

            private Built(Function<Values, O> builder) {
                this.builder = builder;
            }

            @SuppressWarnings({"unchecked", "rawtypes"})
            @Override
            public void write(JsonWriter out, O value) throws IOException {
                out.beginObject();
                for (var entry : keys.entrySet()) {
                    var fieldValue = getters.get(entry.getValue()).apply(value);
                    if (fieldValue != null) {
                        out.name(entry.getKey());
                        ((TypeAdapter) GSON.getAdapter(TypeToken.get(types.get(entry.getValue())))).write(out, fieldValue);
                    }
                }
                out.endObject();
            }

            @Override
            public O read(JsonReader in) throws IOException {
                var values = new Values();
                in.beginObject();
                while (in.hasNext()) {
                    var name = in.nextName();
                    var key = keys.get(name);
                    if (key != null) {
                        var type = types.get(key);
                        var value = GSON.getAdapter(TypeToken.get(type)).read(in);
                        values.values.put(key, value);
                    } else {
                        in.skipValue();
                    }
                }
                in.endObject();
                return builder.apply(values);
            }
        }
        public <T> Key<T> field(String name, Function<O, T> getter, Type type) {
            if (keys.containsKey(name)) {
                throw new IllegalArgumentException("Field with name `"+name+"` already added");
            }
            var key = new Key<T>();
            getters.put(key, getter);
            keys.put(name, key);
            types.put(key, type);
            return key;
        }
    }

    public abstract Function<Values, O> build(Builder<O> builder);

    public static final class Key<T> {}

    public static final class Values {
        private final Map<Key<?>, Object> values = new IdentityHashMap<>();

        @SuppressWarnings("unchecked")
        public <T> T get(Key<T> key) {
            return (T) values.get(key);
        }
    }
}
