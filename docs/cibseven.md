# Using GraalPy in CIB seven

## Setup

1. Put `graalpy-cibseven` on the process engine's classpath. It brings in `graalpy-scriptengine` and
   the GraalPy runtime.
2. Register the plugin. It is required when Spin is on the classpath and recommended in every case:

   ```java
   configuration.getProcessEnginePlugins().add(new GraalPyProcessEnginePlugin());
   ```

   With `bpm-platform.xml` / `processes.xml`:

   ```xml
   <plugin>
     <class>de.bigmichi1.graalpy.cibseven.GraalPyProcessEnginePlugin</class>
   </plugin>
   ```

   With Spring Boot, expose the plugin as a `ProcessEnginePlugin` bean.

3. **Remove Jython from the classpath.** Both engines register as `python`, and
   `ScriptEngineManager` returns whichever it finds first. If you must keep both for a while, use
   `scriptFormat="graalpy"` for GraalPy scripts.

The engine itself needs no plugin. CIB seven's `DefaultScriptEngineResolver` finds it through
`ScriptEngineManager.getEngineByName(language)`. Because the factory declares
`THREADING=MULTITHREADED`, CIB seven caches one engine instance and its compiled scripts
(`enableScriptEngineCaching`, `enableScriptCompilation`, both on by default).

## What the plugin does

CIB seven runs _environment scripts_ for a language before every script of that language. Spin
registers `script/env/python/spin.py` for `python`, and that script is Jython code
(`import org.cibseven.spin.Spin.S as S`) which fails on GraalPy. Even for
`scriptFormat="graalpy"`, CIB seven falls back to the factory's language name (`python`) when
looking up environment scripts.

The plugin acts in `postInit` and again in `postProcessEngineBuild`, so it also catches a Spin
plugin registered after it (Spin adds its resolver in its own `postInit`). Register it after Spin's
plugin when the job executor starts with the engine: a Python job run between the two hooks would
still see Spin's Jython script. If Spin is on the classpath but cannot be loaded, the engine fails to
start rather than silently running Python scripts without `S`.

- It wraps Spin's resolver so that it returns nothing for the GraalPy names (`python`, `graalpy`,
  `python3`, `py`). Other languages are unaffected.
- If Spin is present, it registers a GraalPy environment script that defines `S`, `JSON` and `XML`
  as Java functional objects (`GraalPySpin`). The script uses
  `java.type("de.bigmichi1.graalpy.cibseven.GraalPySpin")`, so keep that class allowed if you
  restrict `graalpy.jsr223.hostClassLookup`.

## Where Python can be used

Each example below is covered by `CibSevenIntegrationTest`.

### Script task with result variable

```xml
<bpmn:scriptTask id="sum" scriptFormat="python" camunda:resultVariable="sum">
  <bpmn:script>a + b</bpmn:script>
</bpmn:scriptTask>
```

The value of the last expression becomes `sum`. Python `int` arrives as `Integer` (or `Long` or
`BigInteger` when larger), `float` as `Double`.

### Setting variables

```python
import json

def describe(value):
    return {'value': value, 'even': value % 2 == 0}

execution.setVariable('doubled', sum * 2)
execution.setVariable('description', json.dumps(describe(sum)))
local_only = 'stays local'
```

`execution` (and `task` in task listeners), process variables, and beans resolved by CIB seven are
available by name. Top-level assignments such as `local_only` go through `Bindings.put`. CIB seven
turns them into process variables only when `autoStoreScriptVariables` is enabled, which is the same
behaviour as with Jython. Use `execution.setVariable` to be explicit.

### Conditions

```xml
<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression" language="python">doubled &gt; 10</bpmn:conditionExpression>
```

### Listeners and input/output mappings

```xml
<camunda:executionListener event="start">
  <camunda:script scriptFormat="python">execution.setVariable('listenerRan', True)</camunda:script>
</camunda:executionListener>

<camunda:inputParameter name="greeting">
  <camunda:script scriptFormat="python">'Hello ' + name.upper()</camunda:script>
</camunda:inputParameter>
```

### DMN expressions

Input expressions, input entries (with `cellInput`) and output entries can all use Python:

```xml
<inputExpression typeRef="integer" expressionLanguage="python"><text>amount * quantity</text></inputExpression>
<inputEntry expressionLanguage="python"><text>cellInput &gt;= 1000</text></inputEntry>
<outputEntry expressionLanguage="python"><text>'HIGH'</text></outputEntry>
```

To make Python the default for a whole engine, set `defaultInputExpressionExpressionLanguage` and
the related properties of the DMN engine configuration to `python`.

### Spin

```python
customer = S(payload, 'application/json')
execution.setVariable('customerName', customer.prop('name').stringValue())
execution.setVariable('itemCount', JSON(payload).prop('items').elements().size())
```

### BPMN errors

```python
import java
BpmnError = java.type('org.cibseven.bpm.engine.delegate.BpmnError')
if amount < 0:
    raise BpmnError('REJECTED', 'negative amount')
```

The Java exception is kept as the cause of the `ScriptException`, so CIB seven routes it to a
matching error boundary event.

### External script resources

`camunda:resource="classpath://scripts/check.py"` works like inline scripts. The resource name is
not passed as `ScriptEngine.FILENAME` by CIB seven, so tracebacks show `<script>`.

## Migration notes from Jython

| Jython                                                   | GraalPy                                                                                                                               |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `print "x"`                                              | `print("x")`                                                                                                                          |
| `from org.cibseven.bpm.engine.delegate import BpmnError` | `BpmnError = java.type('org.cibseven.bpm.engine.delegate.BpmnError')` (or enable `-Dgraalpy.jsr223.option.python.EmulateJython=true`) |
| `from java.util import ArrayList`                        | works unchanged (`java.*` packages)                                                                                                   |
| `unicode`, `long`, `xrange`, `dict.iteritems()`          | `str`, `int`, `range`, `dict.items()`                                                                                                 |
| `5 / 2 == 2`                                             | `5 / 2 == 2.5`, use `//` for integer division                                                                                         |
| functions and imported modules end up in bindings        | not written back; only data is                                                                                                        |

## Operational notes

- **Warm-up.** The first Python script after startup creates the first context, which takes a few
  seconds. Later contexts take a few hundred milliseconds, and pooled contexts are reused.
  `graalpy.jsr223.maxIdleContexts` should roughly match the number of threads that run scripts at
  the same time (job executor threads plus request threads).
- **Performance.** Run on a GraalVM JDK for JIT-compiled Python. On other JDKs GraalPy uses
  interpreter mode.
- **JVM flags.** On the current JDK, add `--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow`.
