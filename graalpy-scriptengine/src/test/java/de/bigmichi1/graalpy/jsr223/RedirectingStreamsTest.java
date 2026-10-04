package de.bigmichi1.graalpy.jsr223;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RedirectingStreamsTest {

    @Test
    void writesSingleBytesAndLargeBlocks() throws IOException {
        final RedirectingStreams streams = new RedirectingStreams();
        final StringWriter out = new StringWriter();
        streams.attach(out, new StringWriter(), null);

        final OutputStream stream = streams.out();
        final byte[] text = "ä".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        for (final byte b : text) {
            stream.write(b);
        }
        stream.write(text, 0, text.length);
        stream.flush();

        assertThat(out.toString()).isEqualTo("ä".repeat(20_000));
    }

    @Test
    void keepsASplitCharacterUntilItIsComplete() throws IOException {
        final RedirectingStreams streams = new RedirectingStreams();
        final StringWriter out = new StringWriter();
        streams.attach(out, new StringWriter(), null);
        final byte[] euro = "€".getBytes(StandardCharsets.UTF_8);

        streams.out().write(euro, 0, 2);
        streams.out().flush();
        assertThat(out.toString()).isEmpty();
        streams.out().write(euro, 2, 1);
        streams.out().flush();

        assertThat(out.toString()).isEqualTo("€");
    }

    @Test
    void detachingDropsAPartialCharacterInsteadOfLeakingIt() throws IOException {
        final RedirectingStreams streams = new RedirectingStreams();
        final StringWriter first = new StringWriter();
        streams.attach(first, new StringWriter(), null);
        streams.out().write("€".getBytes(StandardCharsets.UTF_8), 0, 2);
        streams.detach();

        final StringWriter second = new StringWriter();
        streams.attach(second, new StringWriter(), null);
        streams.out().write("ok".getBytes(StandardCharsets.UTF_8));
        streams.out().flush();

        assertThat(second.toString()).isEqualTo("ok");
    }

    @Test
    void dropsOutputWithoutAWriter() throws IOException {
        final RedirectingStreams streams = new RedirectingStreams();

        streams.err().write("lost".getBytes(StandardCharsets.UTF_8));
        streams.err().flush();
        streams.detach();
    }

    @Test
    void detachSurvivesAFailingWriter() throws IOException {
        final RedirectingStreams streams = new RedirectingStreams();
        streams.attach(new FailingWriter(), new FailingWriter(), null);
        streams.out().write('x');

        streams.detach();

        final StringWriter next = new StringWriter();
        streams.attach(next, new StringWriter(), null);
        streams.out().write('y');
        streams.out().flush();
        assertThat(next.toString()).isEqualTo("y");
    }

    @Test
    void readsSingleBytesAndBlocksUntilEndOfInput() throws IOException {
        final RedirectingStreams streams = new RedirectingStreams();
        streams.attach(new StringWriter(), new StringWriter(), new StringReader("aé"));
        final InputStream in = streams.in();

        assertThat(in.read(new byte[4], 0, 0)).isZero();
        assertThat(in.read()).isEqualTo('a');
        final byte[] rest = new byte[8];
        final int n = in.read(rest, 0, rest.length);
        assertThat(new String(rest, 0, n, StandardCharsets.UTF_8)).isEqualTo("é");
        assertThat(in.read()).isEqualTo(-1);
        assertThat(in.read(rest, 0, rest.length)).isEqualTo(-1);
    }

    @Test
    void readsNothingWithoutAReader() throws IOException {
        final RedirectingStreams streams = new RedirectingStreams();

        assertThat(streams.in().read()).isEqualTo(-1);
    }

    private static final class FailingWriter extends Writer {

        @Override
        public void write(final char[] buffer, final int offset, final int length) throws IOException {
            throw new IOException("write failed");
        }

        @Override
        public void flush() throws IOException {
            throw new IOException("flush failed");
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
