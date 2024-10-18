package dev.lukebemish.taskgraphrunner.runtime.mappings;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class MappingsUtil {
    private MappingsUtil() {}

    public static MappingTree reverse(MappingTree tree) {
        if (tree.getMaxNamespaceId() < 0) {
            return tree;
        }
        var newTree = new MemoryMappingTree();
        newTree.visitNamespaces("left", List.of("right"));
        tree.getMetadata().forEach(entry -> newTree.visitMetadata(entry.getKey(), entry.getValue()));
        tree.getClasses().forEach(classMapping -> {
            var classDstName = classMapping.getDstName(0) == null ? classMapping.getSrcName() : classMapping.getDstName(0);
            newTree.visitClass(classDstName);
            newTree.visitDstName(MappedElementKind.CLASS, 0, classMapping.getSrcName());
            if (classMapping.getComment() != null) {
                newTree.visitComment(MappedElementKind.CLASS, classMapping.getComment());
            }
            classMapping.getFields().forEach(fieldMapping -> {
                var fieldDstName = fieldMapping.getDstName(0) == null ? fieldMapping.getSrcName() : fieldMapping.getDstName(0);
                // we need to remap the field descriptor to the new namespace
                newTree.visitField(fieldDstName, tree.mapDesc(fieldMapping.getSrcDesc(), 0));
                newTree.visitDstName(MappedElementKind.FIELD, 0, fieldMapping.getSrcName());
                try {
                    newTree.visitDstDesc(MappedElementKind.FIELD, 0, fieldMapping.getSrcDesc());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (fieldMapping.getComment() != null) {
                    newTree.visitComment(MappedElementKind.FIELD, fieldMapping.getComment());
                }
            });
            classMapping.getMethods().forEach(methodMapping -> {
                var methodDstName = methodMapping.getDstName(0) == null ? methodMapping.getSrcName() : methodMapping.getDstName(0);
                // we need to remap the method descriptor to the new namespace
                newTree.visitMethod(methodDstName, tree.mapDesc(methodMapping.getSrcDesc(), 0));
                newTree.visitDstName(MappedElementKind.METHOD, 0, methodMapping.getSrcName());
                try {
                    newTree.visitDstDesc(MappedElementKind.METHOD, 0, methodMapping.getSrcDesc());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (methodMapping.getComment() != null) {
                    newTree.visitComment(MappedElementKind.METHOD, methodMapping.getComment());
                }
                for (var arg : methodMapping.getArgs()) {
                    newTree.visitMethodArg(arg.getArgPosition(), arg.getLvIndex(), arg.getDstName(0));
                    newTree.visitDstName(MappedElementKind.METHOD_ARG, 0, arg.getSrcName());
                    if (arg.getComment() != null) {
                        newTree.visitComment(MappedElementKind.METHOD_ARG, arg.getComment());
                    }
                }
                for (var methodVar : methodMapping.getVars()) {
                    newTree.visitMethodVar(methodVar.getLvtRowIndex(), methodVar.getLvIndex(), methodVar.getStartOpIdx(), methodVar.getEndOpIdx(), methodVar.getDstName(0));
                    newTree.visitDstName(MappedElementKind.METHOD_VAR, 0, methodVar.getSrcName());
                    if (methodVar.getComment() != null) {
                        newTree.visitComment(MappedElementKind.METHOD_VAR, methodVar.getComment());
                    }
                }
            });
        });
        newTree.visitEnd();
        return newTree;
    }

    public static MappingTree merge(List<? extends MappingTreeView> trees) {
        var newTree = new MemoryMappingTree();
        newTree.visitNamespaces("left", List.of("right"));
        for (var tree : trees) {
            tree.getMetadata().reversed().forEach(entry -> newTree.visitMetadata(entry.getKey(), entry.getValue()));
        }
        var classSrcNames = new LinkedHashSet<>(trees.stream().flatMap(tree -> tree.getClasses().stream().map(MappingTreeView.ElementMappingView::getSrcName)).toList());
        for (var classSrcName : classSrcNames) {
            var classes = trees.stream().map(tree -> tree.getClass(classSrcName)).filter(Objects::nonNull).toList();
            if (classes.isEmpty()) {
                continue;
            }
            newTree.visitClass(classSrcName);
            newTree.visitDstName(MappedElementKind.CLASS, 0, classes.stream().map(cl -> cl.getDstName(0)).filter(Objects::nonNull).findFirst().orElse(classSrcName));
            classes.stream().map(MappingTreeView.ElementMappingView::getComment).filter(Objects::nonNull).findFirst()
                .ifPresent(comment -> newTree.visitComment(MappedElementKind.CLASS, comment));
            var fieldSrcNames = new LinkedHashSet<>(classes.stream().flatMap(cl -> cl.getFields().stream().map(MappingTreeView.ElementMappingView::getSrcName)).toList());
            for (var fieldSrcName : fieldSrcNames) {
                var fields = classes.stream().map(cl -> cl.getField(fieldSrcName, null)).filter(Objects::nonNull).toList();
                var fieldSrcDsc = fields.stream().map(MappingTreeView.MemberMappingView::getSrcDesc).filter(Objects::nonNull).findFirst().orElse(null);
                if (fields.isEmpty()) {
                    continue;
                }
                newTree.visitField(fieldSrcName, fieldSrcDsc);
                newTree.visitDstName(MappedElementKind.FIELD, 0, fields.stream().map(field -> field.getDstName(0)).filter(Objects::nonNull).findFirst().orElse(fieldSrcName));
                try {
                    newTree.visitDstDesc(MappedElementKind.FIELD, 0, fields.stream().map(field -> field.getDstDesc(0)).filter(Objects::nonNull).findFirst().orElse(fieldSrcDsc));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                fields.stream().map(MappingTreeView.ElementMappingView::getComment).filter(Objects::nonNull).findFirst()
                    .ifPresent(comment -> newTree.visitComment(MappedElementKind.FIELD, comment));
            }
            var visitedMethods = new LinkedHashMap<String, Set<String>>();
            for (var classTree : classes) {
                for (var method : classTree.getMethods()) {
                    var set = visitedMethods.computeIfAbsent(method.getSrcName(), k -> new LinkedHashSet<>());
                    if (method.getSrcDesc() != null) {
                        set.add(method.getSrcDesc());
                    }
                }
            }
            visitedMethods.forEach((srcMethodName, methodDescs) -> methodDescs.forEach(srcMethodDesc -> {
                var methods = classes.stream().map(cl -> cl.getMethod(srcMethodName, srcMethodDesc)).filter(Objects::nonNull).toList();
                if (methods.isEmpty()) {
                    return;
                }
                newTree.visitMethod(srcMethodName, srcMethodDesc);
                newTree.visitDstName(MappedElementKind.METHOD, 0, methods.stream().map(method -> method.getDstName(0)).filter(Objects::nonNull).findFirst().orElse(srcMethodName));
                try {
                    newTree.visitDstDesc(MappedElementKind.METHOD, 0, methods.stream().map(method -> method.getDstDesc(0)).filter(Objects::nonNull).findFirst().orElse(srcMethodDesc));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                methods.stream().map(MappingTreeView.ElementMappingView::getComment).filter(Objects::nonNull).findFirst()
                    .ifPresent(comment -> newTree.visitComment(MappedElementKind.METHOD, comment));
                Set<Integer> visitedArgs = new LinkedHashSet<>();
                for (var method : methods) {
                    // We only do anything with the lvt index -- it's what the parchment format and tiny use, and it's what mapping-io has TSRG use even though, it seems, TSRG might actually use the parameter index
                    method.getArgs().forEach(arg -> {
                        if (arg.getLvIndex() >= 0) {
                            visitedArgs.add(arg.getLvIndex());
                        }
                    });
                }
                for (int argIndex : visitedArgs) {
                    var args = methods.stream().map(method -> method.getArg(-1, argIndex, null)).filter(Objects::nonNull).toList();
                    if (args.isEmpty()) {
                        continue;
                    }
                    var argSrcName = args.stream().map(MappingTreeView.ElementMappingView::getSrcName).filter(Objects::nonNull).findFirst().orElse(null);
                    newTree.visitMethodArg(argIndex, argIndex, argSrcName);
                    args.stream().map(arg -> arg.getDstName(0)).filter(Objects::nonNull).findFirst().or(() -> Optional.ofNullable(argSrcName))
                        .ifPresent(argDstName -> newTree.visitDstName(MappedElementKind.METHOD_ARG, 0, argDstName));
                    args.stream().map(MappingTreeView.ElementMappingView::getComment).filter(Objects::nonNull).findFirst()
                        .ifPresent(comment -> newTree.visitComment(MappedElementKind.METHOD_ARG, comment));
                }
                Set<LVTIdentifier> visitedVars = new LinkedHashSet<>();
                for (var method : methods) {
                    method.getVars().forEach(var -> {
                        if (var.getLvIndex() >= 0 && var.getStartOpIdx() >= 0) {
                            visitedVars.add(new LVTIdentifier(var.getLvIndex(), var.getStartOpIdx()));
                        }
                    });
                }
                for (var lvtIdentifier : visitedVars) {
                    var lvIndex = lvtIdentifier.lvIndex;
                    var startOpIdx = lvtIdentifier.startOpIdx;
                    var vars = methods.stream().map(method -> method.getVar(-1, lvIndex, startOpIdx, -1, null)).filter(Objects::nonNull).toList();
                    if (vars.isEmpty()) {
                        continue;
                    }
                    var varSrcName = vars.stream().map(MappingTreeView.ElementMappingView::getSrcName).filter(Objects::nonNull).findFirst().orElse(null);
                    var lvtRowIndex = vars.stream().map(MappingTreeView.MethodVarMappingView::getLvtRowIndex).filter(i -> i >= 0).findFirst().orElse(-1);
                    var endOpIdx = vars.stream().map(MappingTreeView.MethodVarMappingView::getEndOpIdx).filter(i -> i >= 0).findFirst().orElse(-1);
                    newTree.visitMethodVar(lvtRowIndex, lvIndex, startOpIdx, endOpIdx, varSrcName);
                    vars.stream().map(var -> var.getDstName(0)).filter(Objects::nonNull).findFirst().or(() -> Optional.ofNullable(varSrcName))
                        .ifPresent(varDstName -> newTree.visitDstName(MappedElementKind.METHOD_VAR, 0, varDstName));
                    vars.stream().map(MappingTreeView.ElementMappingView::getComment).filter(Objects::nonNull).findFirst()
                        .ifPresent(comment -> newTree.visitComment(MappedElementKind.METHOD_VAR, comment));
                }
            }));
        }
        newTree.visitEnd();
        return newTree;
    }

    private record LVTIdentifier(int lvIndex, int startOpIdx) {}

    public static MappingTree chain(List<? extends MappingTreeView> mappingTrees) {
        var newTree = new MemoryMappingTree();
        newTree.visitNamespaces("left", List.of("right"));
        for (var tree : mappingTrees) {
            tree.getMetadata().forEach(entry -> newTree.visitMetadata(entry.getKey(), entry.getValue()));
        }
        if (mappingTrees.isEmpty()) {
            return newTree;
        }
        var startTree = mappingTrees.getFirst();
        var restTrees = mappingTrees.subList(1, mappingTrees.size());
        for (var classMapping : startTree.getClasses()) {
            newTree.visitClass(classMapping.getSrcName());
            var comment = classMapping.getComment();
            var dstName = classMapping.getDstName(0);
            var classMappings = new ArrayList<MappingTreeView.ClassMappingView>();
            if (dstName == null) {
                dstName = classMapping.getSrcName();
            }
            for (var tree : restTrees) {
                var singleClassMapping = tree.getClass(dstName);
                if (singleClassMapping != null) {
                    var mapped = singleClassMapping.getDstName(0);
                    if (mapped != null) {
                        dstName = mapped;
                    }
                    if (singleClassMapping.getComment() != null) {
                        comment = singleClassMapping.getComment();
                    }
                    classMappings.add(singleClassMapping);
                }
            }
            newTree.visitDstName(MappedElementKind.CLASS, 0, dstName == null ? classMapping.getSrcName() : dstName);
            if (comment != null) {
                newTree.visitComment(MappedElementKind.CLASS, comment);
            }

            for (var fieldMapping : classMapping.getFields()) {
                newTree.visitField(fieldMapping.getSrcName(), fieldMapping.getSrcDesc());
                var commentField = fieldMapping.getComment();
                var fieldDstName = fieldMapping.getDstName(0);
                if (fieldDstName == null) {
                    fieldDstName = fieldMapping.getSrcName();
                }
                var fieldDstDesc = fieldMapping.getDstDesc(0);
                if (fieldDstDesc == null) {
                    fieldDstDesc = fieldMapping.getSrcDesc();
                }
                for (var singleClassMapping : classMappings) {
                    var singleFieldMapping = singleClassMapping.getField(fieldDstName, null);
                    if (singleFieldMapping != null) {
                        var mapped = singleFieldMapping.getDstName(0);
                        if (mapped != null) {
                            fieldDstName = mapped;
                        }
                        if (singleFieldMapping.getComment() != null) {
                            commentField = singleFieldMapping.getComment();
                        }
                        fieldDstDesc = singleFieldMapping.getDstDesc(0);
                    }
                }
                newTree.visitDstName(MappedElementKind.FIELD, 0, fieldDstName == null ? fieldMapping.getSrcName() : fieldDstName);
                try {
                    newTree.visitDstDesc(MappedElementKind.FIELD, 0, fieldDstDesc);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (commentField != null) {
                    newTree.visitComment(MappedElementKind.FIELD, commentField);
                }
            }

            for (var methodMapping : classMapping.getMethods()) {
                newTree.visitMethod(methodMapping.getSrcName(), methodMapping.getSrcDesc());
                var commentMethod = methodMapping.getComment();
                var methodDstName = methodMapping.getDstName(0);
                var methodDstDesc = methodMapping.getDstDesc(0);
                if (methodDstName == null) {
                    methodDstName = methodMapping.getSrcName();
                }
                var methodMappings = new ArrayList<MappingTreeView.MethodMappingView>();
                for (var singleClassMapping : classMappings) {
                    var singleMethodMapping = singleClassMapping.getMethod(methodDstName, methodDstDesc);
                    if (singleMethodMapping != null) {
                        var mapped = singleMethodMapping.getDstName(0);
                        if (mapped != null) {
                            methodDstName = mapped;
                        }
                        if (singleMethodMapping.getComment() != null) {
                            commentMethod = singleMethodMapping.getComment();
                        }
                        methodDstDesc = singleMethodMapping.getDstDesc(0);
                        methodMappings.add(singleMethodMapping);
                    }
                }
                newTree.visitDstName(MappedElementKind.METHOD, 0, methodDstName == null ? methodMapping.getSrcName() : methodDstName);
                try {
                    newTree.visitDstDesc(MappedElementKind.METHOD, 0, methodDstDesc);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (commentMethod != null) {
                    newTree.visitComment(MappedElementKind.METHOD, commentMethod);
                }

                var visitedArgs = new LinkedHashSet<Integer>();
                var visitedVars = new LinkedHashSet<LVTIdentifier>();

                for (var singleMapping : methodMappings) {
                    for (var arg : singleMapping.getArgs()) {
                        if (arg.getLvIndex() >= 0) {
                            visitedArgs.add(arg.getLvIndex());
                        }
                    }
                    for (var var : singleMapping.getVars()) {
                        if (var.getLvIndex() >= 0 && var.getStartOpIdx() >= 0) {
                            visitedVars.add(new LVTIdentifier(var.getLvIndex(), var.getStartOpIdx()));
                        }
                    }
                }

                for (var lvIndex : visitedArgs) {
                    String commentArg = null;
                    String argDstName = null;
                    var arg = methodMapping.getArg(-1, lvIndex, null);
                    if (arg != null) {
                        argDstName = arg.getDstName(0);
                        commentArg = arg.getComment();
                    }
                    for (var singleMapping : methodMappings) {
                        var singleArg = singleMapping.getArg(-1, lvIndex, null);
                        if (singleArg != null) {
                            var mapped = singleArg.getDstName(0);
                            if (mapped != null) {
                                argDstName = mapped;
                            }
                            if (singleArg.getComment() != null) {
                                commentArg = singleArg.getComment();
                            }
                        }
                    }
                    newTree.visitMethodArg(lvIndex, lvIndex, arg == null ? null : arg.getSrcName());
                    newTree.visitDstName(MappedElementKind.METHOD_ARG, 0, argDstName);
                    if (commentArg != null) {
                        newTree.visitComment(MappedElementKind.METHOD_ARG, commentArg);
                    }
                }
                for (var lvtIdentifier : visitedVars) {
                    String varDstName = null;
                    String commentVar = null;
                    int lvtRowIndex = -1;
                    int endOpIdx = -1;
                    var methodVar = methodMapping.getVar(-1, lvtIdentifier.lvIndex, lvtIdentifier.startOpIdx, -1, null);
                    if (methodVar != null) {
                        varDstName = methodVar.getDstName(0);
                        commentVar = methodVar.getComment();
                        lvtRowIndex = methodVar.getLvtRowIndex();
                        endOpIdx = methodVar.getEndOpIdx();
                    }
                    for (var singleMapping : methodMappings) {
                        var singleVar = singleMapping.getVar(lvtRowIndex, lvtIdentifier.lvIndex, lvtIdentifier.startOpIdx, endOpIdx, varDstName);
                        if (singleVar != null) {
                            var mapped = singleVar.getDstName(0);
                            if (mapped != null) {
                                varDstName = mapped;
                            }
                            if (singleVar.getComment() != null) {
                                commentVar = singleVar.getComment();
                            }
                            if (singleVar.getLvtRowIndex() >= 0) {
                                lvtRowIndex = singleVar.getLvtRowIndex();
                            }
                            if (singleVar.getEndOpIdx() >= 0) {
                                endOpIdx = singleVar.getEndOpIdx();
                            }
                        }
                    }
                    newTree.visitMethodVar(lvtRowIndex, lvtIdentifier.lvIndex, lvtIdentifier.startOpIdx, endOpIdx, methodVar == null ? null : methodVar.getSrcName());
                    newTree.visitDstName(MappedElementKind.METHOD_VAR, 0, varDstName);
                    if (commentVar != null) {
                        newTree.visitComment(MappedElementKind.METHOD_VAR, commentVar);
                    }
                }
            }
        }
        newTree.visitEnd();
        return newTree;
    }
}
