package de.bigmichi1.graalpy.jsr223;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;

/**
 * JSR-223 script engine running Python 3 on GraalPy.
 *
 * <h2>Evaluation model</h2>
 * <ul>
 *   <li>Every evaluation runs in a fresh Python module namespace on a context borrowed from the
 *       factory's pool, so evaluations do not share Python globals and the engine is safe to use
 *       from several threads at once.</li>
 *   <li>Only the identifiers a script actually reads are looked up in the {@link ScriptContext}
 *       (engine scope first, then global scope). Large or lazily loading bindings such as a process
 *       engine's variable scope are never enumerated.</li>
 *   <li>If the last statement of a script is an expression, its value is the result of
 *       {@code eval}; otherwise the result is {@code null}.</li>
 *   <li>Top-level variables the script assigns are written to the engine scope bindings with
 *       {@link Bindings#put}, unless disabled with
 *       {@link GraalPyEngineOptions.Builder#writeBack(boolean)}. Skipped are names starting with
 *       {@code _}, unchanged bindings, and values without a Java representation (modules,
 *       functions, classes, instances of Python classes); see {@link ValueConverter}.</li>
 *   <li>Python output goes to the context's writer and error writer; input reads from its
 *       reader.</li>
 * </ul>
 *
 * <p>Instances are cheap: all heavy state (polyglot engine, contexts, compiled code) lives in the
 * {@link GraalPyScriptEngineFactory}.
 */
public final class GraalPyScriptEngine extends AbstractScriptEngine implements Compilable {

    private static final String DEFAULT_FILENAME = "<script>";

    private final GraalPyScriptEngineFactory factory;
    private final ContextPool pool;
    private final boolean writeBack;

    GraalPyScriptEngine(GraalPyScriptEngineFactory factory, ContextPool pool, boolean writeBack) {
        this.factory = factory;
        this.pool = pool;
        this.writeBack = writeBack;
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        return evaluate(script, context);
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        return evaluate(read(reader), context);
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }

    @Override
    public CompiledScript compile(String script) throws ScriptException {
        // Syntax errors must surface here. The compiled code itself is cached per pooled context,
        // so the CompiledScript only keeps the source and can be shared across threads.
        PooledContext pooled = pool.borrow();
        boolean broken = false;
        try {
            pooled.prepare(script, DEFAULT_FILENAME);
        } catch (PolyglotException e) {
            broken = isFatal(e);
            throw toScriptException(e, DEFAULT_FILENAME);
        } finally {
            pool.release(pooled, broken);
        }
        return new GraalPyCompiledScript(this, script);
    }

    @Override
    public CompiledScript compile(Reader script) throws ScriptException {
        return compile(read(script));
    }

    Object evaluate(String script, ScriptContext scriptContext) throws ScriptException {
        String filename = filename(scriptContext);
        PooledContext pooled = pool.borrow();
        boolean broken = false;
        pooled.attachStreams(scriptContext.getWriter(), scriptContext.getErrorWriter(), scriptContext.getReader());
        try {
            Value prepared = pooled.prepare(script, filename);

            Value names = prepared.getMember("names");
            List<Object> keys = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (long i = 0; i < names.getArraySize(); i++) {
                String name = names.getArrayElement(i).asString();
                int scope = scriptContext.getAttributesScope(name);
                if (scope != -1) {
                    keys.add(name);
                    values.add(scriptContext.getAttribute(name, scope));
                }
            }

            Value outcome = pooled.execute(prepared, keys.toArray(), values.toArray(), writeBack);

            Value changes = outcome.getArrayElement(2);
            for (long i = 0; i + 1 < changes.getArraySize(); i += 2) {
                Object value = ValueConverter.toJava(changes.getArrayElement(i + 1));
                // Guest objects without a Java representation belong to the pooled context and must
                // not outlive this evaluation in the caller's bindings.
                if (!(value instanceof Value)) {
                    scriptContext.setAttribute(changes.getArrayElement(i).asString(), value, ScriptContext.ENGINE_SCOPE);
                }
            }
            return outcome.getArrayElement(0).asBoolean() ? ValueConverter.toJava(outcome.getArrayElement(1)) : null;
        } catch (PolyglotException e) {
            broken = isFatal(e);
            throw toScriptException(e, filename);
        } finally {
            pool.release(pooled, broken);
        }
    }

    private static boolean isFatal(PolyglotException e) {
        return e.isCancelled() || e.isExit() || e.isInternalError() || e.isResourceExhausted() || e.isInterrupted();
    }

    private static ScriptException toScriptException(PolyglotException e, String filename) {
        if (e.isHostException()) {
            // Keep Java exceptions thrown by called host code as the cause, so callers can react to
            // them (CIB seven looks for a BpmnError in the cause chain).
            ScriptException exception = new ScriptException(e.asHostException().toString());
            exception.initCause(e.asHostException());
            return exception;
        }
        int line = lineNumber(e, filename);
        ScriptException exception = new ScriptException(e.getMessage(), filename, line);
        exception.initCause(e);
        return exception;
    }

    private static int lineNumber(PolyglotException e, String filename) {
        Value guest = e.getGuestObject();
        if (guest != null && e.isGuestException() && guest.hasMember("lineno")) {
            // SyntaxError raised by compile() carries the position of the offending token.
            Value lineno = guest.getMember("lineno");
            if (lineno != null && lineno.fitsInInt()) {
                return lineno.asInt();
            }
        }
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            SourceSection location = frame.getSourceLocation();
            if (frame.isGuestFrame() && location != null && location.getSource().getName().equals(filename)) {
                return location.getStartLine();
            }
        }
        return -1;
    }

    private static String filename(ScriptContext context) {
        Object filename = context.getAttribute(ScriptEngine.FILENAME);
        return filename != null ? filename.toString() : DEFAULT_FILENAME;
    }

    private static String read(Reader reader) throws ScriptException {
        try {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, n);
            }
            return builder.toString();
        } catch (IOException e) {
            throw (ScriptException) new ScriptException("Failed to read script: " + e.getMessage()).initCause(e);
        }
    }
}
