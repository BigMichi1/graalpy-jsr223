package de.bigmichi1.graalpy.jsr223;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

import org.graalvm.polyglot.Engine;

/**
 * JSR-223 factory for {@link GraalPyScriptEngine}.
 *
 * <p>Registered through {@code META-INF/services/javax.script.ScriptEngineFactory}, so
 * {@code new ScriptEngineManager().getEngineByName("python")} finds it. The factory owns a shared
 * polyglot {@link Engine} and a pool of Python contexts, both created on first use. All script
 * engines obtained from one factory share them, which makes {@link #getScriptEngine()} cheap.
 *
 * <p>{@link #getParameter(String) THREADING} is {@code MULTITHREADED}: one engine instance may be
 * used concurrently. Process engines such as CIB seven cache the engine only if this parameter is
 * set.
 */
public final class GraalPyScriptEngineFactory implements ScriptEngineFactory, AutoCloseable {

    /** Language id of GraalPy in the polyglot API. */
    public static final String LANGUAGE_ID = "python";

    /** Names this factory registers under, e.g. for {@code ScriptEngineManager.getEngineByName}. */
    public static final List<String> NAMES = List.of("python", "graalpy", "GraalPy", "python3", "py");
    private static final List<String> EXTENSIONS = List.of("py");
    private static final List<String> MIME_TYPES = List.of("text/x-python", "application/x-python");
    private static final String WARN_INTERPRETER_ONLY = "engine.WarnInterpreterOnly";

    private final GraalPyEngineOptions options;
    private volatile Engine engine;
    private volatile ContextPool pool;
    private boolean closed;

    /** Creates a factory configured from {@code graalpy.jsr223.*} system properties. */
    public GraalPyScriptEngineFactory() {
        this(GraalPyEngineOptions.fromSystemProperties());
    }

    public GraalPyScriptEngineFactory(GraalPyEngineOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public GraalPyEngineOptions getOptions() {
        return options;
    }

    @Override
    public String getEngineName() {
        return "GraalPy";
    }

    @Override
    public String getEngineVersion() {
        return polyglotEngine().getVersion();
    }

    @Override
    public List<String> getExtensions() {
        return EXTENSIONS;
    }

    @Override
    public List<String> getMimeTypes() {
        return MIME_TYPES;
    }

    @Override
    public List<String> getNames() {
        return NAMES;
    }

    @Override
    public String getLanguageName() {
        return LANGUAGE_ID;
    }

    @Override
    public String getLanguageVersion() {
        return polyglotEngine().getLanguages().get(LANGUAGE_ID).getVersion();
    }

    @Override
    public Object getParameter(String key) {
        return switch (key) {
            case ScriptEngine.ENGINE -> getEngineName();
            case ScriptEngine.ENGINE_VERSION -> getEngineVersion();
            case ScriptEngine.NAME -> NAMES.get(0);
            case ScriptEngine.LANGUAGE -> getLanguageName();
            case ScriptEngine.LANGUAGE_VERSION -> getLanguageVersion();
            case "THREADING" -> "MULTITHREADED";
            default -> null;
        };
    }

    @Override
    public String getMethodCallSyntax(String obj, String method, String... args) {
        return obj + "." + method + "(" + String.join(", ", args) + ")";
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        return "print(" + pythonStringLiteral(toDisplay) + ")";
    }

    @Override
    public String getProgram(String... statements) {
        return String.join("\n", statements) + "\n";
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new GraalPyScriptEngine(this, pool(), options.writeBack());
    }

    /** Closes all pooled contexts and the shared polyglot engine. Engines created earlier stop working. */
    @Override
    public synchronized void close() {
        closed = true;
        if (pool != null) {
            pool.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    ContextPool pool() {
        ContextPool result = pool;
        if (result == null) {
            synchronized (this) {
                result = pool;
                if (result == null) {
                    result = new ContextPool(polyglotEngine(), options);
                    pool = result;
                }
            }
        }
        return result;
    }

    private Engine polyglotEngine() {
        Engine result = engine;
        if (result == null) {
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("GraalPy script engine factory has been closed");
                }
                result = engine;
                if (result == null) {
                    result = createEngine();
                    engine = result;
                }
            }
        }
        return result;
    }

    private Engine createEngine() {
        Engine.Builder builder = Engine.newBuilder(LANGUAGE_ID);
        Map<String, String> polyglotOptions = options.polyglotOptions();
        // Running on a stock JDK falls back to the Truffle interpreter. That is a supported mode, so
        // don't print a warning per engine unless explicitly asked for.
        if (!polyglotOptions.containsKey(WARN_INTERPRETER_ONLY)
                && System.getProperty("polyglot." + WARN_INTERPRETER_ONLY) == null) {
            builder.option(WARN_INTERPRETER_ONLY, "false");
        }
        builder.options(polyglotOptions);
        return builder.build();
    }

    private static String pythonStringLiteral(String text) {
        return text.codePoints()
                .mapToObj(cp -> switch (cp) {
                    case '\\' -> "\\\\";
                    case '"' -> "\\\"";
                    case '\n' -> "\\n";
                    case '\r' -> "\\r";
                    case '\t' -> "\\t";
                    default -> cp < 0x20 ? String.format("\\x%02x", cp) : Character.toString(cp);
                })
                .collect(Collectors.joining("", "\"", "\""));
    }
}
