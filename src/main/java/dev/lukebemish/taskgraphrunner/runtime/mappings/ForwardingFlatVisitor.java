package dev.lukebemish.taskgraphrunner.runtime.mappings;

import net.fabricmc.mappingio.FlatMappingVisitor;
import net.fabricmc.mappingio.MappingFlag;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public abstract class ForwardingFlatVisitor implements FlatMappingVisitor {
    private final FlatMappingVisitor delegate;

    public ForwardingFlatVisitor(FlatMappingVisitor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void reset() {
        delegate.reset();
    }

    @Override
    public Set<MappingFlag> getFlags() {
        return delegate.getFlags();
    }

    @Override
    public boolean visitHeader() throws IOException {
        return delegate.visitHeader();
    }

    @Override
    public void visitMetadata(String key, @Nullable String value) throws IOException {
        delegate.visitMetadata(key, value);
    }

    @Override
    public boolean visitContent() throws IOException {
        return delegate.visitContent();
    }

    @Override
    public boolean visitEnd() throws IOException {
        return delegate.visitEnd();
    }

    @Override
    public void visitNamespaces(String srcNamespace, List<String> dstNamespaces) throws IOException {
        delegate.visitNamespaces(srcNamespace, dstNamespaces);
    }

    @Override
    public boolean visitClass(String srcName, @Nullable String[] dstNames) throws IOException {
        return delegate.visitClass(srcName, dstNames);
    }

    @Override
    public void visitClassComment(String srcName, @Nullable String[] dstNames, String comment) throws IOException {
        delegate.visitClassComment(srcName, dstNames, comment);
    }

    @Override
    public boolean visitField(String srcClsName, String srcName, @Nullable String srcDesc, @Nullable String[] dstClsNames, @Nullable String[] dstNames, @Nullable String[] dstDescs) throws IOException {
        return delegate.visitField(srcClsName, srcName, srcDesc, dstClsNames, dstNames, dstDescs);
    }

    @Override
    public void visitFieldComment(String srcClsName, String srcName, @Nullable String srcDesc, @Nullable String[] dstClsNames, @Nullable String[] dstNames, @Nullable String[] dstDescs, String comment) throws IOException {
        delegate.visitFieldComment(srcClsName, srcName, srcDesc, dstClsNames, dstNames, dstDescs, comment);
    }

    @Override
    public boolean visitMethod(String srcClsName, String srcName, @Nullable String srcDesc, @Nullable String[] dstClsNames, @Nullable String[] dstNames, @Nullable String[] dstDescs) throws IOException {
        return delegate.visitMethod(srcClsName, srcName, srcDesc, dstClsNames, dstNames, dstDescs);
    }

    @Override
    public void visitMethodComment(String srcClsName, String srcName, @Nullable String srcDesc, @Nullable String[] dstClsNames, @Nullable String[] dstNames, @Nullable String[] dstDescs, String comment) throws IOException {
        delegate.visitMethodComment(srcClsName, srcName, srcDesc, dstClsNames, dstNames, dstDescs, comment);
    }

    @Override
    public boolean visitMethodArg(String srcClsName, String srcMethodName, @Nullable String srcMethodDesc, int argPosition, int lvIndex, @Nullable String srcName, @Nullable String[] dstClsNames, @Nullable String[] dstMethodNames, @Nullable String[] dstMethodDescs, String[] dstNames) throws IOException {
        return delegate.visitMethodArg(srcClsName, srcMethodName, srcMethodDesc, argPosition, lvIndex, srcName, dstClsNames, dstMethodNames, dstMethodDescs, dstNames);
    }

    @Override
    public void visitMethodArgComment(String srcClsName, String srcMethodName, @Nullable String srcMethodDesc, int argPosition, int lvIndex, @Nullable String srcName, @Nullable String[] dstClsNames, @Nullable String[] dstMethodNames, @Nullable String[] dstMethodDescs, @Nullable String[] dstNames, String comment) throws IOException {
        delegate.visitMethodArgComment(srcClsName, srcMethodName, srcMethodDesc, argPosition, lvIndex, srcName, dstClsNames, dstMethodNames, dstMethodDescs, dstNames, comment);
    }

    @Override
    public boolean visitMethodVar(String srcClsName, String srcMethodName, @Nullable String srcMethodDesc, int lvtRowIndex, int lvIndex, int startOpIdx, int endOpIdx, @Nullable String srcName, @Nullable String[] dstClsNames, @Nullable String[] dstMethodNames, @Nullable String[] dstMethodDescs, String[] dstNames) throws IOException {
        return delegate.visitMethodVar(srcClsName, srcMethodName, srcMethodDesc, lvtRowIndex, lvIndex, startOpIdx, endOpIdx, srcName, dstClsNames, dstMethodNames, dstMethodDescs, dstNames);
    }

    @Override
    public void visitMethodVarComment(String srcClsName, String srcMethodName, @Nullable String srcMethodDesc, int lvtRowIndex, int lvIndex, int startOpIdx, int endOpIdx, @Nullable String srcName, @Nullable String[] dstClsNames, @Nullable String[] dstMethodNames, @Nullable String[] dstMethodDescs, @Nullable String[] dstNames, String comment) throws IOException {
        delegate.visitMethodVarComment(srcClsName, srcMethodName, srcMethodDesc, lvtRowIndex, lvIndex, startOpIdx, endOpIdx, srcName, dstClsNames, dstMethodNames, dstMethodDescs, dstNames, comment);
    }
}
