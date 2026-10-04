package de.bigmichi1.graalpy.jsr223.perf;

import de.bigmichi1.graalpy.jsr223.GraalPyEngineOptions;
import de.bigmichi1.graalpy.jsr223.GraalPyScriptEngineFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What a process engine pays per script: the shapes CIB seven evaluates (a condition, a script task
 * with a result, a script that writes variables back), each through a compiled script and fresh
 * bindings, as CIB seven does. GraalJS, the usual JavaScript engine of CIB seven, evaluates the same condition as the
 * baseline.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = { "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-Dpolyglot.engine.WarnInterpreterOnly=false" })
@State(Scope.Benchmark)
public class EvalBenchmark {

    private static final List<Integer> ITEMS = IntStream.range(0, 100).boxed().toList();

    private GraalPyScriptEngineFactory factory;
    private ScriptEngine python;
    private CompiledScript condition;
    private CompiledScript scriptTask;
    private CompiledScript writeBack;
    private CompiledScript loop;
    private CompiledScript jsCondition;

    @Setup(Level.Trial)
    public void setUp() throws ScriptException {
        factory = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().build());
        python = factory.getScriptEngine();
        condition = ((Compilable) python).compile("amount > 100");
        scriptTask = ((Compilable) python).compile("total = sum(item * 2 for item in items)\ntotal");
        writeBack = ((Compilable) python).compile("result = {'amount': amount, 'pair': [amount, amount]}");
        loop = ((Compilable) python).compile("s = 0\nfor i in range(1000):\n    s += i\ns");
        final ScriptEngine js = new ScriptEngineManager().getEngineByName("graal.js");
        jsCondition = ((Compilable) js).compile("amount > 100");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        factory.close();
    }

    private static Bindings bindings() {
        final Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 150);
        variables.put("items", ITEMS);
        return new SimpleBindings(variables);
    }

    @Benchmark
    public Object pythonCondition() throws ScriptException {
        return condition.eval(bindings());
    }

    @Benchmark
    public Object pythonConditionUncompiled() throws ScriptException {
        return python.eval("amount > 100", bindings());
    }

    @Benchmark
    public Object pythonScriptTask() throws ScriptException {
        return scriptTask.eval(bindings());
    }

    @Benchmark
    public Object pythonWriteBack() throws ScriptException {
        final Bindings bindings = bindings();
        writeBack.eval(bindings);
        return bindings.get("result");
    }

    @Benchmark
    public Object pythonLoop() throws ScriptException {
        return loop.eval(bindings());
    }

    @Benchmark
    @Threads(4)
    public Object pythonConditionFourThreads() throws ScriptException {
        return condition.eval(bindings());
    }

    @Benchmark
    public Object javascriptCondition() throws ScriptException {
        return jsCondition.eval(bindings());
    }
}
