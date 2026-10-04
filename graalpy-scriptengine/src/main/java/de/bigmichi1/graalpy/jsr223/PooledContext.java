package de.bigmichi1.graalpy.jsr223;

import java.io.Reader;
import java.io.Writer;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** A GraalPy context together with its support functions and stream redirection. */
final class PooledContext {

    private final Context context;
    private final RedirectingStreams streams;
    private final Value prepare;
    private final Value execute;
    private int evaluations;

    PooledContext(Context context, RedirectingStreams streams, Value prepare, Value execute) {
        this.context = context;
        this.streams = streams;
        this.prepare = prepare;
        this.execute = execute;
    }

    Context context() {
        return context;
    }

    /** Parses/compiles {@code script} (cached per context); the result exposes {@code names}. */
    Value prepare(String script, String filename) {
        return prepare.execute(script, filename);
    }

    /** Runs a prepared script; returns {@code [has_result, result, changes]}. */
    Value execute(Value prepared, Object[] keys, Object[] values, boolean writeBack) {
        evaluations++;
        return execute.execute(prepared, keys, values, writeBack);
    }

    int evaluations() {
        return evaluations;
    }

    void attachStreams(Writer out, Writer err, Reader in) {
        streams.attach(out, err, in);
    }

    void detachStreams() {
        streams.detach();
    }

    void close() {
        try {
            context.close(true);
        } catch (RuntimeException ignored) {
            // A context that cannot be closed cleanly is unusable anyway.
        }
    }
}
