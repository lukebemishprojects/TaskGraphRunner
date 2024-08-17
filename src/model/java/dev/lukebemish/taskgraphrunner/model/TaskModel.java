package dev.lukebemish.taskgraphrunner.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public sealed interface TaskModel {
    String type();

    String name();

    static TaskModel fromJson(JsonElement json) {
        var type = json.getAsJsonObject().get("type").getAsString();
        Function<JsonObject, TaskModel> deserializer = switch (type) {
            case "downloadManifest" -> DownloadManifest::fromJson;
            case "downloadJson" -> DownloadJson::fromJson;
            case "downloadDistribution" -> DownloadDistribution::fromJson;
            case "downloadMappings" -> DownloadMappings::fromJson;
            case "splitClassesResources" -> SplitClassesResources::fromJson;
            case "listClasspath" -> ListClasspath::fromJson;
            case "injectSources" -> InjectSources::fromJson;
            case "patchSources" -> PatchSources::fromJson;
            case "retrieveData" -> RetrieveData::fromJson;
            case "tool" -> Tool::fromJson;
            default -> throw new IllegalArgumentException("Unknown task type `" + type + "`");
        };
        return deserializer.apply(json.getAsJsonObject());
    }

    JsonElement toJson();

    default List<Output> outputs() {
        return List.of(new Output(name(), "output"));
    }

    record Tool(String name, List<Argument> args) implements TaskModel {

        @Override
        public String type() {
            return "tool";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            var args = new JsonArray();
            for (var arg : this.args) {
                args.add(arg.toJson());
            }
            json.add("args", args);
            return json;
        }
        static Tool fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var args = new ArrayList<Argument>();
            for (var arg : json.getAsJsonArray("args")) {
                args.add(Argument.fromJson(arg));
            }
            return new Tool(name, args);
        }
    }

    record DownloadManifest(String name) implements TaskModel {
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

    record DownloadJson(String name, Input version, Input manifest) implements TaskModel {

        @Override
        public String type() {
            return "downloadJson";
        }

        @Override
        public JsonElement toJson() {
            var json = new JsonObject();
            json.addProperty("type", type());
            json.addProperty("name", name);
            json.add("version", version.toJson());
            json.add("manifest", manifest.toJson());
            return json;
        }

        static DownloadJson fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var version = Input.fromJson(json.get("version"));
            var manifest = Input.fromJson(json.get("manifest"));
            return new DownloadJson(name, version, manifest);
        }
    }

    record DownloadDistribution(String name, Input distribution, Input versionJson) implements TaskModel {
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
            var distribution = Input.fromJson(json.get("distribution"));
            var versionJson = Input.fromJson(json.get("versionJson"));
            return new DownloadDistribution(name, distribution, versionJson);
        }
    }

    record DownloadMappings(String name, Input distribution, Input versionJson) implements TaskModel {
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
            var distribution = Input.fromJson(json.get("distribution"));
            var versionJson = Input.fromJson(json.get("versionJson"));
            return new DownloadMappings(name, distribution, versionJson);
        }
    }

    record SplitClassesResources(String name, Input input, @Nullable Input excludePattern) implements TaskModel {
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
            if (excludePattern != null) {
                json.add("excludePatterns", excludePattern.toJson());
            }
            return json;
        }

        static SplitClassesResources fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var input = Input.fromJson(json.get("input"));
            var excludePatternElement = json.get("excludePatterns");
            var excludePattern = excludePatternElement == null ? null : Input.fromJson(json.get("excludePatterns"));
            return new SplitClassesResources(name, input, excludePattern);
        }

        @Override
        public List<Output> outputs() {
            return List.of(new Output(name(), "output"), new Output(name(), "resourcesOutput"));
        }
    }

    record ListClasspath(String name, Input versionJson, @Nullable Input additionalLibraries) implements TaskModel {
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
            if (additionalLibraries != null) {
                json.add("additionalLibraries", additionalLibraries.toJson());
            }
            return json;
        }

        static ListClasspath fromJson(JsonObject json) {
            var name = json.get("name").getAsString();
            var versionJson = Input.fromJson(json.get("versionJson"));
            var additionalLibrariesElement = json.get("additionalLibraries");
            var additionalLibraries = additionalLibrariesElement == null ? null : Input.fromJson(additionalLibrariesElement);
            return new ListClasspath(name, versionJson, additionalLibraries);
        }
    }

    record InjectSources(String name, Input input, Input sources) implements TaskModel {
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

    record PatchSources(String name, Input input, Input patches) implements TaskModel {
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

    record RetrieveData(String name, Input input, String path) implements TaskModel {
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
