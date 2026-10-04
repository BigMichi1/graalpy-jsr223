# Design

This document explains how the GraalPy JSR-223 engine works, why it is built this way, which
JSR-223 interfaces it implements and which it deliberately does not.

## Goals

1. **Python 3 through the standard `javax.script` API.** Any JSR-223 host must be able to find it
   with `ScriptEngineManager.getEngineByName("python")`.
2. **A drop-in provider for CIB seven.** That covers script tasks, execution and task listeners,
   input/output mappings, conditional sequence flows, and DMN expressions written in Python.
3. **Behaviour Jython users recognise:**
   - bindings are readable as variables;
   - assignments flow back into the bindings;
   - `eval` returns the value of the last expression.
4. **Safe for process engines.** The engine must handle concurrent use, keep evaluations isolated
   from each other, and never enumerate bindings eagerly.

Not goals: embedding pip packages (see [Extending the Python path](#extending-the-python-path)) or
sandboxing untrusted code beyond what the polyglot API offers (see [Security](#security)).

## Architecture

```
 javax.script host (ScriptEngineManager, CIB seven, ...)
        │  ServiceLoader: META-INF/services/javax.script.ScriptEngineFactory
        ▼
 GraalPyScriptEngineFactory ── owns ──► polyglot Engine (shared, created lazily)
        │                                    │
        │ getScriptEngine() (cheap)          ▼
        ▼                              ContextPool ── PooledContext × n
 GraalPyScriptEngine  ── borrow/release ──►   ├─ polyglot Context (python)
   (AbstractScriptEngine, Compilable)         ├─ RedirectingStreams (stdout/stderr/stdin)
        │                                     └─ jsr223_support.py: prepare() / execute()
        ▼
 GraalPyCompiledScript (source only, shareable)
```

| Class                           | Responsibility                                                                                                                                   |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `GraalPyScriptEngineFactory`    | JSR-223 metadata (names, MIME types, `THREADING`). Owns the shared polyglot `Engine` and the context pool, both created lazily. `AutoCloseable`. |
| `GraalPyScriptEngine`           | The `eval`/`compile` entry points. Bridges `ScriptContext` and Python: looks up names, writes changes back, maps errors.                         |
| `GraalPyCompiledScript`         | A syntax-checked script that keeps only its source, so it is safe to cache and share across threads.                                             |
| `ContextPool` / `PooledContext` | A non-blocking pool of Python contexts with thread affinity, retirement of broken contexts and an optional recycle limit.                        |
| `RedirectingStreams`            | Byte streams installed into each context once, forwarding to the `Writer`/`Reader` of the evaluation currently running.                          |
| `ValueConverter`                | Converts Python results and written-back variables into plain Java objects.                                                                      |
| `GraalPyEngineOptions`          | Immutable configuration, read from `graalpy.jsr223.*` system properties or a builder.                                                            |
| `jsr223_support.py`             | Python half of the bridge: parsing, rewriting the trailing expression, the per-context compile cache, namespace execution and change detection.  |

## Evaluation flow

For `engine.eval(script, scriptContext)`:

1. **Borrow** a context from the pool. The pool prefers the context this thread used last.
2. **Attach streams.** The context's stdout, stderr and stdin now point at the `ScriptContext`'s
   writer, error writer and reader.
3. **`prepare(source, filename)`** in Python, cached per context:
   - `ast.parse` the source.
   - If the last statement is an expression, rewrite it into `__jsr223_result__ = <expr>`.
   - Collect every identifier the script uses (`ast.Name` nodes), including identifiers used inside
     functions.
   - `compile()` the result.
4. **Resolve names.** For each collected identifier, `ScriptContext.getAttributesScope(name)` looks
   in the engine scope first, then the global scope. Only names that exist are passed in.
5. **`execute`** in Python:
   - Build a **fresh module namespace** (`__name__ == "__main__"`, `__file__`, builtins) seeded with
     the resolved values.
   - `exec` the code.
   - Flush `sys.stdout` and `sys.stderr`.
   - Return `[has_result, result, changes]`. `changes` lists every top-level name that is new or
     re-bound (identity comparison) and is neither private (`_x`), a module, a function nor a class.
6. **Write back.** Each changed value goes through `ValueConverter` and then
   `scriptContext.setAttribute(name, value, ENGINE_SCOPE)`, which ends in `Bindings.put`. Values with
   no Java representation are skipped (see below).
7. **Return** the converted result, or `null` if the script did not end in an expression.
8. **Release** the context. It goes back to the pool, or is closed if it is broken, over its
   recycle limit, or the pool is full.

## Design decisions

### Pooled contexts, fresh namespaces

| Option                                                                               | Isolation                                                                                                     | Cost per eval                                                  | Thread-safe            | Verdict                                                                          |
| ------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- | ---------------------- | -------------------------------------------------------------------------------- |
| New `Context` per eval                                                               | perfect                                                                                                       | ~0.5 s (shared engine, interpreter mode), several seconds cold | yes                    | too slow for a process engine                                                    |
| One `Context` per `ScriptEngine` (GraalJS and the community GraalPy engines do this) | globals leak between evals                                                                                    | low                                                            | no, `THREADING = null` | CIB seven would then create a new engine, and so a new context, for every script |
| **Context pool + fresh module dict per eval**                                        | Python globals isolated; interpreter-level state (`sys.modules`, monkey patches) is shared within one context | low                                                            | yes                    | **chosen**                                                                       |

The shared polyglot `Engine` lets all contexts share parsed and compiled code, including the
support module, which is loaded as a cached `Source`. Because every evaluation starts from a fresh
namespace, a script never sees variables from an earlier evaluation. Only an explicit mutation of
interpreter-wide state, such as patching a stdlib module, can leak into other evaluations on the
same context. `maxEvaluationsPerContext` caps that, if needed.

The pool **never blocks**. When no context is idle it creates one, and when more than
`maxIdleContexts` are returned the extras are closed. A script can call Java code that evaluates
another script on the same thread, for example a listener firing inside `execution.setVariable`.
A bounded, blocking pool could deadlock there.

Contexts are discarded when a `PolyglotException` reports cancellation, exit, an internal error,
resource exhaustion or an interrupt. A context that fails to close is logged at WARN and dropped.

### Lazy, name-driven binding lookup

CIB seven's `ScriptBindings` can enumerate entries, but enumerating loads **every** process
variable and bean. Some process variables are serialized objects, so this can be expensive and can
even fail. The community GraalPy engines copy `entrySet()` into Python globals. This engine asks
only for the identifiers the script mentions, found by walking its AST. Two alternatives were
rejected:

- **A mapping-backed `locals` for `exec`.** Top-level code would see the bindings, but functions
  defined in the script would not, because functions resolve names through `globals`.
- **A custom `globals` mapping.** CPython semantics require a real `dict` for `globals`, and guest
  behaviour for subclasses is not guaranteed.

Bindings take precedence over builtins. A process variable named `input` shadows the builtin, just
as in Jython.

### Result of `eval`

Python's `exec` has no result value, but JSR-223 hosts depend on one: a conditional sequence flow
needs a boolean, a script task needs the value for its `resultVariable`, and a DMN expression needs
its value. A trailing expression statement is therefore rewritten into an assignment to a hidden
name, the same trick interactive shells use. A one-line script such as `amount > 100` simply
returns its value.

### Write-back and value conversion

Assignments are written back so that Jython-style scripts keep working, and so that environment
scripts can hand data and Java objects to the main script (such as the functional-interface objects of
`GraalPySpin`). Python functions and lambdas an environment script defines are not visible to the
main script. Excluded from write-back:

- **Private names** (`_tmp`). This gives script authors scratch variables that never reach the
  bindings.
- **Modules, functions and classes.** They are program structure, not data.
- **Unchanged bindings.** Re-putting every injected value would cause needless variable updates in
  CIB seven when `autoStoreScriptVariables` is enabled.
- **Values without a Java representation**, such as lambdas, Python objects or bound host methods,
  also when nested anywhere inside a list, dict or set. Each skip is logged at DEBUG.
  A guest value belongs to the pooled context that created it. If it outlived the evaluation in
  the caller's bindings, the next evaluation (possibly on another context, or after the context is
  closed) would fail with _"Context execution was cancelled"_ or a cross-context error. This was
  found during development and is pinned by a test.

`ValueConverter` maps results:

| Python               | Java                                                                    |
| -------------------- | ----------------------------------------------------------------------- |
| `None`               | `null`                                                                  |
| `bool`               | `Boolean`                                                               |
| `int`                | `Integer`, `Long` or `BigInteger`, whichever is the narrowest that fits |
| `float`              | `Double`, also for integral values such as `2.0`                        |
| `str`                | `String`                                                                |
| `bytes`, `bytearray` | `byte[]`                                                                |
| `list`, `tuple`      | `ArrayList`                                                             |
| `dict`               | `LinkedHashMap`                                                         |
| `set`, `frozenset`   | `LinkedHashSet`                                                         |
| `datetime` types     | `LocalDate`, `LocalTime`, `LocalDateTime` or `ZonedDateTime`            |
| `timedelta`          | `Duration`                                                              |
| Java objects         | the original object                                                     |

Anything else is returned as a polyglot `Value` (from `eval` only). Containers are converted
deeply, up to 64 levels and at most one million values per conversion, which stops self-referencing
containers; what lies beyond stays a `Value`. A `Value` belongs to a pooled context: a caller that
stores one (for example as a CIB seven `resultVariable`) gets an error later, when the value is
serialized or read. Let scripts end in plain data.

### Errors

| Failure                                                                              | `ScriptException`                                                                                                                                                                           |
| ------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Python exception                                                                     | Message `Type: text`. File name from `ScriptEngine.FILENAME`. Line number from the guest stack frame of the script; for a `SyntaxError`, from its `lineno`. Cause: the `PolyglotException`. |
| Java exception thrown by called Java code (including `raise SomeJavaException(...)`) | Cause: the original Java exception. CIB seven looks for `BpmnError` in the cause chain, so `raise BpmnError('CODE')` from Python triggers boundary error events.                            |

### Streams

A polyglot context's streams can only be set when the context is built, but every JSR-223
evaluation can bring its own `Writer`. Each pooled context therefore gets delegating streams that
are re-pointed on borrow and detached on release. The output stream decodes UTF-8 incrementally, so
multi-byte characters split across writes are not corrupted; detaching resets the decoder, so a
partial character never reaches the next evaluation's writer.

- Python's buffers are flushed after the script. If the script failed, a flush failure is attached to
  its exception as suppressed and does not replace it. If the script succeeded, a flush failure is
  the evaluation's error.
- The final flush into the writer on release cannot fail the evaluation; it is logged at WARN.
- Output written while no evaluation is attached (by a thread a script left running) has no writer
  and is dropped.
- The flush runs as a separate call from Java, outside Python's `execute` frame, because of the
  [GraalPy defect](#known-graalpy-defect) with exception handlers in that frame.

## JSR-223 interface coverage

The `javax.script` package defines these types. The table shows what the engine implements and why.

| Type                                    | Kind           | Status                                                              | Reason                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| --------------------------------------- | -------------- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ScriptEngineFactory`                   | interface      | **Implemented** (`GraalPyScriptEngineFactory`)                      | Required: `ScriptEngineManager` discovers engines through `ServiceLoader` and this interface. Also carries the `THREADING` parameter CIB seven checks before caching an engine.                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `ScriptEngine`                          | interface      | **Implemented** (`GraalPyScriptEngine`, via `AbstractScriptEngine`) | Required: the core `eval` API.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `AbstractScriptEngine`                  | abstract class | **Extended**                                                        | Supplies the standard overloads (`eval(String)`, `eval(String, Bindings)`, ...), the default `ScriptContext`, and `get`/`put`. Re-implementing them would only risk deviating from the spec.                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `Compilable`                            | interface      | **Implemented**                                                     | CIB seven compiles scripts once per process definition when `enableScriptCompilation` is on (the default) and the engine is `Compilable`. Without it, every execution would pass the full source again. It also reports syntax errors at deployment or first use rather than mid-process.                                                                                                                                                                                                                                                                                                                                                 |
| `CompiledScript`                        | abstract class | **Implemented** (`GraalPyCompiledScript`)                           | Required by `Compilable`. Keeps only the source. Bytecode is cached in every pooled context, because CIB seven shares a `CompiledScript` across threads and engine instances.                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `Bindings`                              | interface      | **Not implemented**; uses `SimpleBindings`                          | The engine never exposes Python state as a live map. Every evaluation has its own namespace and talks to the bindings only through `get`/`containsKey`/`put`. A custom `Bindings` (as GraalJS has) is needed only when bindings _are_ the guest globals, which this design deliberately avoids. Any host bindings work, including CIB seven's `ScriptBindings`.                                                                                                                                                                                                                                                                           |
| `ScriptContext`                         | interface      | **Not implemented**; uses `SimpleScriptContext`                     | Standard scope handling (engine scope before global scope) is exactly what is needed. Writers and readers are taken from whatever context the caller passes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `SimpleBindings`, `SimpleScriptContext` | classes        | **Used**                                                            | Defaults from the JDK.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `Invocable`                             | interface      | **Not implemented**, by design                                      | `invokeFunction`/`invokeMethod`/`getInterface` assume functions defined by an earlier `eval` stay alive in the engine. Here every evaluation runs in a fresh namespace on a context that is then returned to the pool, and functions are not written back. A persistent per-engine namespace would break thread safety and isolation, which CIB seven depends on. CIB seven never uses `Invocable`. Alternative: evaluate `fn(arg)` with the arguments in the bindings, or put a Python function in a module on the Python path. A future `Invocable` could be offered as a separate, explicitly stateful engine type if there is demand. |
| `ScriptEngineManager`                   | class          | **Not applicable**                                                  | Provided by the JDK. Discovery works through `META-INF/services/javax.script.ScriptEngineFactory`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `ScriptException`                       | class          | **Used**                                                            | All errors are reported as `ScriptException`, with file name, line number and cause.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |

## Comparison with Jython

| Aspect                  | Jython 2.7 engine                           | GraalPy engine                                                                                              |
| ----------------------- | ------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Language                | the 2.x line of Python                      | Python 3 (GraalPy's current CPython compatibility level)                                                    |
| Variables from bindings | lazy, through scope `__getitem__`           | lazy, through the AST name scan                                                                             |
| Assignments to bindings | every name, including modules and functions | data only; `_private` names excluded                                                                        |
| `eval` result           | last expression (Jython-specific)           | last expression                                                                                             |
| Java access             | `from java.util import ArrayList`           | `import java; java.type('java.util.ArrayList')`, or `from java.util import ArrayList` for `java.*` packages |
| Threading               | `MULTITHREADED`, shared interpreter state   | `MULTITHREADED`, pooled contexts with fresh namespaces                                                      |
| File and native access  | unrestricted                                | denied by default, configurable                                                                             |

Migrating scripts from Jython means porting them to Python 3 (`print`, `unicode`, integer
division, ...) and rewriting `import org.foo.Bar`-style Java imports for packages outside `java.*`
to use `java.type`. GraalPy's `python.EmulateJython` option can be passed as a polyglot option for
more Jython-compatible imports.

## Security

The defaults trust script authors, as Jython and CIB seven's Groovy and JavaScript support do.
Scripts are part of deployed process definitions:

- `hostAccess=all` and `hostClassLookup=*`: scripts can call any public Java API, including
  `System.exit` or reflection. Restrict these with `graalpy.jsr223.hostAccess=explicit|none` and
  `graalpy.jsr223.hostClassLookup=<prefixes>` if script authors are less trusted than the
  application.
- File system, native code and thread creation are **off** by default. The standard library still
  loads from GraalPy's internal resources.
- **This is not a sandbox against hostile code.** There is no CPU or memory limit. Oracle GraalVM
  offers sandbox policies and resource limits that can be passed through `graalpy.jsr223.option.*`
  where supported.

## Extending the Python path

Pure-Python modules can be put on the path with
`-Dgraalpy.jsr223.option.python.PythonPath=/opt/process-scripts` (this needs `allowIO=true` to read
from the host file system). Packages with native code need GraalPy's own packaging tools, such as
the GraalPy Gradle and Maven plugins with their virtual filesystem, which this project does not
include yet.

## Prior art

These projects were studied for ideas. No code was copied.

| Project                                                                           | What it does                                                                                                                                                                                                          | Takeaway                                                                                                                                                                                                  |
| --------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GraalJS `js-scriptengine` (`GraalJSScriptEngine`, `GraalJSBindings`)              | Official JSR-223 engine for JavaScript on GraalVM. One context per bindings object, "magic" `polyglot.js.*` binding keys for options, delegating stdout/stderr streams, `Compilable` via `parse`, `THREADING = null`. | Adopted: shared `Engine`, delegating streams, host exceptions kept as cause. Rejected: context-per-bindings and non-thread-safe engines, which make CIB seven create a new engine for every script.       |
| GraalVM reference manual, "Compatibility with JSR-223 ScriptEngine"               | Minimal single-file template for any polyglot language                                                                                                                                                                | Too minimal: `createBindings`, `setContext` and `eval(String, Bindings)` are unsupported, and CIB seven needs all of them.                                                                                |
| [datakurre/operaton-python](https://github.com/datakurre/operaton-python)         | GraalPy engine for Operaton, another Camunda 7 fork                                                                                                                                                                   | Closest prior art. Copies `entrySet()` into globals, uses one context per engine, discards stdout, never writes back. Its approach to a Spin environment script for GraalPy confirmed that one is needed. |
| openHAB `GraalPythonScriptEngine`, JMRI, invesdwin-scripting                      | GraalPy engines adapted from the GraalJS classes; invesdwin pools engines                                                                                                                                             | Confirms that pooling is the practical answer to context startup cost.                                                                                                                                    |
| CIB seven `DefaultScriptEngineResolver`, `ScriptBindings`, `ScriptingEnvironment` | How the host behaves                                                                                                                                                                                                  | Drove the decisions on the `THREADING` parameter, a shareable `CompiledScript`, lazy lookup, `BpmnError` as cause, and the Spin environment fix.                                                          |

## Testing

`./gradlew build` runs the following suites and fails if a module's own tests cover less than 90 % of
its lines (jacoco). Measured: `graalpy-scriptengine` 98.69 % of lines and 92.88 % of branches,
`graalpy-cibseven` 100 % of lines and 95.83 % of branches.

- `GraalPyScriptEngineTest`:
  - discovery, result conversion, scope lookup and lazy lookup;
  - write-back rules (also nested Python objects and self-referencing containers) and isolation;
  - streams: UTF-8, stdin, failing writers;
  - syntax and runtime errors with line numbers, Java exception causes, `SystemExit`;
  - compiled script reuse, 64 concurrent evaluations on 8 threads, nested evaluation;
  - context recycling, host class filter, file access, closing a factory mid-evaluation.
- `GraalPyEngineOptionsTest`, `GraalPyScriptEngineFactoryTest`, `RedirectingStreamsTest`,
  `ContextPoolTest`, `ClasspathResourcesTest`: configuration, metadata, stream edge cases, the
  pool's thread affinity, and that a closed factory leaves nothing reachable from a thread.
- `CibSevenIntegrationTest` runs an H2 in-memory CIB seven engine through:
  - script tasks with `resultVariable`, execution listeners and input/output mappings;
  - Python conditions on an exclusive gateway, and Spin `S()`/`JSON()`;
  - `BpmnError` raised from Python to a boundary event, and error reporting;
  - a DMN decision table with Python input expressions, input entries and output entries.
- `GraalPySpinTest`, `GraalPyProcessEnginePluginTest`: Spin functions and the resolver rewiring.

## Performance

`./gradlew :graalpy-scriptengine:jmh` runs the JMH benchmarks in `src/jmh`. Pass JMH arguments with
`--args`, e.g. `--args="-f 1 EvalBenchmark"`; the results go to `build/reports/jmh/results.json`.
They are not part of `check`.

One run on a developer machine: Temurin JDK, interpreter-only Truffle, WSL2, 5×2 s warm-up and
measurement, one fork. Every evaluation uses fresh `SimpleBindings`, as CIB seven does. The error
bars are wide on this machine, so read the numbers as orders of magnitude:

| Benchmark                                                              | Result                   |
| ---------------------------------------------------------------------- | ------------------------ |
| Python condition `amount > 100`, compiled                              | 17 µs ± 5                |
| same, 4 threads at once                                                | 18 µs ± 1 per evaluation |
| same, not compiled (the source text is cached per context)             | 13 µs ± 7                |
| script task: generator over a 100-element Java list                    | 82 µs ± 43               |
| script writing a dict back to the bindings                             | 24 µs ± 10               |
| Python loop of 1000 iterations                                         | 450 µs ± 424             |
| GraalJS (`js-scriptengine`), the same condition                        | 1990 µs ± 4193           |
| an evaluation that has to create its context (pool empty, engine warm) | 252 ms ± 162             |
| the first evaluation of a new factory in a fresh JVM                   | 8.9 s                    |

What this means:

- A pooled evaluation costs microseconds. Creating a context costs a quarter of a second, and a cold
  engine seconds. Pooling, and a factory created once per JVM, are what make Python usable per script.
- The 4-thread run costs the same per evaluation as the single-threaded one, so the pool does not
  serialise evaluations.
- GraalJS's own script engine is about 100 times slower in this pattern, because it builds a new
  polyglot context for every new `Bindings` object.
- On a GraalVM JDK, or with the Truffle optimizing runtime enabled, the loop and script-task numbers
  should drop considerably; that was not measured here.

## Memory

`./gradlew :graalpy-scriptengine:leakCheck` (`src/jmh/.../LeakCheck.java`) runs each workload for 2
warm-up and 8 measured rounds of 2 000 evaluations, and measures the heap after a full GC between
rounds. It fails if any workload grows by more than 8 MB.
`-PleakCheck.rounds=30 -PleakCheck.only=<name>` narrows a run to one workload.

| Workload                                                     | Heap after GC over the measured rounds |
| ------------------------------------------------------------ | -------------------------------------- |
| compiled script with write-back                              | flat, 48.8 MB                          |
| a unique source text per evaluation (compile cache eviction) | flat, 50.2–50.5 MB                     |
| errors with tracebacks                                       | flat, 50.3 MB                          |
| output into a writer                                         | flat, 50.4 MB                          |
| 4 pool threads                                               | flat, 59.7 MB                          |
| a new short-lived thread per evaluation                      | flat, 59.7 MB                          |
| factories created and closed, 30 rounds of 5                 | flat, 48.4 MB                          |

The last workload found the one retention problem. The pool remembered each thread's last context in
a strong thread-local reference, so a long-lived thread kept a closed factory's context, and its
engine, reachable. Before the fix, the heap moved between 84 and 133 MB in steps of one engine
(about 12 MB). The reference is weak, and `ContextPoolTest` pins it.

## Known GraalPy defect

Since GraalPy 25.1, whose bytecode interpreter is the only one, a Python exception that leaves a frame
through a `try`/`finally` or a `try`/`except … raise` reaches Java wrong. `1 / 0` is not affected;
`NameError` and an explicit `raise` are.

- **With Java assertions on (`-ea`, as in every test run):** `Value.execute` throws
  `IllegalArgumentException: Bytecode index out of range`, and the script's error is lost.
- **Without assertions:** the stack trace lacks the frame of the `exec`'d code, and the handler frame
  has no location, so the script's line number is lost.

| GraalPy             | `-ea` crash                | Script frame in the stack trace                                                                       |
| ------------------- | -------------------------- | ----------------------------------------------------------------------------------------------------- |
| 25.1.3 – 25.4.4.1.1 | yes                        | missing                                                                                               |
| 24.0.2 – 25.0.4     | no                         | present but without a location, and the handler frame points at the wrong source (wrong line numbers) |
| 23.1                | does not start on this JDK | —                                                                                                     |

No release behaves correctly. The engine works around the defect: `jsr223_support.py` has no
exception handler in the frame that executes the script, and Python's output is flushed by a separate
call from Java (`GraalPyScriptEngine.evaluate`). Reported upstream as
[oracle/graalpython#1186](https://github.com/oracle/graalpython/issues/1186), with the reproducer
[BigMichi1/graalpy-reraise-stacktrace](https://github.com/BigMichi1/graalpy-reraise-stacktrace).
When a fixed release ships, the workaround can go and the flush can move back into Python.

## Possible next steps

- Evaluation time limits via `Context.interrupt(Duration)`.
- Integration with the GraalPy Gradle plugin for bundling pip packages in a virtual filesystem.
- Optional pre-warming of the pool at startup.
- Publishing to a Maven repository.

