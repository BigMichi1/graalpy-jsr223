package de.bigmichi1.graalpy.jsr223;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.WeakReference;
import org.junit.jupiter.api.Test;

class ContextPoolTest {

    @Test
    void aClosedFactoryLeavesNoContextReachableFromTheThreadThatUsedIt() throws InterruptedException {
        final WeakReference<PooledContext> released = useOneContextAndClose();

        for (int i = 0; i < 50 && released.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }

        // A strong thread-local reference to the last used context kept it, and its engine, alive for
        // as long as this thread lives.
        assertThat(released.get()).isNull();
    }

    @Test
    void prefersTheContextThisThreadUsedLast() {
        try (GraalPyScriptEngineFactory factory = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().maxIdleContexts(4).build())) {
            final ContextPool pool = factory.pool();
            final PooledContext first = pool.borrow();
            final PooledContext second = pool.borrow();
            pool.release(second, false);
            pool.release(first, false);

            final PooledContext again = pool.borrow();
            assertThat(again).isSameAs(first);
            pool.release(again, false);
        }
    }

    private static WeakReference<PooledContext> useOneContextAndClose() {
        final GraalPyScriptEngineFactory factory = new GraalPyScriptEngineFactory();
        final ContextPool pool = factory.pool();
        final PooledContext context = pool.borrow();
        pool.release(context, false);
        factory.close();
        return new WeakReference<>(context);
    }
}
