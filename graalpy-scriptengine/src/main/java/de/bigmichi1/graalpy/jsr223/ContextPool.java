package de.bigmichi1.graalpy.jsr223;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/**
 * Pool of GraalPy contexts sharing one polyglot {@link Engine}.
 *
 * <p>Creating a Python context costs hundreds of milliseconds even with a shared engine, while the
 * actual script evaluation is usually much cheaper. Contexts are therefore reused. Isolation between
 * evaluations is provided by running every script in a fresh module namespace (see
 * {@code jsr223_support.py}), not by fresh contexts.
 *
 * <p>The pool never blocks: if no idle context is available a new one is created, and surplus
 * contexts are closed when returned. This keeps nested evaluations (a script calling Java that
 * evaluates another script on the same thread) deadlock free.
 *
 * <p>Borrowing prefers the context the current thread used last. Consecutive evaluations on one
 * thread (CIB seven runs environment scripts right before the actual script) then hit the same
 * warm compilation cache and imported modules.
 */
final class ContextPool implements AutoCloseable {

    private static final String LANGUAGE = "python";
    private static final String SUPPORT_RESOURCE = "jsr223_support.py";

    private final Engine engine;
    private final GraalPyEngineOptions options;
    private final Source supportSource;
    private final ConcurrentLinkedDeque<PooledContext> idle = new ConcurrentLinkedDeque<>();
    private final ThreadLocal<PooledContext> lastUsed = new ThreadLocal<>();
    private final AtomicInteger idleCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    ContextPool(Engine engine, GraalPyEngineOptions options) {
        this.engine = engine;
        this.options = options;
        this.supportSource = loadSupportSource();
    }

    PooledContext borrow() {
        if (closed.get()) {
            throw new IllegalStateException("GraalPy script engine factory has been closed");
        }
        PooledContext preferred = lastUsed.get();
        if (preferred != null && idle.remove(preferred)) {
            idleCount.decrementAndGet();
            return preferred;
        }
        PooledContext context = idle.pollFirst();
        if (context != null) {
            idleCount.decrementAndGet();
            return context;
        }
        return create();
    }

    /**
     * Returns a context after use.
     *
     * @param broken {@code true} if the context must not be reused (cancelled, exited, internal
     *               error)
     */
    void release(PooledContext context, boolean broken) {
        context.detachStreams();
        boolean retire = broken
                || closed.get()
                || (options.maxEvaluationsPerContext() > 0
                        && context.evaluations() >= options.maxEvaluationsPerContext());
        if (!retire && idleCount.incrementAndGet() <= options.maxIdleContexts()) {
            lastUsed.set(context);
            idle.addFirst(context);
            return;
        }
        if (!retire) {
            idleCount.decrementAndGet();
        }
        if (lastUsed.get() == context) {
            lastUsed.remove();
        }
        context.close();
    }

    int idleContexts() {
        return idleCount.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            PooledContext context;
            while ((context = idle.pollFirst()) != null) {
                idleCount.decrementAndGet();
                context.close();
            }
        }
    }

    private PooledContext create() {
        RedirectingStreams streams = new RedirectingStreams();
        Context.Builder builder = Context.newBuilder(LANGUAGE)
                .engine(engine)
                .allowHostAccess(options.hostAccess().hostAccess())
                .allowHostClassLookup(options.hostClassFilter())
                .allowIO(options.allowIO() ? IOAccess.ALL : IOAccess.NONE)
                .allowCreateThread(options.allowCreateThread())
                .allowNativeAccess(options.allowNativeAccess())
                .out(streams.out())
                .err(streams.err())
                .in(streams.in());
        for (Map.Entry<String, String> option : options.polyglotOptions().entrySet()) {
            builder.option(option.getKey(), option.getValue());
        }
        Context context = builder.build();
        try {
            Value api = context.eval(supportSource).execute(options.compilationCacheSize());
            return new PooledContext(context, streams, api.getHashValue("prepare"), api.getHashValue("execute"));
        } catch (RuntimeException e) {
            context.close(true);
            throw e;
        }
    }

    private static Source loadSupportSource() {
        try (InputStream in = ContextPool.class.getResourceAsStream(SUPPORT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + SUPPORT_RESOURCE);
            }
            String code = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Cached sources let the shared engine reuse the parsed support module across contexts.
            return Source.newBuilder(LANGUAGE, code, SUPPORT_RESOURCE).cached(true).build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
