package dev.lukebemish.taskgraphrunner.runtime.mappings;

import net.fabricmc.mappingio.FlatMappingVisitor;
import net.fabricmc.mappingio.adapter.RegularAsFlatMappingVisitor;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class MappingInheritance {
    private final Map<String, ClassInheritance> classes;

    private MappingInheritance(Map<String, ClassInheritance> classes) {
        this.classes = classes;
    }

    public MappingTree fill(MappingTreeView input) throws IOException {
        var output = new MemoryMappingTree();
        input.accept(output);
        output.visitNamespaces(input.getSrcNamespace(), input.getDstNamespaces());
        for (var classInheritance : classes.values()) {
            classInheritance.fill(input, new RegularAsFlatMappingVisitor(output));
        }
        output.visitEnd();
        return output;
    }

    public MappingInheritance remap(MappingTree tree, int namespace) {
        var newClasses = classes.entrySet().stream().collect(Collectors.toMap(e -> {
            var newName = tree.mapClassName(e.getKey(), namespace);
            return newName == null ? e.getKey() : newName;
        }, e -> {
            return e.getValue().remap(tree, namespace);
        }));
        return new MappingInheritance(newClasses);
    }

    public static MappingInheritance read(Path path) throws IOException {
        try (var is = Files.newInputStream(path);
             var zis = new ZipInputStream(is)) {
            ZipEntry entry;
            Map<String, ClassInheritance> map = new LinkedHashMap<>();
            while ((entry = zis.getNextEntry()) != null) {
                var entryName = entry.getName();
                if (!entryName.startsWith("META-INF/") && entryName.endsWith(".class")) {
                    var expectedDotsClassName = entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
                    AtomicReference<String> nameHolder = new AtomicReference<>();
                    AtomicReference<String> parentHolder = new AtomicReference<>();
                    List<String> interfacesHolder = new ArrayList<>();
                    Map<String, MethodInheritance> methodsHolder = new LinkedHashMap<>();
                    Map<String, FieldInheritance> fieldsHolder = new LinkedHashMap<>();
                    AtomicBoolean enabled = new AtomicBoolean(true);
                    Set<String> bridges = new LinkedHashSet<>();
                    var reader = new ClassReader(zis);
                    var visitor = new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                            if (!name.equals(expectedDotsClassName)) {
                                enabled.set(false);
                            }
                            if (enabled.getPlain()) {
                                nameHolder.setPlain(name.replace('.', '/'));
                                parentHolder.setPlain(superName.replace('.', '/'));
                                for (var interfaceName : interfaces) {
                                    interfacesHolder.add(interfaceName.replace('.', '/'));
                                }
                            }
                            super.visit(version, access, name, signature, superName, interfaces);
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                            if (enabled.getPlain()) {
                                fieldsHolder.put(name, new FieldInheritance(name, descriptor));
                            }
                            return super.visitField(access, name, descriptor, signature, value);
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            if (enabled.getPlain()) {
                                boolean isBridge = (access & Opcodes.ACC_BRIDGE) != 0;
                                if (isBridge) {
                                    bridges.add(name+descriptor);
                                }
                                methodsHolder.put(name+descriptor, new MethodInheritance(name, descriptor, null, null));
                            }
                            return super.visitMethod(access, name, descriptor, signature, exceptions);
                        }
                    };
                    reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                    if (enabled.getPlain()) {
                        // Is this the _best_ heuristic? Eh. But it works, in theory
                        for (var bridge : bridges) {
                            var bridgeMethod = methodsHolder.get(bridge);
                            var name = bridgeMethod.name;
                            var eligible = methodsHolder.values().stream().filter(m -> !bridges.contains(m.name + m.descriptor) && m.name.equals(name)).toList();
                            if (eligible.size() == 1) {
                                var method = eligible.getFirst();
                                methodsHolder.put(bridge, new MethodInheritance(bridgeMethod.name, bridgeMethod.descriptor, null, method.descriptor));
                            }
                        }
                        map.put(nameHolder.getPlain(), new ClassInheritance(nameHolder.getPlain(), parentHolder.getPlain(), interfacesHolder, methodsHolder, fieldsHolder));
                    }
                }
            }
            var inheritance = new MappingInheritance(map);
            return inheritance.withParents();
        }
    }

    private record ClassInheritance(String name, String parent, List<String> interfaces,
                                    Map<String, MethodInheritance> methods, Map<String, FieldInheritance> fields) {

        private ClassInheritance remap(MappingTree tree, int namespace) {
            String newName = tree.mapClassName(name, namespace);
            String newParent = tree.mapClassName(parent, namespace);
            if (newName == null) {
                newName = name;
            }
            if (newParent == null) {
                newParent = parent;
            }
            List<String> newInterfaces = interfaces.stream().map(i -> {
                var name = tree.mapClassName(i, namespace);
                return name == null ? i : name;
            }).toList();
            Map<String, MethodInheritance> newMethods = methods.values().stream().map(m -> m.remap(tree, namespace, this))
                .collect(Collectors.toMap(m -> m.name + m.descriptor, m -> m));
            Map<String, FieldInheritance> newFields = fields.values().stream().map(f -> f.remap(tree, namespace, this))
                .collect(Collectors.toMap(f -> f.name, f -> f));
            return new ClassInheritance(newName, newParent, newInterfaces, newMethods, newFields);
        }

        private ClassInheritance withParents(MappingInheritance mappingInheritance) {
            return new ClassInheritance(name, parent, interfaces, methods.values().stream().map(methodInheritance -> methodInheritance.withParents(this, mappingInheritance))
                .collect(Collectors.toMap(m -> m.name + m.descriptor, m -> m)), fields);
        }

        private void fill(MappingTreeView input, FlatMappingVisitor output) throws IOException {
            // Fields are ignored -- they cannot be inherited
            for (var methodInheritance : methods.values()) {
                methodInheritance.fill(this, input, output);
            }
        }
    }

    public MappingInheritance withParents() {
        return new MappingInheritance(classes.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().withParents(this))));
    }

    private record FieldInheritance(String name, String descriptor) {

        private FieldInheritance remap(MappingTree tree, int namespace, ClassInheritance clazz) {
            String newName = name;
            String newDescriptor = tree.mapDesc(descriptor, namespace);
            var classMapping = tree.getClass(clazz.name);
            if (classMapping != null) {
                var fieldMapping = classMapping.getField(name, descriptor);
                if (fieldMapping != null) {
                    newName = fieldMapping.getDstName(namespace);
                }
            }
            if (newName == null) {
                newName = name;
            }
            return new FieldInheritance(newName, newDescriptor);
        }
    }

    private record MethodInheritance(String name, String descriptor, @Nullable String from,
                                     @Nullable String bridgeDescriptor) {

        private MethodInheritance withParents(ClassInheritance classInheritance, MappingInheritance mappingInheritance) {
            if (from != null && bridgeDescriptor != null) {
                return this;
            }
            String found = findMethod(mappingInheritance, classInheritance.name, name, descriptor);
            if (found != null && !found.equals(classInheritance.name)) {
                return new MethodInheritance(name, descriptor, found, bridgeDescriptor);
            }
            return this;
        }

        private String findMethod(MappingInheritance mappingInheritance, String className, String name, String descriptor) {
            var inheritance = mappingInheritance.classes.get(className);
            if (inheritance != null) {
                var found = findMethod(mappingInheritance, inheritance.parent, name, descriptor);
                if (found != null) {
                    return found;
                }
                for (var interfaceName : inheritance.interfaces) {
                    found = findMethod(mappingInheritance, interfaceName, name, descriptor);
                    if (found != null) {
                        return found;
                    }
                }
                if (inheritance.methods.get(name + descriptor) != null) {
                    return className;
                }
            }
            return null;
        }

        private MethodInheritance remap(MappingTree tree, int namespace, ClassInheritance clazz) {
            String newDescriptor = tree.mapDesc(descriptor, namespace);
            String newFrom = from;
            if (newFrom != null) {
                newFrom = tree.mapClassName(from, namespace);
            }
            String newBridgeDescriptor = bridgeDescriptor;
            if (newBridgeDescriptor != null) {
                newBridgeDescriptor = tree.mapDesc(bridgeDescriptor, namespace);
            }
            String newName = null;
            var classMapping = tree.getClass(clazz.name);
            if (classMapping != null) {
                var methodMapping = classMapping.getMethod(name, descriptor);
                if (methodMapping != null) {
                    newName = methodMapping.getDstName(namespace);
                }
                if (newName == null && bridgeDescriptor != null) {
                    methodMapping = classMapping.getMethod(name, bridgeDescriptor);
                    if (methodMapping != null) {
                        newName = methodMapping.getDstName(namespace);
                    }
                }
            }
            if (newName == null) {
                var sourceClassMapping = tree.getClass(from);
                if (sourceClassMapping != null) {
                    var methodMapping = sourceClassMapping.getMethod(name, descriptor);
                    if (methodMapping != null) {
                        newName = methodMapping.getDstName(namespace);
                    }
                }
            }
            if (newName == null) {
                newName = name;
            }
            return new MethodInheritance(newName, newDescriptor, newFrom, newBridgeDescriptor);
        }

        public void fill(ClassInheritance classInheritance, MappingTreeView input, FlatMappingVisitor output) throws IOException {
            if (from == null && bridgeDescriptor == null) {
                return;
            }
            var thisClass = input.getClass(classInheritance.name);
            var inheritArgs = true;
            if (thisClass != null) {
                var thisMethod = thisClass.getMethod(name, descriptor);
                if (thisMethod != null) {
                    if (IntStream.range(0, input.getMaxNamespaceId()).noneMatch(i -> thisMethod.getDstName(i) != null)) {
                        inheritArgs = false;
                    } else {
                        return;
                    }
                }
                if (bridgeDescriptor != null) {
                    var bridgeMethod = thisClass.getMethod(name, bridgeDescriptor);
                    if (bridgeMethod != null && IntStream.range(0, input.getMaxNamespaceId()).anyMatch(i -> bridgeMethod.getDstName(i) != null)) {
                        output.visitMethod(thisClass.getSrcName(), name, descriptor, dstNames(input, bridgeMethod));
                        if (inheritArgs) {
                            for (var arg : bridgeMethod.getArgs()) {
                                output.visitMethodArg(thisClass.getSrcName(), name, descriptor, arg.getArgPosition(), arg.getLvIndex(), arg.getSrcName(), dstNames(input, arg));
                            }
                        }
                        return;
                    }
                }
            }
            if (from != null) {
                var fromClass = input.getClass(from);
                if (fromClass != null) {
                    var fromMethod = fromClass.getMethod(name, descriptor);
                    if (fromMethod != null) {
                        output.visitMethod(fromClass.getSrcName(), name, descriptor, dstNames(input, fromMethod));
                        if (inheritArgs) {
                            for (var arg : fromMethod.getArgs()) {
                                output.visitMethodArg(fromClass.getSrcName(), name, descriptor, arg.getArgPosition(), arg.getLvIndex(), arg.getSrcName(), dstNames(input, arg));
                            }
                        }
                    }
                }
            }
        }
    }

    private static @Nullable String[] dstNames(MappingTreeView tree, MappingTreeView.ElementMappingView element) {
        var dstNames = new String[tree.getDstNamespaces().size()];
        for (int i = 0; i < dstNames.length; i++) {
            dstNames[i] = element.getDstName(i);
        }
        return dstNames;
    }
}
