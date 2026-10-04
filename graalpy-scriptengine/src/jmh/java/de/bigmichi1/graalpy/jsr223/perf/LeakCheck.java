package de.bigmichi1.graalpy.jsr223.perf;

import de.bigmichi1.graalpy.jsr223.GraalPyEngineOptions;
import de.bigmichi1.graalpy.jsr223.GraalPyScriptEngineFactory;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;

/**
 * Runs each workload in rounds and measures the heap after a full collection between rounds. A
 * workload that keeps something alive per evaluation shows as a heap that grows round after round;
 * one that does not settles after the warm-up rounds. Exits 1 if any workload grows by more than
 * {@link #ALLOWED_GROWTH_MB} over its measured rounds.
 */
public final class LeakCheck {

    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASURED_ROUNDS = Integer.getInteger("leakCheck.rounds", 8);
    private static final int EVALUATIONS_PER_ROUND = 2_000;
    private static final double ALLOWED_GROWTH_MB = 8.0;

    private LeakCheck() {}

    /** One kind of load, run {@code EVALUATIONS_PER_ROUND} times per round. */
    @FunctionalInterface
    private interface Workload {
        void run(int round) throws Exception;
    }

    public static void main(final String[] args) throws Exception {
        final GraalPyScriptEngineFactory factory = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().maxIdleContexts(4).build());
        final ScriptEngine python = factory.getScriptEngine();
        final CompiledScript compiled = ((Compilable) python).compile("result = {'n': n, 'items': [n, n + 1]}\nn * 2");
        final ExecutorService pool = Executors.newFixedThreadPool(4);

        final Map<String, Workload> workloads = new LinkedHashMap<>();
        workloads.put("compiled script, write-back", round -> {
            for (int i = 0; i < EVALUATIONS_PER_ROUND; i++) {
                final Bindings bindings = new SimpleBindings(new HashMap<>(Map.of("n", i)));
                compiled.eval(bindings);
            }
        });
        workloads.put("unique source text per evaluation", round -> {
            for (int i = 0; i < EVALUATIONS_PER_ROUND; i++) {
                python.eval("x = " + round + " * 100000 + " + i + "\nx");
            }
        });
        workloads.put("errors with tracebacks", round -> {
            for (int i = 0; i < EVALUATIONS_PER_ROUND; i++) {
                try {
                    python.eval(i % 2 == 0 ? "undefined_name" : "raise ValueError('boom')");
                } catch (final ScriptException expected) {
                    // the point of this workload
                }
            }
        });
        workloads.put("output into a writer", round -> {
            for (int i = 0; i < EVALUATIONS_PER_ROUND; i++) {
                final ScriptContext context = new SimpleScriptContext();
                context.setWriter(new StringWriter());
                python.eval("print('line ' * 20)", context);
            }
        });
        workloads.put("four threads", round -> {
            final List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < EVALUATIONS_PER_ROUND; i++) {
                final int n = i;
                futures.add(pool.submit(() -> compiled.eval(new SimpleBindings(new HashMap<>(Map.of("n", n))))));
            }
            for (final Future<Object> future : futures) {
                future.get();
            }
        });
        workloads.put("short-lived threads", round -> {
            for (int i = 0; i < EVALUATIONS_PER_ROUND / 20; i++) {
                final Thread thread = new Thread(() -> {
                    try {
                        compiled.eval(new SimpleBindings(new HashMap<>(Map.of("n", 1))));
                    } catch (final ScriptException e) {
                        throw new IllegalStateException(e);
                    }
                });
                thread.start();
                thread.join();
            }
        });
        workloads.put("factories created and closed", round -> {
            for (int i = 0; i < 5; i++) {
                try (GraalPyScriptEngineFactory shortLived = new GraalPyScriptEngineFactory()) {
                    shortLived.getScriptEngine().eval("1 + 1");
                }
            }
        });

        boolean leaked = false;
        final String only = System.getProperty("leakCheck.only", "");
        for (final Map.Entry<String, Workload> workload : workloads.entrySet()) {
            if (workload.getKey().contains(only)) {
                leaked |= measure(workload.getKey(), workload.getValue());
            }
        }
        pool.shutdownNow();
        factory.close();
        System.out.println(leaked ? "RESULT: heap growth above " + ALLOWED_GROWTH_MB + " MB" : "RESULT: no growth");
        System.exit(leaked ? 1 : 0);
    }

    private static boolean measure(final String name, final Workload workload) throws Exception {
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            workload.run(round);
        }
        final double[] used = new double[MEASURED_ROUNDS];
        final long start = System.nanoTime();
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            workload.run(WARMUP_ROUNDS + round);
            used[round] = usedHeapMb();
        }
        final double growth = used[MEASURED_ROUNDS - 1] - used[0];
        final boolean leaked = growth > ALLOWED_GROWTH_MB;
        final StringBuilder series = new StringBuilder();
        for (final double value : used) {
            series.append(String.format(Locale.ROOT, " %.1f", value));
        }
        System.out.printf(
            Locale.ROOT,
            "%-36s %s  growth %+.1f MB over %d rounds, %.0f ms per round, heap after GC:%s%n",
            name,
            leaked ? "LEAK" : "ok  ",
            growth,
            MEASURED_ROUNDS,
            (System.nanoTime() - start) / 1e6 / MEASURED_ROUNDS,
            series
        );
        return leaked;
    }

    private static double usedHeapMb() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
    }
}
