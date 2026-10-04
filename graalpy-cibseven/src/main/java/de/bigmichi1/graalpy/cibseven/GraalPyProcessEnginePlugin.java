package de.bigmichi1.graalpy.cibseven;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.scripting.env.ScriptEnvResolver;

import de.bigmichi1.graalpy.jsr223.GraalPyScriptEngineFactory;

/**
 * Makes CIB seven's scripting environment GraalPy compatible.
 *
 * <p>The script engine itself needs no plugin: it is found through the JDK service loader as soon as
 * the jar is on the classpath, for {@code scriptFormat="python"} (or {@code graalpy}). This plugin
 * only fixes environment scripts, which CIB seven runs before every script of a language:
 *
 * <ul>
 *   <li>Spin registers a Jython specific environment script for {@code python}. It is suppressed for
 *       all GraalPy language names.</li>
 *   <li>If Spin is on the classpath, a GraalPy environment script providing {@code S}, {@code JSON}
 *       and {@code XML} is registered instead (see {@link GraalPySpin}).</li>
 * </ul>
 *
 * <p>The changes are applied in {@link #postProcessEngineBuild(ProcessEngine)}, after every plugin
 * has registered its resolvers, so plugin order does not matter.
 */
public class GraalPyProcessEnginePlugin extends AbstractProcessEnginePlugin {

    static final Set<String> PYTHON_LANGUAGES = GraalPyScriptEngineFactory.NAMES.stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private static final String SPIN_CLASS = "org.cibseven.spin.Spin";
    private static final String SPIN_ENV_RESOLVER_CLASS = "org.cibseven.spin.plugin.impl.SpinScriptEnvResolver";
    private static final String SPIN_ENV_RESOURCE = "spin_env.py";

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
        ProcessEngineConfigurationImpl configuration =
                (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        // ScriptingEnvironment keeps a reference to this list and resolves lazily, so in-place
        // changes take effect as long as no script has run yet.
        List<ScriptEnvResolver> resolvers = configuration.getEnvScriptResolvers();
        resolvers.replaceAll(resolver -> isSpinResolver(resolver) ? new NonPythonResolver(resolver) : resolver);
        if (isSpinAvailable() && resolvers.stream().noneMatch(SpinEnvResolver.class::isInstance)) {
            resolvers.add(new SpinEnvResolver(loadSpinEnvScript()));
        }
    }

    private static boolean isSpinResolver(ScriptEnvResolver resolver) {
        return resolver.getClass().getName().equals(SPIN_ENV_RESOLVER_CLASS);
    }

    private static boolean isSpinAvailable() {
        try {
            Class.forName(SPIN_CLASS, false, GraalPyProcessEnginePlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static String loadSpinEnvScript() {
        try (InputStream in = GraalPyProcessEnginePlugin.class.getResourceAsStream(SPIN_ENV_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + SPIN_ENV_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isPython(String language) {
        return language != null && PYTHON_LANGUAGES.contains(language.toLowerCase(Locale.ROOT));
    }

    /** Delegates to the wrapped resolver for every language except GraalPy's. */
    record NonPythonResolver(ScriptEnvResolver delegate) implements ScriptEnvResolver {
        @Override
        public String[] resolve(String language) {
            return isPython(language) ? null : delegate.resolve(language);
        }
    }

    /** Provides the GraalPy Spin environment for GraalPy languages. */
    record SpinEnvResolver(String script) implements ScriptEnvResolver {
        @Override
        public String[] resolve(String language) {
            return isPython(language) ? new String[] {script} : null;
        }
    }
}
