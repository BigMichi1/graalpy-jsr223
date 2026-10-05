# GraalPy JSR-223

A `javax.script` (JSR-223) script engine for **Python 3**, built on
[GraalPy](https://www.graalvm.org/python/), the Python implementation of GraalVM. It also includes a
plugin that lets [CIB seven](https://github.com/cibseven/cibseven) use it for inline scripts and
script expressions.

Until now, Jython has been the only JSR-223 Python engine for the JVM. Jython implements Python
2.7. This project offers Python 3 instead, with the standard library GraalPy ships, and keeps the
way scripts and bindings work familiar to people coming from Jython.

| Module | Artifact | Purpose |
|---|---|---|
| `graalpy-scriptengine` | `de.bigmichi1.graalpy:graalpy-scriptengine` | JSR-223 engine. Usable on its own, needs no CIB seven. |
| `graalpy-cibseven` | `de.bigmichi1.graalpy:graalpy-cibseven` | CIB seven process engine plugin (Spin support, env scripts) and integration tests |

Documentation:

- [docs/design.md](docs/design.md): architecture, design decisions, JSR-223 interface coverage, prior art,
  tests, performance, memory, and the known GraalPy defect
- [docs/cibseven.md](docs/cibseven.md): using the engine in CIB seven (BPMN, DMN, Spin, configuration)

## Quick start

```java
ScriptEngine python = new ScriptEngineManager().getEngineByName("python");

Bindings bindings = python.createBindings();
bindings.put("order", Map.of("amount", 120, "currency", "EUR"));

Object total = python.eval("""
        vat = 0.25
        order['amount'] * (1 + vat)
        """, bindings);          // -> 150.0 (Double)

bindings.get("vat");             // -> 0.25, written back because the script assigned it
```

- The engine is registered under the names `python`, `graalpy`, `GraalPy`, `python3` and `py`, the
  extension `py`, and the MIME types `text/x-python` and `application/x-python`.
- `eval` returns the value of the script's last expression statement, or `null` if the script ends
  with something else.
- Top-level variables a script assigns are written back to the engine-scope bindings. Python
  values are converted to Java first (`dict` → `LinkedHashMap`, `list` → `ArrayList`, ...).
- `print` writes to `ScriptContext.getWriter()`.
- Java is reachable with `import java` and `java.type("java.util.ArrayList")`. Objects passed in
  through bindings can be used directly.

## Building

The project uses [mise](https://mise.jdx.dev/) to pin its tooling (Temurin JDK 25 and Gradle 9.8):

```sh
mise install
./gradlew build
```

`./gradlew build` compiles both modules and runs the unit and CIB seven integration tests. It fails if a
module's tests cover less than 90 % of its lines.

Outside the build, because they take minutes:

- `./gradlew :graalpy-scriptengine:jmh` runs the JMH benchmarks (see
  [Performance](docs/design.md#performance)).
- `./gradlew :graalpy-scriptengine:leakCheck` runs the heap growth check (see
  [Memory](docs/design.md#memory)).

GraalPy 25.1 and later lose a script's error, or its line number, when the exception passes a Python
frame with an exception handler. The engine works around this. See
[Known GraalPy defect](docs/design.md#known-graalpy-defect), reported as
[oracle/graalpython#1186](https://github.com/oracle/graalpython/issues/1186).

## Requirements and runtime notes

- **Java 21 or newer.** The jars target Java 21.
- **Dependencies:** `org.graalvm.polyglot:polyglot` and `org.graalvm.polyglot:python-community`
  25.4.x, pulled in transitively.
- **Interpreter mode on stock JDKs.** On a regular OpenJDK, GraalPy runs in Truffle's interpreter
  mode, which works but is slower. For JIT-compiled Python, run on a GraalVM JDK or enable the
  Truffle optimizing runtime.
- **JDK 24+ warnings.** Truffle uses native access and `sun.misc.Unsafe`. Add
  `--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow` to the JVM to silence
  the warnings.
- **Startup cost.** The first Python context takes a few seconds to create, later ones take a few
  hundred milliseconds. The engine pools contexts, so this is paid once per pool slot, not once per
  script.

## Configuration

Configure the engine with `graalpy.jsr223.*` system properties, or in code with
`new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder()...build())`.

| Property | Default | Meaning |
|---|---|---|
| `graalpy.jsr223.hostAccess` | `all` | Which Java members scripts may use: `all`, `constrained`, `explicit` or `none` |
| `graalpy.jsr223.hostClassLookup` | `*` | Classes `java.type()` may load: `*` for all, empty for none, or comma-separated prefixes |
| `graalpy.jsr223.allowIO` | `false` | Host file system access. The standard library works without it. |
| `graalpy.jsr223.allowCreateThread` | `false` | Let scripts start threads |
| `graalpy.jsr223.allowNativeAccess` | `false` | Native extensions and `ctypes` |
| `graalpy.jsr223.maxIdleContexts` | number of CPUs | How many warm contexts the pool keeps |
| `graalpy.jsr223.maxEvaluationsPerContext` | `0` (never) | Recycle a context after this many evaluations |
| `graalpy.jsr223.compilationCacheSize` | `256` | Compiled scripts cached per context |
| `graalpy.jsr223.writeBack` | `true` | Write assigned top-level variables back to the bindings |
| `graalpy.jsr223.option.<name>` | | Raw polyglot option, e.g. `graalpy.jsr223.option.python.PythonPath=/opt/scripts` |

See [docs/design.md](docs/design.md#security) for the security implications of these defaults.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
