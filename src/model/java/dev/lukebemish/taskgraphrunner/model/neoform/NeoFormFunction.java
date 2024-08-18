package dev.lukebemish.taskgraphrunner.model.neoform;

import com.google.gson.annotations.SerializedName;

import java.util.List;

record NeoFormFunction(
    String version,
    List<String> args,
    @SerializedName("jvmargs") List<String> jvmArgs
) {
    // We don't care about the repository URL
}
