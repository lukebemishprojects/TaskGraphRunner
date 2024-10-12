package dev.lukebemish.taskgraphrunner.signatures;

import java.util.function.Predicate;

@FunctionalInterface
public interface ClassFinder extends Predicate<String> {
}
