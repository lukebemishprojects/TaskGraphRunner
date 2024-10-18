package dev.lukebemish.taskgraphrunner.runtime.mappings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitableMappingTree;
import org.parchmentmc.feather.io.gson.MDCGsonAdapterFactory;
import org.parchmentmc.feather.io.gson.SimpleVersionAdapter;
import org.parchmentmc.feather.mapping.MappingDataContainer;
import org.parchmentmc.feather.mapping.VersionedMappingDataContainer;
import org.parchmentmc.feather.util.SimpleVersion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ParchmentMappingsReader {
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapterFactory(new MDCGsonAdapterFactory())
        .registerTypeAdapter(SimpleVersion.class, new SimpleVersionAdapter())
        .create();

    private ParchmentMappingsReader() {}

    public static MappingTree loadMappings(Path path) throws IOException {
        VisitableMappingTree tree = new MemoryMappingTree();
        tree.visitNamespaces("named", List.of("parchment"));
        MappingDataContainer container;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            container = GSON.fromJson(reader, VersionedMappingDataContainer.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (var packageData : container.getPackages()) {
            tree.visitClass(packageData.getName() + "/package-info");
            tree.visitDstName(MappedElementKind.CLASS, 0, packageData.getName()+ "/package-info");
            if (!packageData.getJavadoc().isEmpty()) {
                tree.visitComment(MappedElementKind.CLASS, String.join("\n", packageData.getJavadoc()));
            }
        }
        for (var classData : container.getClasses()) {
            tree.visitClass(classData.getName());
            tree.visitDstName(MappedElementKind.CLASS, 0, classData.getName());
            if (!classData.getJavadoc().isEmpty()) {
                tree.visitComment(MappedElementKind.CLASS, String.join("\n", classData.getJavadoc()));
            }
            for (var fieldData : classData.getFields()) {
                tree.visitField(fieldData.getName(), fieldData.getDescriptor());
                tree.visitDstName(MappedElementKind.FIELD, 0, fieldData.getName());
                tree.visitDstDesc(MappedElementKind.FIELD, 0, fieldData.getDescriptor());
                if (!fieldData.getJavadoc().isEmpty()) {
                    tree.visitComment(MappedElementKind.FIELD, String.join("\n", fieldData.getJavadoc()));
                }
            }
            for (var methodData : classData.getMethods()) {
                tree.visitMethod(methodData.getName(), methodData.getDescriptor());
                tree.visitDstName(MappedElementKind.METHOD, 0, methodData.getName());
                tree.visitDstDesc(MappedElementKind.METHOD, 0, methodData.getDescriptor());
                if (!methodData.getJavadoc().isEmpty()) {
                    tree.visitComment(MappedElementKind.METHOD, String.join("\n", methodData.getJavadoc()));
                }
                for (var arg : methodData.getParameters()) {
                    tree.visitMethodArg(-1, arg.getIndex(), null);
                    tree.visitDstName(MappedElementKind.METHOD_ARG, 0, arg.getName());
                    if (arg.getJavadoc() != null) {
                        tree.visitComment(MappedElementKind.METHOD_ARG, arg.getJavadoc());
                    }
                }
            }
        }
        return tree;
    }
}
