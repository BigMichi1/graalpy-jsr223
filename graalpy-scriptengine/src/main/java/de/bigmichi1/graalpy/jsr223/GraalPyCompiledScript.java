package de.bigmichi1.graalpy.jsr223;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/**
 * A syntax-checked script.
 *
 * <p>Holds only the source text: the bytecode is cached inside each pooled context on first use.
 * Instances are immutable and may be cached and evaluated concurrently, which is what CIB seven does
 * with compiled scripts of a process definition.
 */
final class GraalPyCompiledScript extends CompiledScript {

    private final GraalPyScriptEngine engine;
    private final String source;

    GraalPyCompiledScript(final GraalPyScriptEngine engine, final String source) {
        this.engine = engine;
        this.source = source;
    }

    @Override
    public Object eval(final ScriptContext context) throws ScriptException {
        return engine.evaluate(source, context);
    }

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
