package de.bigmichi1.graalpy.jsr223;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GraalPyScriptEngineTest {

    private static GraalPyScriptEngineFactory factory;
    private static ScriptEngine engine;

    @BeforeAll
    static void setUp() {
        factory = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().maxIdleContexts(4).build());
        engine = factory.getScriptEngine();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void isDiscoverableThroughScriptEngineManager() {
        final ScriptEngineManager manager = new ScriptEngineManager();
        for (final String name : List.of("python", "graalpy", "python3")) {
            assertThat(manager.getEngineByName(name)).isInstanceOf(GraalPyScriptEngine.class);
        }
        assertThat(manager.getEngineByExtension("py")).isInstanceOf(GraalPyScriptEngine.class);
        assertThat(manager.getEngineByMimeType("text/x-python")).isInstanceOf(GraalPyScriptEngine.class);
    }

    @Test
    void readmeExample() throws ScriptException {
        final ScriptEngine python = new ScriptEngineManager().getEngineByName("python");
        final Bindings bindings = python.createBindings();
        bindings.put("order", Map.of("amount", 120, "currency", "EUR"));

        final Object total = python.eval(
            """
            vat = 0.25
            order['amount'] * (1 + vat)
            """,
            bindings
        );

        assertThat(total).isEqualTo(150.0d);
        assertThat(bindings.get("vat")).isEqualTo(0.25d);
    }

    @Test
    void reportsMetadata() {
        assertThat(factory.getParameter("THREADING")).isEqualTo("MULTITHREADED");
        assertThat(factory.getLanguageVersion()).startsWith("3.");
        assertThat(factory.getOutputStatement("say \"hi\"\n")).isEqualTo("print(\"say \\\"hi\\\"\\n\")");
        assertThat(factory.getMethodCallSyntax("obj", "run", "a", "b")).isEqualTo("obj.run(a, b)");
    }

    @Test
    void returnsValueOfTrailingExpression() throws ScriptException {
        assertThat(engine.eval("1 + 2")).isEqualTo(3);
        assertThat(engine.eval("x = 5\nx * 2")).isEqualTo(10);
        assertThat(engine.eval("x = 5")).isNull();
        assertThat(engine.eval("")).isNull();
    }

    @Test
    void convertsResultsToJavaTypes() throws ScriptException {
        assertThat(engine.eval("True")).isEqualTo(true);
        assertThat(engine.eval("2**40")).isEqualTo(1L << 40);
        assertThat(engine.eval("2**100")).isEqualTo(BigInteger.TWO.pow(100));
        assertThat(engine.eval("2.0")).isEqualTo(2.0d);
        assertThat(engine.eval("'text'")).isEqualTo("text");
        assertThat(engine.eval("None")).isNull();
        assertThat(engine.eval("[1, 'a', [2]]")).isEqualTo(List.of(1, "a", List.of(2)));
        assertThat(engine.eval("(1, 2)")).isEqualTo(List.of(1, 2));
        assertThat(engine.eval("{'a': 1, 'b': [True]}")).isEqualTo(Map.of("a", 1, "b", List.of(true)));
        assertThat(engine.eval("{1, 2}")).isEqualTo(Set.of(1, 2));
        assertThat(engine.eval("b'\\x01\\x02'")).isEqualTo(new byte[] { 1, 2 });
        assertThat(engine.eval("import datetime\ndatetime.date(2024, 2, 29)")).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(engine.eval("import datetime\ndatetime.datetime(2024, 2, 29, 13, 5)")).isEqualTo(LocalDateTime.of(2024, 2, 29, 13, 5));
    }

    @Test
    void readsBindingsFromEngineAndGlobalScope() throws ScriptException {
        final ScriptContext context = new SimpleScriptContext();
        context.setBindings(new SimpleBindings(new HashMap<>(Map.of("a", 2, "b", 3))), ScriptContext.ENGINE_SCOPE);
        context.setBindings(new SimpleBindings(new HashMap<>(Map.of("b", 100, "c", 7))), ScriptContext.GLOBAL_SCOPE);

        assertThat(engine.eval("a * b + c", context)).isEqualTo(13);
    }

    @Test
    void looksUpOnlyNamesTheScriptUses() throws ScriptException {
        final List<String> lookups = new ArrayList<>();
        final Bindings bindings = new SimpleBindings(new HashMap<>(Map.of("used", 1, "unused", 2))) {
            @Override
            public boolean containsKey(final Object key) {
                lookups.add((String) key);
                return super.containsKey(key);
            }

            @Override
            public Set<Map.Entry<String, Object>> entrySet() {
                throw new AssertionError("bindings must not be enumerated");
            }
        };

        assertThat(engine.eval("def f():\n    return used\nf()", bindings)).isEqualTo(1);
        assertThat(lookups).contains("used").doesNotContain("unused");
    }

    @Test
    void writesAssignedVariablesBackToEngineScope() throws ScriptException {
        final Bindings bindings = new SimpleBindings(new HashMap<>(Map.of("count", 1, "untouched", "x")));

        engine.eval(
            """
            import json
            count = count + 1
            created = {'k': [1, 2]}
            _private = 1
            def helper(): pass
            class Thing: pass
            """,
            bindings
        );

        assertThat(bindings)
            .containsEntry("count", 2)
            .containsEntry("created", Map.of("k", List.of(1, 2)))
            .containsEntry("untouched", "x")
            .doesNotContainKeys("_private", "helper", "Thing", "json", "__name__");
    }

    @Test
    void doesNotPutUnchangedBindings() throws ScriptException {
        final List<String> puts = new ArrayList<>();
        final Bindings bindings = new SimpleBindings(new HashMap<>(Map.of("a", 1, "items", new ArrayList<>(List.of(1))))) {
            @Override
            public Object put(final String name, final Object value) {
                puts.add(name);
                return super.put(name, value);
            }
        };

        engine.eval("b = a\nitems.add(2)", bindings);

        assertThat(puts).containsExactly("b");
        assertThat(bindings.get("items")).isEqualTo(List.of(1, 2));
    }

    @Test
    void writeBackCanBeDisabled() throws ScriptException {
        try (GraalPyScriptEngineFactory noWriteBack = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().writeBack(false).build())) {
            final Bindings bindings = new SimpleBindings();
            noWriteBack.getScriptEngine().eval("x = 1", bindings);
            assertThat(bindings).isEmpty();
        }
    }

    @Test
    void isolatesGlobalsBetweenEvaluations() throws ScriptException {
        final Bindings first = new SimpleBindings();
        engine.eval("leftover = 42", first);

        assertThatThrownBy(() -> engine.eval("leftover", new SimpleBindings()))
            .isInstanceOf(ScriptException.class)
            .hasMessageContaining("NameError");
    }

    @Test
    void exposesJavaObjects() throws ScriptException {
        final Map<String, Object> map = new HashMap<>(Map.of("key", "value"));
        final List<String> list = new ArrayList<>(List.of("a"));
        final Bindings bindings = new SimpleBindings(new HashMap<>(Map.of("map", map, "list", list)));

        final Object result = engine.eval(
            """
            list.add('b')
            map.put('other', len(list))
            import java
            ArrayList = java.type('java.util.ArrayList')
            ArrayList([map.get('key'), map['other']])
            """,
            bindings
        );

        assertThat(result).isEqualTo(List.of("value", 2));
        assertThat(list).containsExactly("a", "b");
        assertThat(bindings.get("ArrayList")).isNotNull();
    }

    @Test
    void importsJavaPackages() throws ScriptException {
        assertThat(engine.eval("from java.util import ArrayList\nArrayList([1, 2]).size()")).isEqualTo(2);
    }

    @Test
    void passesPolyglotOptions() throws ScriptException {
        try (GraalPyScriptEngineFactory jython = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().polyglotOption("python.EmulateJython", "true").build())) {
            assertThat(jython.getScriptEngine().eval("import java.util.ArrayList as AL\nAL().isEmpty()")).isEqualTo(true);
        }
    }

    @Test
    void redirectsStandardStreams() throws ScriptException {
        final ScriptContext context = new SimpleScriptContext();
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        context.setWriter(out);
        context.setErrorWriter(err);
        context.setReader(new StringReader("line from reader\n"));

        engine.eval(
            """
            import sys
            print('hello', 'wörld')
            print('oops', file=sys.stderr)
            print(input().upper())
            """,
            context
        );

        assertThat(out.toString()).isEqualTo("hello wörld\nLINE FROM READER\n");
        assertThat(err.toString()).isEqualTo("oops\n");
    }

    @Test
    void reportsSyntaxErrorsWithLineNumber() {
        final ScriptContext context = new SimpleScriptContext();
        context.setAttribute(ScriptEngine.FILENAME, "broken.py", ScriptContext.ENGINE_SCOPE);

        assertThatThrownBy(() -> engine.eval("x = 1\ny = (", context)).isInstanceOfSatisfying(ScriptException.class, e -> {
            assertThat(e.getMessage()).contains("SyntaxError");
            assertThat(e.getFileName()).isEqualTo("broken.py");
            assertThat(e.getLineNumber()).isEqualTo(2);
        });
        assertThatThrownBy(() -> ((Compilable) engine).compile("def broken(:"))
            .isInstanceOf(ScriptException.class)
            .hasMessageContaining("SyntaxError");
    }

    @Test
    void reportsRuntimeErrorsWithLineNumber() {
        final ScriptContext context = new SimpleScriptContext();
        context.setAttribute(ScriptEngine.FILENAME, "failing.py", ScriptContext.ENGINE_SCOPE);

        assertThatThrownBy(() -> engine.eval("a = 1\nb = 2\nraise ValueError('bad value')", context)).isInstanceOfSatisfying(ScriptException.class, e -> {
            assertThat(e.getMessage()).contains("ValueError").contains("bad value");
            assertThat(e.getLineNumber()).isEqualTo(3);
        });
    }

    @Test
    void keepsJavaExceptionsAsCause() {
        final Bindings bindings = new SimpleBindings(new HashMap<>(Map.of("list", List.of())));

        assertThatThrownBy(() -> engine.eval("list.add(1)", bindings))
            .isInstanceOf(ScriptException.class)
            .hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void compiledScriptsAreReusable() throws ScriptException {
        final CompiledScript script = ((Compilable) engine).compile("value * 2");

        assertThat(script.eval(new SimpleBindings(new HashMap<>(Map.of("value", 4))))).isEqualTo(8);
        assertThat(script.eval(new SimpleBindings(new HashMap<>(Map.of("value", 5))))).isEqualTo(10);
        assertThat(script.getEngine()).isSameAs(engine);
    }

    @Test
    void evaluatesConcurrently() throws Exception {
        final CompiledScript script = ((Compilable) engine).compile(
            """
            total = 0
            for i in range(n):
                total += i
            total
            """
        );
        final ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            final List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final int n = i;
                tasks.add(() -> script.eval(new SimpleBindings(new HashMap<>(Map.of("n", n)))));
            }
            final List<Future<Object>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                assertThat(futures.get(i).get()).isEqualTo((i * (i - 1)) / 2);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(factory.pool().idleContexts()).isLessThanOrEqualTo(4);
    }

    @Test
    void writesBackOnlyValuesWithJavaRepresentation() throws ScriptException {
        final Bindings bindings = new SimpleBindings();

        engine.eval(
            """
            import java
            shout = lambda s: s.upper()
            now = java.type('java.lang.System').currentTimeMillis
            upper = java.type('java.util.function.Function').identity()
            """,
            bindings
        );

        assertThat(bindings).containsOnlyKeys("upper");
        // Java functional objects stay callable in later evaluations, whatever context runs them.
        assertThat(engine.eval("upper('same')", bindings)).isEqualTo("same");
    }

    @Test
    void skipsContainersHoldingPythonObjects() throws ScriptException {
        final Bindings bindings = new SimpleBindings();

        engine.eval("handlers = [lambda: 1]\nconfig = {'f': len}\nplain = [1, {'a': 2}]", bindings);

        assertThat(bindings).containsOnlyKeys("plain");
    }

    @Test
    void stopsConvertingSelfReferencingContainers() throws ScriptException {
        final Bindings bindings = new SimpleBindings();

        // Without a visit budget this takes 2^64 steps.
        engine.eval("loop = []\nloop.append(loop)\nloop.append(loop)", bindings);

        assertThat(bindings).doesNotContainKey("loop");
    }

    @Test
    void failingWriterDoesNotHideTheScriptError() throws ScriptException {
        final ScriptContext context = new SimpleScriptContext();
        context.setWriter(
            new Writer() {
                @Override
                public void write(final char[] buffer, final int offset, final int length) {
                    // accept
                }

                @Override
                public void flush() {
                    throw new IllegalStateException("writer closed");
                }

                @Override
                public void close() {
                    // nothing to release
                }
            }
        );

        // No print: the writer is only flushed when the evaluation releases its context.
        assertThatThrownBy(() -> engine.eval("raise KeyError('original')", context))
            .isInstanceOf(ScriptException.class)
            .hasMessageContaining("KeyError");
        // The context went back to the pool despite the failing writer and keeps working.
        assertThat(engine.eval("1 + 1", new SimpleScriptContext())).isEqualTo(2);
    }

    @Test
    void readsScriptsFromReaders() throws ScriptException {
        assertThat(engine.eval(new StringReader("6 * 7"))).isEqualTo(42);
        assertThat(((Compilable) engine).compile(new StringReader("'compiled'")).eval()).isEqualTo("compiled");
    }

    @Test
    void reportsAFailingReader() {
        final Reader broken = new Reader() {
            @Override
            public int read(final char[] buffer, final int offset, final int length) throws IOException {
                throw new IOException("disk gone");
            }

            @Override
            public void close() {
                // nothing to release
            }
        };

        assertThatThrownBy(() -> engine.eval(broken))
            .isInstanceOf(ScriptException.class)
            .hasMessageContaining("disk gone")
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void returnsProxiesAndDurations() throws ScriptException {
        final ProxyObject proxy = ProxyObject.fromMap(new HashMap<>(Map.of("k", 1)));
        final Bindings bindings = new SimpleBindings(new HashMap<>(Map.of("proxy", proxy)));

        assertThat(engine.eval("proxy", bindings)).isSameAs(proxy);
        assertThat(engine.eval("import datetime\ndatetime.timedelta(minutes=90)")).isEqualTo(Duration.ofMinutes(90));
        assertThat(engine.eval("import datetime\ndatetime.time(8, 30)")).isEqualTo(LocalTime.of(8, 30));
        assertThat(engine.eval("float('inf')")).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(engine.eval("bytearray(b'ab')")).isEqualTo(new byte[] { 'a', 'b' });
    }

    @Test
    void returnsPythonObjectsWithoutJavaFormAsValues() throws ScriptException {
        assertThat(engine.eval("class Thing: pass\nThing()")).isInstanceOf(Value.class);
    }

    @Test
    void keepsAFailingFlushAsSuppressedWhenTheScriptFailed() {
        final ScriptContext context = new SimpleScriptContext();
        context.setWriter(
            new Writer() {
                @Override
                public void write(final char[] buffer, final int offset, final int length) {
                    // accept
                }

                @Override
                public void flush() throws IOException {
                    throw new IOException("writer closed");
                }

                @Override
                public void close() {
                    // nothing to release
                }
            }
        );

        // write() without a newline stays in Python's buffer until the flush after the script.
        assertThatThrownBy(() -> engine.eval("import sys\nsys.stdout.write('pending')\nraise KeyError('original')", context))
            .isInstanceOf(ScriptException.class)
            .hasMessageContaining("KeyError")
            .satisfies(e -> assertThat(e.getCause().getSuppressed()).isNotEmpty());
        assertThatThrownBy(() -> engine.eval("import sys\nsys.stdout.write('pending')", context))
            .isInstanceOf(ScriptException.class)
            .hasMessageContaining("writer closed");
    }

    @Test
    void recyclesContextsAfterTheConfiguredNumberOfEvaluations() throws ScriptException {
        try (GraalPyScriptEngineFactory recycling = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().maxEvaluationsPerContext(2).maxIdleContexts(1).build())) {
            final ScriptEngine recycled = recycling.getScriptEngine();
            for (int i = 0; i < 5; i++) {
                assertThat(recycled.eval("import sys\nsys.flags is not None")).isEqualTo(true);
            }
            assertThat(recycling.pool().idleContexts()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void enforcesTheHostClassLookupFilter() throws ScriptException {
        try (GraalPyScriptEngineFactory restricted = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().hostClassLookup("java.util.").build())) {
            final ScriptEngine python = restricted.getScriptEngine();
            assertThat(python.eval("import java\njava.type('java.util.ArrayList')().size()")).isEqualTo(0);
            assertThatThrownBy(() -> python.eval("import java\njava.type('java.lang.System')")).isInstanceOf(ScriptException.class);
        }
    }

    @Test
    void closesAContextWhoseSetupFails() {
        // site import needs host file access, which is denied: the context fails while the support
        // module loads, and must be closed rather than leaked.
        try (
            GraalPyScriptEngineFactory failing = new GraalPyScriptEngineFactory(
                GraalPyEngineOptions.builder().polyglotOption("python.ForceImportSite", "true").polyglotOption("python.PythonPath", "/nonexistent").build()
            )
        ) {
            final ScriptEngine python = failing.getScriptEngine();
            try {
                python.eval("1");
            } catch (final RuntimeException | ScriptException e) {
                assertThat(e).isNotNull();
            }
        }
    }

    @Test
    void reportsErrorsOutsideTheScriptWithoutALineNumber() {
        assertThatThrownBy(() -> engine.eval("x = 1\0")).isInstanceOfSatisfying(ScriptException.class, e -> assertThat(e.getMessage()).isNotBlank());
    }

    @Test
    void reportsTheScriptLineForErrorsCarryingTheirOwnLineno() {
        // JSONDecodeError has a lineno attribute, for the JSON document; the script line is 3.
        assertThatThrownBy(() -> engine.eval("import json\nx = 1\njson.loads('{')")).isInstanceOfSatisfying(ScriptException.class, e -> assertThat(e.getLineNumber()).isEqualTo(3));
        assertThatThrownBy(() -> engine.eval("class E(Exception):\n    lineno = None\n\nraise E()")).isInstanceOfSatisfying(ScriptException.class, e ->
            assertThat(e.getLineNumber()).isEqualTo(4)
        );
    }

    @Test
    void retiresAContextAfterSystemExit() throws ScriptException {
        assertThatThrownBy(() -> engine.eval("raise SystemExit(3)")).isInstanceOf(ScriptException.class);

        assertThat(engine.eval("'still working'")).isEqualTo("still working");
    }

    @Test
    void finishesAnEvaluationThatClosesItsFactory() throws ScriptException {
        final GraalPyScriptEngineFactory closing = new GraalPyScriptEngineFactory();
        final Bindings bindings = new SimpleBindings(new HashMap<>(Map.of("factory", closing)));

        assertThat(closing.getScriptEngine().eval("factory.close()\n'done'", bindings)).isEqualTo("done");
        closing.close();
        assertThat(closing.pool().idleContexts()).isZero();
    }

    @Test
    void convertsFrozenSetsZonedDateTimesAndKeepsIterators() throws ScriptException {
        assertThat(engine.eval("frozenset([1])")).isEqualTo(Set.of(1));
        assertThat(engine.eval("import datetime\ndatetime.datetime(2024, 1, 2, 3, 4, tzinfo=datetime.timezone.utc)")).isInstanceOf(ZonedDateTime.class);
        assertThat(engine.eval("iter([1, 2])")).isInstanceOf(Value.class);
    }

    @Test
    void grantsFileAccessWhenConfigured() throws ScriptException {
        try (GraalPyScriptEngineFactory withIo = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().allowIO(true).build())) {
            assertThat(withIo.getScriptEngine().eval("import os\nos.path.isdir('.')")).isEqualTo(true);
        }
    }

    @Test
    void supportsNestedEvaluation() throws ScriptException {
        final Bindings bindings = new SimpleBindings(new HashMap<>(Map.of("engine", engine)));

        assertThat(engine.eval("engine.eval('20 + 1') * 2", bindings)).isEqualTo(42);
    }
}
