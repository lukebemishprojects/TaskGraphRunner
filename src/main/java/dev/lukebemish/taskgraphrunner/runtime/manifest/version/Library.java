package dev.lukebemish.taskgraphrunner.runtime.manifest.version;

import java.util.List;

public record Library(String name, List<Rule> rules) {
}
