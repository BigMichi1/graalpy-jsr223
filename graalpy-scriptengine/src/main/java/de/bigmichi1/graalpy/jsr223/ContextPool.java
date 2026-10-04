package de.bigmichi1.graalpy.jsr223;

import java.lang.ref.WeakReference;
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
    /**
     * The context each thread used last. Weak, so that a long-lived thread (a job executor's) does
     * not keep a retired context, and through it a closed engine, reachable.
     */
    private final ThreadLocal<WeakReference<PooledContext>> lastUsed = new ThreadLocal<>();
    private final AtomicInteger idleCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean engineClosed = new AtomicBoolean();
    /** Contexts currently borrowed; the engine is closed only once none is left. */
    private final AtomicInteger borrowed = new AtomicInteger();

    ContextPool(final Engine engine, final GraalPyEngineOptions options) {
        this.engine = engine;
        this.options = options;
        this.supportSource = loadSupportSource();
    }

    PooledContext borrow() {
        borrowed.incrementAndGet();
        try {
            if (closed.get()) {
                throw new IllegalStateException("GraalPy script engine factory has been closed");
            }
            final WeakReference<PooledContext> reference = lastUsed.get();
            final PooledContext preferred = reference == null ? null : reference.get();
            if (preferred != null && idle.remove(preferred)) {
                idleCount.decrementAndGet();
                return preferred;
            }
            final PooledContext context = idle.pollFirst();
            if (context != null) {
                idleCount.decrementAndGet();
                return context;
            }
            return create();
        } catch (final RuntimeException e) {
            returned();
            throw e;
        }
    }

    /**
     * Returns a context after use.
     *
     * @param broken {@code true} if the context must not be reused (cancelled, exited, internal
     *               error)
     */
    void release(final PooledContext context, final boolean broken) {
        // Detaching flushes the evaluation's writers. It never throws (failures are logged), so the
        // context below is always either pooled or closed.
        context.detachStreams();
        final boolean retire = broken || closed.get() || (options.maxEvaluationsPerContext() > 0 && context.evaluations() >= options.maxEvaluationsPerContext());
        try {
            if (!retire && idleCount.incrementAndGet() <= options.maxIdleContexts()) {
                lastUsed.set(new WeakReference<>(context));
                idle.addFirst(context);
                // close() may have drained the idle list between the check above and the add.
                if (closed.get() && idle.remove(context)) {
                    idleCount.decrementAndGet();
                    context.close();
                }
                return;
            }
            if (!retire) {
                idleCount.decrementAndGet();
            }
            final WeakReference<PooledContext> reference = lastUsed.get();
            if (reference != null && reference.get() == context) {
                lastUsed.remove();
            }
            context.close();
        } finally {
            returned();
        }
    }

    private void returned() {
        if (borrowed.decrementAndGet() == 0 && closed.get()) {
            closeEngine();
        }
    }

    private void closeEngine() {
        if (engineClosed.compareAndSet(false, true)) {
            engine.close();
        }
    }

    int idleContexts() {
        return idleCount.get();
    }

    /**
     * Closes the idle contexts at once. Borrowed contexts finish their evaluation and are closed when
     * returned; the shared engine is closed with the last of them.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            PooledContext context;
            while ((context = idle.pollFirst()) != null) {
                idleCount.decrementAndGet();
                context.close();
            }
            if (borrowed.get() == 0) {
                closeEngine();
            }
        }
    }

    private PooledContext create() {
        final RedirectingStreams streams = new RedirectingStreams();
        final Context.Builder builder = Context.newBuilder(LANGUAGE)
            .engine(engine)
            .allowHostAccess(options.hostAccess().hostAccess())
            .allowHostClassLookup(options.hostClassFilter())
            .allowIO(options.allowIO() ? IOAccess.ALL : IOAccess.NONE)
            .allowCreateThread(options.allowCreateThread())
            .allowNativeAccess(options.allowNativeAccess())
            .out(streams.out())
            .err(streams.err())
            .in(streams.in());
        for (final Map.Entry<String, String> option : options.polyglotOptions().entrySet()) {
            builder.option(option.getKey(), option.getValue());
        }
        final Context context = builder.build();
        try {
            final Value api = context.eval(supportSource).execute(options.compilationCacheSize());
            return new PooledContext(context, streams, api.getHashValue("prepare"), api.getHashValue("execute"), api.getHashValue("flush"));
        } catch (final RuntimeException e) {
            context.close(true);
            throw e;
        }
    }

    private static Source loadSupportSource() {
        final String code = ClasspathResources.readUtf8(ContextPool.class, SUPPORT_RESOURCE);
        // Cached sources let the shared engine reuse the parsed support module across contexts.
        return Source.newBuilder(LANGUAGE, code, SUPPORT_RESOURCE).cached(true).buildLiteral();
    }
}
