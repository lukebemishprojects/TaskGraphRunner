package dev.lukebemish.taskgraphrunner.runtime.mappings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.mappingio.tree.MappingTree;
import org.parchmentmc.feather.io.gson.MDCGsonAdapterFactory;
import org.parchmentmc.feather.io.gson.SimpleVersionAdapter;
import org.parchmentmc.feather.mapping.ImmutableVersionedMappingDataContainer;
import org.parchmentmc.feather.mapping.MappingDataBuilder;
import org.parchmentmc.feather.mapping.VersionedMappingDataContainer;
import org.parchmentmc.feather.util.SimpleVersion;

import java.io.IOException;
import java.io.Writer;

public class ParchmentMappingWriter implements MappingsSourceImpl.MappingConsumer {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapterFactory(new MDCGsonAdapterFactory())
        .registerTypeAdapter(SimpleVersion.class, new SimpleVersionAdapter())
        .create();

    private final Writer writer;
    private final MappingDataBuilder builder = new MappingDataBuilder();

    public ParchmentMappingWriter(Writer writer) {
        this.writer = writer;
    }

    @Override
    public void close() throws IOException {
        try {
            writer.write(GSON.toJson(new ImmutableVersionedMappingDataContainer(VersionedMappingDataContainer.CURRENT_FORMAT, builder.getPackages(), builder.getClasses())));
        } finally {
            writer.close();
        }
    }

    @Override
    public void accept(MappingTree mappings) {
        boolean hasDst = !mappings.getDstNamespaces().isEmpty();
        for (var classMapping : mappings.getClasses()) {
            var dstName = classMapping.getSrcName();
            if (hasDst) {
                dstName = classMapping.getDstName(0);
                if (dstName == null) {
                    dstName = classMapping.getSrcName();
                }
            }
            boolean isPackage = dstName.endsWith("/package-info");
            if (isPackage) {
                if (classMapping.getComment() != null) {
                    var packageBuilder = builder.createPackage(dstName.substring(0, dstName.length() - "/package-info".length()));
                    packageBuilder.addJavadoc(classMapping.getComment().split("\n"));
                }
            } else {
                var classBuilder = builder.createClass(dstName);
                boolean classHasDocs = false;
                if (classMapping.getComment() != null) {
                    classBuilder.addJavadoc(classMapping.getComment().split("\n"));
                    classHasDocs = true;
                }
                for (var method : classMapping.getMethods()) {
                    var methodDstName = method.getSrcName();
                    if (hasDst) {
                        methodDstName = method.getDstName(0);
                        if (methodDstName == null) {
                            methodDstName = method.getSrcName();
                        }
                    }
                    var dstDesc = method.getSrcDesc();
                    if (hasDst) {
                        dstDesc = method.getDstDesc(0);
                        if (dstDesc == null) {
                            dstDesc = method.getSrcDesc();
                        }
                    }
                    boolean methodHasDocs = false;
                    var methodBuilder = classBuilder.createMethod(methodDstName, dstDesc);
                    if (method.getComment() != null) {
                        methodBuilder.addJavadoc(method.getComment().split("\n"));
                        classHasDocs = true;
                        methodHasDocs = true;
                    }
                    for (var arg : method.getArgs()) {
                        byte index = (byte) arg.getLvIndex();
                        if (index >= 0) {
                            var argBuilder = methodBuilder.createParameter(index);
                            var argDstName = arg.getSrcName();
                            if (hasDst) {
                                argDstName = arg.getDstName(0);
                                if (argDstName == null) {
                                    argDstName = arg.getSrcName();
                                }
                            }
                            boolean argHasDocs = false;
                            if (argDstName != null) {
                                methodHasDocs = true;
                                argHasDocs = true;
                                argBuilder.setName(argDstName);
                            }
                            if (arg.getComment() != null) {
                                methodHasDocs = true;
                                argHasDocs = true;
                                argBuilder.addJavadoc(arg.getComment().split("\n"));
                            }
                            if (!argHasDocs) {
                                methodBuilder.removeParameter(index);
                            }
                        }
                    }
                    if (!methodHasDocs) {
                        classBuilder.removeMethod(methodDstName, dstDesc);
                    }
                }
                for (var field : classMapping.getFields()) {
                    var fieldDstName = field.getSrcName();
                    if (hasDst) {
                        fieldDstName = field.getDstName(0);
                        if (fieldDstName == null) {
                            fieldDstName = field.getSrcName();
                        }
                    }
                    var dstDesc = field.getSrcDesc();
                    if (hasDst) {
                        dstDesc = field.getDstDesc(0);
                        if (dstDesc == null) {
                            dstDesc = field.getSrcDesc();
                        }
                    }
                    if (field.getComment() != null) {
                        classHasDocs = true;
                        var fieldBuilder = classBuilder.createField(fieldDstName, dstDesc);
                        fieldBuilder.addJavadoc(field.getComment().split("\n"));
                    }
                }
                if (!classHasDocs) {
                    builder.removeClass(dstName);
                }
            }
        }
    }
}
