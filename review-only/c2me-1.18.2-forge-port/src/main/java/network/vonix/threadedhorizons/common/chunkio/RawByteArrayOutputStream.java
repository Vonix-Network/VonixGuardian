package network.vonix.threadedhorizons.common.chunkio;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class RawByteArrayOutputStream extends ByteArrayOutputStream {

    public RawByteArrayOutputStream() {
        super();
    }

    public RawByteArrayOutputStream(int size) {
        super(size);
    }

    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(this.buf, 0, this.count);
    }
}
