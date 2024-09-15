package dev.lukebemish.taskgraphrunner.model.conversion;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

record NeoFormConfig(
    int spec,
    String version,
    boolean official,
    @SerializedName("java_target") int javaTarget,
    String encoding,
    Map<String, List<NeoFormStep>> steps,
    Map<String, JsonElement> data,
    Map<String, NeoFormFunction> functions,
    Map<String, List<String>> libraries
) {

}
