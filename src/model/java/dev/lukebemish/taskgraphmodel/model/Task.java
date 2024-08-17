package dev.lukebemish.taskgraphmodel.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.function.Function;

public sealed interface Task {
    String type();

    String name();

    static Task fromJson(JsonElement json) {
        var type = json.getAsJsonObject().get("type").getAsString();
        Function<JsonObject, Task> deserializer = switch (type) {
            case "downloadManifest" -> DownloadManifest::fromJson;
            case "downloadJson" -> DownloadJson::fromJson;
            case "downloadDistribution" -> DownloadDistribution::fromJson;
            case "downloadMappings" -> DownloadMappings::fromJson;
            case "splitClassesResources" -> SplitClassesResources::fromJson;
            case "listClasspath" -> ListClasspath::fromJson;
            case "injectSources" -> InjectSources::fromJson;
            case "patchSources" -> PatchSources::fromJson;
            case "retrieveData" -> RetrieveData::fromJson;
            default -> throw new IllegalArgumentException("Unknown task type `" + type + "`");
        };
        return deserializer.apply(json.getAsJsonObject());
    }

    JsonElement toJson();

    default List<Output> outputs() {
        return List.of(new Output(name(), "output"));
    }

    record DownloadManifest(String name) implements Task {
        @Override
        public String type() {
            return "downloadManifest";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            return json;
        }

        static DownloadManifest fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            return new DownloadManifest(name);
        }
    }

    record DownloadJson(String name, String version, Input manifest) implements Task {

        @Override
        public String type() {
            return "downloadJson";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            json.addProperty("version", version);
            json.add("manifest", manifest.toJson());
            return json;
        }

        static DownloadJson fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var version = json.get("version").getAsString();
            var manifest = Input.fromJson(json.get("manifest"));
            return new DownloadJson(name, version, manifest);
        }
    }

    record DownloadDistribution(String name, Distribution distribution, Input versionJson) implements Task {
        @Override
        public String type() {
            return "downloadDistribution";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            json.add("versionJson", versionJson.toJson());
            json.add("distribution", distribution.toJson());
            return json;
        }

        static DownloadDistribution fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var distribution = Distribution.fromJson(json.get("distribution"));
            var versionJson = Input.fromJson(json.get("versionJson"));
            return new DownloadDistribution(name, distribution, versionJson);
        }
    }

    record DownloadMappings(String name, Distribution distribution, Input versionJson) implements Task {
        @Override
        public String type() {
            return "downloadMappings";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            json.add("versionJson", versionJson.toJson());
            json.add("distribution", distribution.toJson());
            return json;
        }

        static DownloadMappings fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var distribution = Distribution.fromJson(json.get("distribution"));
            var versionJson = Input.fromJson(json.get("versionJson"));
            return new DownloadMappings(name, distribution, versionJson);
        }
    }

    record SplitClassesResources(String name, Input input) implements Task {
        @Override
        public String type() {
            return "splitClassesResources";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            json.add("input", input.toJson());
            return json;
        }

        static SplitClassesResources fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var input = Input.fromJson(json.get("input"));
            return new SplitClassesResources(name, input);
        }

        @Override
        public List<Output> outputs() {
            return List.of(new Output(name(), "output"), new Output(name(), "resourcesOutput"));
        }
    }

    record ListClasspath(String name, Input versionJson) implements Task {
        @Override
        public String type() {
            return "listClasspath";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            json.add("versionJson", versionJson.toJson());
            return json;
        }

        static ListClasspath fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var versionJson = Input.fromJson(json.get("versionJson"));
            return new ListClasspath(name, versionJson);
        }
    }

    record InjectSources(String name, Input input, Input sources) implements Task {
        @Override
        public String type() {
            return "injectSources";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            json.add("input", input.toJson());
            json.add("sources", sources.toJson());
            return json;
        }

        static InjectSources fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var input = Input.fromJson(json.get("input"));
            var sources = Input.fromJson(json.get("sources"));
            return new InjectSources(name, input, sources);
        }
    }

    record PatchSources(String name, Input input, Input patches) implements Task {
        @Override
        public String type() {
            return "patchSources";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            json.add("input", input.toJson());
            json.add("patches", patches.toJson());
            return json;
        }

        static PatchSources fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var input = Input.fromJson(json.get("input"));
            var patches = Input.fromJson(json.get("patches"));
            return new PatchSources(name, input, patches);
        }
    }

    record RetrieveData(String name, Input input, String path) implements Task {
        @Override
        public String type() {
            return "retrieveData";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            json.add("input", input.toJson());
            json.addProperty("path", path);
            return json;
        }

        static RetrieveData fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var input = Input.fromJson(json.get("input"));
            var path = json.get("path").getAsString();
            return new RetrieveData(name, input, path);
        }
    }
}
