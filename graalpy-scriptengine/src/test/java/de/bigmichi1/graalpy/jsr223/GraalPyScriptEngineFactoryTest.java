package de.bigmichi1.graalpy.jsr223;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.junit.jupiter.api.Test;

class GraalPyScriptEngineFactoryTest {

    @Test
    void describesItself() {
        try (GraalPyScriptEngineFactory factory = new GraalPyScriptEngineFactory()) {
            assertThat(factory.getOptions()).isNotNull();
            assertThat(factory.getEngineName()).isEqualTo("GraalPy");
            assertThat(factory.getEngineVersion()).isNotBlank();
            assertThat(factory.getLanguageName()).isEqualTo("python");
            assertThat(factory.getExtensions()).containsExactly("py");
            assertThat(factory.getMimeTypes()).contains("text/x-python");
            assertThat(factory.getNames()).contains("python", "graalpy");
            assertThat(factory.getParameter(ScriptEngine.ENGINE)).isEqualTo("GraalPy");
            assertThat(factory.getParameter(ScriptEngine.ENGINE_VERSION)).isEqualTo(factory.getEngineVersion());
            assertThat(factory.getParameter(ScriptEngine.NAME)).isEqualTo("python");
            assertThat(factory.getParameter(ScriptEngine.LANGUAGE)).isEqualTo("python");
            assertThat(factory.getParameter(ScriptEngine.LANGUAGE_VERSION)).isEqualTo(factory.getLanguageVersion());
            assertThat(factory.getParameter("THREADING")).isEqualTo("MULTITHREADED");
            assertThat(factory.getParameter("unknown")).isNull();
        }
    }

    @Test
    void generatesRunnableProgramsAndOutputStatements() throws ScriptException {
        try (GraalPyScriptEngineFactory factory = new GraalPyScriptEngineFactory()) {
            final String text = "back\\slash \"quoted\"\ttab\r\nline\u0001end €";
            final String program = factory.getProgram(factory.getOutputStatement(text), "x = 1");
            final StringWriter out = new StringWriter();
            final ScriptEngine engine = factory.getScriptEngine();
            engine.getContext().setWriter(out);

            engine.eval(program);

            assertThat(out.toString()).isEqualTo(text + "\n");
            assertThat(program).endsWith("x = 1\n");
        }
    }

    @Test
    void refusesWorkOnceClosed() {
        final GraalPyScriptEngineFactory factory = new GraalPyScriptEngineFactory();
        final ScriptEngine engine = factory.getScriptEngine();
        factory.close();

        assertThatThrownBy(() -> engine.eval("1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");
    }

    @Test
    void refusesToStartOnceClosed() {
        final GraalPyScriptEngineFactory factory = new GraalPyScriptEngineFactory();
        factory.close();

        assertThatThrownBy(factory::getScriptEngine).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        factory.close();
    }

    @Test
    void silencesTheInterpreterOnlyWarningUnlessConfigured() {
        final String key = "polyglot.engine.WarnInterpreterOnly";
        final String before = System.getProperty(key);
        System.clearProperty(key);
        try (GraalPyScriptEngineFactory factory = new GraalPyScriptEngineFactory()) {
            assertThat(factory.getEngineVersion()).isNotBlank();
        } finally {
            if (before != null) {
                System.setProperty(key, before);
            }
        }
        try (GraalPyScriptEngineFactory explicit = new GraalPyScriptEngineFactory(GraalPyEngineOptions.builder().polyglotOption("engine.WarnInterpreterOnly", "false").build())) {
            assertThat(explicit.getEngineVersion()).isNotBlank();
        }
    }
}
