package de.bigmichi1.graalpy.jsr223.perf;

import de.bigmichi1.graalpy.jsr223.GraalPyEngineOptions;
import de.bigmichi1.graalpy.jsr223.GraalPyScriptEngineFactory;
import java.util.concurrent.TimeUnit;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What the pool saves: an evaluation that has to create its context (no idle context kept), on a
 * shared engine that is already warm, and the very first evaluation of a new factory.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = { "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-Dpolyglot.engine.WarnInterpreterOnly=false" })
public class ContextStartupBenchmark {

    /** A factory that keeps no idle context, so every evaluation creates and closes one. */
    @State(Scope.Benchmark)
    public static class Unpooled {

        private GraalPyScriptEngineFactory factory;
        private ScriptEngine python;

        @Setup(Level.Trial)
        public void setUp() throws ScriptException {
            factory = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().maxIdleContexts(0).build());
            python = factory.getScriptEngine();
            python.eval("1");
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            factory.close();
        }
    }

    @Benchmark
    @Warmup(iterations = 3)
    @Measurement(iterations = 10)
    public Object evaluationCreatingItsContext(final Unpooled state) throws ScriptException {
        return state.python.eval("amount = 1\namount > 0");
    }

    @Benchmark
    @Warmup(iterations = 0)
    @Measurement(iterations = 1)
    public Object firstEvaluationOfANewFactory() throws ScriptException {
        try (GraalPyScriptEngineFactory factory = new GraalPyScriptEngineFactory()) {
            return factory.getScriptEngine().eval("1 + 1");
        }
    }
}
