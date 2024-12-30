package dev.lukebemish.taskgraphrunner.runtime.mappings;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingNsCompleter;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MappingsUtil {
    private MappingsUtil() {}

    public static MappingTree reverse(MappingTree tree) {
        var newTree = new MemoryMappingTree();
        var reverser = tree.getDstNamespaces().isEmpty() ? newTree : new MappingSourceNsSwitch(newTree, tree.getDstNamespaces().getFirst(), true);
        try {
            tree.accept(reverser);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return newTree;
    }

    public static MappingTree merge(List<? extends MappingTree> trees) {
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

    public interface MappingProvider {
        MappingTree make(MappingInheritance inheritance) throws IOException;
    }

    public static MappingTree filledMerge(MappingInheritance inheritance, List<MappingProvider> trees) {
        try {
            var appliedTrees = new ArrayList<MappingTree>(trees.size());
            for (var tree : trees) {
                appliedTrees.add(tree.make(inheritance));
            }
            return merge(appliedTrees);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static MappingProvider provider(MappingTree tree) {
        return inheritance -> inheritance.fill(tree);
    }

    public static MappingTree filledChain(MappingInheritance inheritance, List<MappingProvider> trees) {
        try {
            var tree = trees.getFirst().make(inheritance);
            inheritance = inheritance.remap(tree, tree.getMaxNamespaceId() - 1);
            for (var other : trees.subList(1, trees.size())) {
                var mappings = other.make(inheritance);
                tree = chain(List.of(tree, mappings));
                inheritance = inheritance.remap(mappings, mappings.getMaxNamespaceId() - 1);
            }
            return tree;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static MappingTree chain(List<? extends MappingTree> trees) {
        // This is a *non-transitive* chain, since some mappings (looking at you, intermediary) just... leave stuff out.
        // Thus, its results may not be the most intuitive if you expect mapping "composition" or the like
        // This can be avoided by "completing" mappings by their inheritance
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
        MappingVisitor trimmedVisitor = MappingDropMissingSrcVisitor.create(trimmedTree);
        trimmedVisitor = new MappingDstNsReorder(trimmedVisitor, List.of("right"));
        trimmedVisitor = new MappingNsRenamer(trimmedVisitor, Map.of("namespace0", "left", "namespace"+i, "right"));
        for (i = trees.size(); i > 0; i--) {
            trimmedVisitor = new MappingNsCompleter(trimmedVisitor, Map.of("namespace" + i, "namespace" + (i - 1)));
        }
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
