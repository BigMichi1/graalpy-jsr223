package de.bigmichi1.graalpy.jsr223;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Byte streams handed to a polyglot context once, at build time, which forward to the
 * {@link javax.script.ScriptContext} writers/reader of whatever evaluation currently uses the
 * context. GraalPy writes UTF-8.
 */
final class RedirectingStreams {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedirectingStreams.class);

    private final WriterStream out = new WriterStream();
    private final WriterStream err = new WriterStream();
    private final ReaderStream in = new ReaderStream();

    OutputStream out() {
        return out;
    }

    OutputStream err() {
        return err;
    }

    InputStream in() {
        return in;
    }

    void attach(final Writer outWriter, final Writer errWriter, final Reader reader) {
        out.target = outWriter;
        err.target = errWriter;
        in.attach(reader);
    }

    /** Flushes and detaches the evaluation's writers; never throws, failures are logged. */
    void detach() {
        out.detach("stdout");
        err.detach("stderr");
        in.attach(null);
    }

    /**
     * Decodes UTF-8 bytes into the current target writer. Output written while no evaluation is
     * attached (for example by a thread a script left running) has no reader and is dropped.
     */
    private static final class WriterStream extends OutputStream {

        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        private final ByteBuffer bytes = ByteBuffer.allocate(8192);
        private final CharBuffer chars = CharBuffer.allocate(8192);
        private volatile Writer target;

        @Override
        public synchronized void write(final int b) throws IOException {
            if (!bytes.hasRemaining()) {
                drain();
            }
            bytes.put((byte) b);
        }

        @Override
        public synchronized void write(final byte[] b, final int off, final int len) throws IOException {
            int position = off;
            final int end = off + len;
            while (position < end) {
                if (!bytes.hasRemaining()) {
                    drain();
                }
                final int n = Math.min(end - position, bytes.remaining());
                bytes.put(b, position, n);
                position += n;
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            drain();
            final Writer writer = target;
            if (writer != null) {
                writer.flush();
            }
        }

        synchronized void detach(final String name) {
            try {
                flush();
            } catch (final IOException | RuntimeException e) {
                // The script itself finished; a failing writer must not replace its outcome.
                LOGGER.warn("Script {} could not be flushed to the ScriptContext writer; its tail is lost", name, e);
            } finally {
                target = null;
                // An incomplete UTF-8 sequence must not leak into the next evaluation's output.
                bytes.clear();
                decoder.reset();
            }
        }

        private void drain() throws IOException {
            bytes.flip();
            CoderResult result;
            do {
                // Underflow leaves an incomplete multi-byte sequence in the buffer for the next write.
                result = decoder.decode(bytes, chars, false);
                chars.flip();
                final Writer writer = target;
                if (writer != null && chars.hasRemaining()) {
                    writer.write(chars.array(), chars.position(), chars.remaining());
                }
                chars.clear();
            } while (result.isOverflow());
            bytes.compact();
        }
    }

    /** Encodes characters of the current reader as UTF-8; without a reader the stream is at EOF. */
    private static final class ReaderStream extends InputStream {

        private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        private final CharBuffer chars = CharBuffer.allocate(4096);
        private final ByteBuffer bytes = ByteBuffer.allocate(16384);
        private Reader reader;
        private boolean eof;

        ReaderStream() {
            // Until an evaluation attaches its reader, the stream is empty and at end of input.
            attach(null);
        }

        synchronized void attach(final Reader newReader) {
            reader = newReader;
            eof = newReader == null;
            chars.clear().flip();
            bytes.clear().flip();
            encoder.reset();
        }

        @Override
        public synchronized int read() throws IOException {
            final byte[] one = new byte[1];
            final int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public synchronized int read(final byte[] b, final int off, final int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (!bytes.hasRemaining()) {
                if (eof) {
                    return -1;
                }
                fill();
            }
            final int n = Math.min(len, bytes.remaining());
            bytes.get(b, off, n);
            return n;
        }

        private void fill() throws IOException {
            chars.compact();
            final int read = reader.read(chars);
            chars.flip();
            if (read < 0) {
                eof = true;
            }
            bytes.compact();
            encoder.encode(chars, bytes, eof);
            bytes.flip();
        }
    }
}
