package dev.lukebemish.taskgraphrunner.model.conversion;

import dev.lukebemish.taskgraphrunner.model.Argument;
import dev.lukebemish.taskgraphrunner.model.Input;

import java.util.List;

public record AdditionalJst(List<Input> classpath, List<Argument> arguments) {}
