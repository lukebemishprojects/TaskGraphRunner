package dev.lukebemish.taskgraphrunner.runtime.mappings;

import net.fabricmc.mappingio.FlatMappingVisitor;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.RegularAsFlatMappingVisitor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public class MappingDropMissingSrcVisitor extends ForwardingFlatVisitor {
    public MappingDropMissingSrcVisitor(FlatMappingVisitor delegate) {
        super(delegate);
    }

    public static MappingVisitor create(MappingVisitor delegate) {
        return new MappingDropMissingSrcVisitor(new RegularAsFlatMappingVisitor(delegate)).asRegularVisitor();
    }

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
}
