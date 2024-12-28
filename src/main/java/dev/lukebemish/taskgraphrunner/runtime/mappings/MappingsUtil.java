package dev.lukebemish.taskgraphrunner.runtime.mappings;

import net.fabricmc.mappingio.FlatMappingVisitor;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MappingsUtil {
    private MappingsUtil() {}

    public static MappingTree reverse(MappingTree tree) {
        if (tree.getMaxNamespaceId() < 0) {
            return tree;
        }
        var newTree = new MemoryMappingTree();
        var reverser = new MappingSourceNsSwitch(newTree, tree.getDstNamespaces().getFirst(), true);
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

    public static MappingTree chain(List<? extends MappingTreeView> trees) {
        // This is a *non-transitive* chain, since some mappings (looking at you, intermediary) just... leave stuff out.
        // Thus, its results may not be the most intuitive if you expect mapping "composition" or the like
        var newTree = new MemoryMappingTree();
        newTree.setSrcNamespace("namespace0");
        int i = 0;
        for (var tree : trees) {
            MappingVisitor visitor = newTree;

            var leftNs = "namespace"+i;
            var rightNs = "namespace"+(i+1);

            if (!tree.getDstNamespaces().isEmpty()) {
                visitor = new MappingDstNsReorder(visitor, List.of(rightNs));
            }

            Map<String, String> rename = new LinkedHashMap<>();
            rename.put(tree.getSrcNamespace(), leftNs);
            if (!tree.getDstNamespaces().isEmpty()) {
                rename.put(tree.getDstNamespaces().getFirst(), rightNs);
            }
            visitor = new MappingNsRenamer(visitor, rename);
            try {
                tree.accept(visitor);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            i++;
        }

        var trimmedTree = new MemoryMappingTree();
        trimmedTree.setSrcNamespace("left");
        var flatVisitor = FlatMappingVisitor.fromRegularVisitor(trimmedTree);
        flatVisitor = new ForwardingFlatVisitor(flatVisitor) {
            private boolean checkNames(@Nullable String[] dstNames) {
                return dstNames != null && dstNames.length > 0 && Arrays.stream(dstNames).allMatch(Objects::nonNull);
            }

            @Override
            public boolean visitClass(String srcName, @Nullable String[] dstNames) throws IOException {
                return checkNames(dstNames) && super.visitClass(srcName, dstNames);
            }

            @Override
            public boolean visitMethod(String srcClsName, String srcName, @Nullable String srcDesc, @Nullable String[] dstClsNames, @Nullable String[] dstNames, @Nullable String[] dstDescs) throws IOException {
                return checkNames(dstNames) && super.visitMethod(srcClsName, srcName, srcDesc, dstClsNames, dstNames, dstDescs);
            }

            @Override
            public boolean visitField(String srcClsName, String srcName, @Nullable String srcDesc, @Nullable String[] dstClsNames, @Nullable String[] dstNames, @Nullable String[] dstDescs) throws IOException {
                return checkNames(dstNames) && super.visitField(srcClsName, srcName, srcDesc, dstClsNames, dstNames, dstDescs);
            }

            @Override
            public boolean visitMethodArg(String srcClsName, String srcMethodName, @Nullable String srcMethodDesc, int argPosition, int lvIndex, @Nullable String srcName, @Nullable String[] dstClsNames, @Nullable String[] dstMethodNames, @Nullable String[] dstMethodDescs, String[] dstNames) throws IOException {
                return checkNames(dstNames) && super.visitMethodArg(srcClsName, srcMethodName, srcMethodDesc, argPosition, lvIndex, srcName, dstClsNames, dstMethodNames, dstMethodDescs, dstNames);
            }

            @Override
            public boolean visitMethodVar(String srcClsName, String srcMethodName, @Nullable String srcMethodDesc, int lvtRowIndex, int lvIndex, int startOpIdx, int endOpIdx, @Nullable String srcName, @Nullable String[] dstClsNames, @Nullable String[] dstMethodNames, @Nullable String[] dstMethodDescs, String[] dstNames) throws IOException {
                return checkNames(dstNames) && super.visitMethodVar(srcClsName, srcMethodName, srcMethodDesc, lvtRowIndex, lvIndex, startOpIdx, endOpIdx, srcName, dstClsNames, dstMethodNames, dstMethodDescs, dstNames);
            }
        };
        MappingVisitor trimmedVisitor = new MappingDstNsReorder(flatVisitor.asRegularVisitor(), List.of("right"));
        trimmedVisitor = new MappingNsRenamer(trimmedVisitor, Map.of("namespace0", "left", "namespace"+i, "right"));
        try {
            newTree.accept(trimmedVisitor);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return trimmedTree;
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
}
