package de.bigmichi1.graalpy.jsr223;

import java.io.Reader;
import java.io.Writer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A GraalPy context together with its support functions and stream redirection. */
final class PooledContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(PooledContext.class);

    private final Context context;
    private final RedirectingStreams streams;
    private final Value prepare;
    private final Value execute;
    private final Value flush;
    private int evaluations;

    PooledContext(final Context context, final RedirectingStreams streams, final Value prepare, final Value execute, final Value flush) {
        this.context = context;
        this.streams = streams;
        this.prepare = prepare;
        this.execute = execute;
        this.flush = flush;
    }

    /** Parses/compiles {@code script} (cached per context); the result exposes {@code names}. */
    Value prepare(final String script, final String filename) {
        return prepare.execute(script, filename);
    }

    /** Runs a prepared script; returns {@code [has_result, result, changes]}. */
    Value execute(final Value prepared, final Object[] keys, final Object[] values, final boolean writeBack) {
        evaluations++;
        return execute.execute(prepared, keys, values, writeBack);
    }

    /** Flushes Python's buffered stdout/stderr into the attached writers. */
    void flushOutput() {
        flush.execute();
    }

    int evaluations() {
        return evaluations;
    }

    void attachStreams(final Writer out, final Writer err, final Reader in) {
        streams.attach(out, err, in);
    }

    void detachStreams() {
        streams.detach();
    }

    void close() {
        try {
            context.close(true);
        } catch (final RuntimeException e) {
            // Not rethrown: the caller is releasing the context after an evaluation whose outcome
            // must not be replaced. A failure here can mean leaked resources or a pool defect.
            LOGGER.warn("Closing a GraalPy context failed", e);
        }
    }
}
