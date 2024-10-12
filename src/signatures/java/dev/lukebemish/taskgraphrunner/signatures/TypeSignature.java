package dev.lukebemish.taskgraphrunner.signatures;

public sealed interface TypeSignature permits TypeSignatureImpl.ArrayType, TypeSignatureImpl.BoundedType, TypeSignatureImpl.ClassType, TypeSignatureImpl.ClassTypeSuffix, TypeSignatureImpl.ParameterType, TypeSignatureImpl.Primitive, TypeSignatureImpl.WildcardType {
    String binary();
    String source();
    default String neo() {
        throw new UnsupportedOperationException("Signature "+source()+" does not support neo injections");
    }
    default String fabric() {
        throw new UnsupportedOperationException("Signature "+source()+" does not support fabric injections");
    }
    default byte[] binaryStub() {
        throw new UnsupportedOperationException("Signature "+binary()+" does not support binary stubs");
    }
    default String sourceStub() {
        throw new UnsupportedOperationException("Signature "+source()+" does not support source stubs");
    }

    static TypeSignature fromNeo(String neo, ClassFinder classFinder) {
        return TypeSignatureImpl.parseNeoInjection(neo, classFinder);
    }

    static TypeSignature fromFabric(String fabric) {
        return TypeSignatureImpl.parseFabricInjection(fabric);
    }

    static TypeSignature fromBinary(String binary) {
        return TypeSignatureImpl.parseBinary(binary);
    }

    static TypeSignature fromSource(String source, ClassFinder classFinder) {
        return TypeSignatureImpl.parseSource(source, classFinder);
    }
}
