package dev.lukebemish.taskgraphrunner.runtime.mappings;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class MappingsUtil {
    private MappingsUtil() {}

    public static MappingTree reverse(MappingTree tree) {
        if (tree.getMaxNamespaceId() < 0) {
            return tree;
        }
        var newTree = new MemoryMappingTree();
        var reverser = new MappingSourceNsSwitch(newTree, tree.getDstNamespaces().getFirst());
        try {
            tree.accept(reverser);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return newTree;
    }

    public static MappingTree merge(List<? extends MappingTreeView> trees) {
        var newTree = new MemoryMappingTree();
        for (var tree : trees) {
            MappingVisitor visitor = newTree;
            if (!tree.getDstNamespaces().isEmpty()) {
                visitor = new MappingDstNsReorder(visitor, List.of("right"));
            }
            Map<String, String> rename = new LinkedHashMap<>();
            rename.put(tree.getSrcNamespace(), "left");
            if (!tree.getDstNamespaces().isEmpty()) {
                rename.put(tree.getDstNamespaces().getFirst(), "right");
            }
            visitor = new MappingNsRenamer(visitor, rename);
            try {
                tree.accept(visitor);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return newTree;
    }

    public static MappingTree fixInnerClasses(MappingTree tree) {
        if (tree.getDstNamespaces().isEmpty()) {
            return tree;
        }

        var renamesTree = new MemoryMappingTree();
        String dstNamespace = "oldDst";

        renamesTree.visitNamespaces(dstNamespace, List.of("right"));
        int targetNamespace = tree.getNamespaceId(tree.getDstNamespaces().getLast());
        for (var clazz : tree.getClasses()) {
            var dstName = clazz.getDstName(targetNamespace);
            var srcName = clazz.getSrcName();
            if (dstName == null) {
                continue;
            }
            var dstParts = dstName.split("\\$");
            var parts = srcName.split("\\$");
            if (parts.length != dstParts.length || parts.length < 2) {
                continue;
            }
            var firstSrcPart = parts[0];
            var firstDstPart = dstParts[0];
            var remappedFirstSrcPart = tree.mapClassName(firstSrcPart, targetNamespace);
            if (remappedFirstSrcPart.equals(firstDstPart)) {
                continue;
            }
            dstParts[0] = remappedFirstSrcPart;
            var remappedDstName = String.join("$", dstParts);
            renamesTree.visitClass(dstName);
            renamesTree.visitDstName(MappedElementKind.CLASS, 0, remappedDstName);
        }
        renamesTree.visitEnd();

        var outTree = new MemoryMappingTree();
        var visitor = new ForwardingMappingVisitor(outTree) {
            @Override
            public void visitDstName(MappedElementKind targetKind, int namespace, String name) throws IOException {
                if (targetKind == MappedElementKind.CLASS) {
                    var mapped = renamesTree.mapClassName(name, namespace);
                    if (mapped != null) {
                        super.visitDstName(targetKind, namespace, mapped);
                        return;
                    }
                }
                super.visitDstName(targetKind, namespace, name);
            }

            @Override
            public void visitDstDesc(MappedElementKind targetKind, int namespace, String desc) throws IOException {
                super.visitDstDesc(targetKind, namespace, renamesTree.mapDesc(desc, namespace));
            }
        };

        try {
            tree.accept(visitor);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return outTree;
    }

    private record LVTIdentifier(int lvIndex, int startOpIdx) {}

    public static MappingTree chain(List<? extends MappingTreeView> mappingTrees) {
        // Seemingly no better way to do this -- especially as intermediary mappings may be missing stuff
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
