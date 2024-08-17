package dev.lukebemish.taskgraphrunner.runtime;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

public interface RecordedInput {
    void hashReference(ByteConsumer digest, Context context);

    default void hashContents(ByteConsumer digest, Context context) {
        hashReference(digest, context);
    }

    JsonElement recordedValue(Context context);

    interface ByteConsumer {
        void update(byte[] bytes);

        void update(byte b);

        void update(ByteBuffer buffer);

        void update(byte[] buffer, int i, int read);

        static ByteConsumer of(MessageDigest digest) {
            return new ByteConsumer() {
                @Override
                public void update(byte[] bytes) {
                    digest.update(bytes);
                }

                @Override
                public void update(byte b) {
                    digest.update(b);
                }

                @Override
                public void update(ByteBuffer buffer) {
                    digest.update(buffer);
                }

                @Override
                public void update(byte[] buffer, int i, int read) {
                    digest.update(buffer, i, read);
                }
            };
        }

        static ByteConsumer of(OutputStream stream) {
            return new ByteConsumer() {
                @Override
                public void update(byte[] bytes) {
                    try {
                        stream.write(bytes);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public void update(byte b) {
                    try {
                        stream.write(b);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public void update(ByteBuffer buffer) {
                    try {
                        stream.write(buffer.array(), buffer.arrayOffset(), buffer.limit());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public void update(byte[] buffer, int i, int read) {
                    try {
                        stream.write(buffer, i, read);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
        }
    }
}
