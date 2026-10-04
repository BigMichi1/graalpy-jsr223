package de.bigmichi1.graalpy.cibseven;

import de.bigmichi1.graalpy.jsr223.ClasspathResources;
import de.bigmichi1.graalpy.jsr223.GraalPyScriptEngineFactory;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.scripting.env.ScriptEnvResolver;

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
 * <p>The changes are applied in {@link #postInit} and again in {@link #postProcessEngineBuild}, so
 * they also catch a Spin plugin registered after this one. Register this plugin after Spin's when
 * the job executor starts with the engine: a Python job run between the two hooks would otherwise
 * still see Spin's Jython environment.
 */
public class GraalPyProcessEnginePlugin extends AbstractProcessEnginePlugin {

    static final Set<String> PYTHON_LANGUAGES = GraalPyScriptEngineFactory.NAMES.stream()
        .map(name -> name.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());

    private static final String SPIN_CLASS = "org.cibseven.spin.Spin";
    private static final String SPIN_ENV_RESOLVER_CLASS = "org.cibseven.spin.plugin.impl.SpinScriptEnvResolver";
    private static final String SPIN_ENV_RESOURCE = "spin_env.py";

    @Override
    public void postInit(final ProcessEngineConfigurationImpl configuration) {
        adaptEnvScriptResolvers(configuration);
    }

    @Override
    public void postProcessEngineBuild(final ProcessEngine processEngine) {
        // Again, for a Spin plugin registered after this one: Spin adds its resolver in its own postInit.
        adaptEnvScriptResolvers((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration());
    }

    private static void adaptEnvScriptResolvers(final ProcessEngineConfigurationImpl configuration) {
        // ScriptingEnvironment keeps a reference to this list and resolves each language once, on its
        // first script, so in-place changes count as long as no Python script has run yet.
        final List<ScriptEnvResolver> resolvers = configuration.getEnvScriptResolvers();
        resolvers.replaceAll(resolver -> isSpinResolver(resolver) ? new NonPythonResolver(resolver) : resolver);
        if (isSpinAvailable() && resolvers.stream().noneMatch(SpinEnvResolver.class::isInstance)) {
            resolvers.add(new SpinEnvResolver(loadSpinEnvScript()));
        }
    }

    private static boolean isSpinResolver(final ScriptEnvResolver resolver) {
        return resolver.getClass().getName().equals(SPIN_ENV_RESOLVER_CLASS);
    }

    private static boolean isSpinAvailable() {
        return isClassPresent(SPIN_CLASS);
    }

    static boolean isClassPresent(final String className) {
        try {
            Class.forName(className, false, GraalPyProcessEnginePlugin.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException e) {
            // A Spin that is present but broken (LinkageError) is not absence: let it fail the boot.
            return false;
        }
    }

    private static String loadSpinEnvScript() {
        return ClasspathResources.readUtf8(GraalPyProcessEnginePlugin.class, SPIN_ENV_RESOURCE);
    }

    private static boolean isPython(final String language) {
        return language != null && PYTHON_LANGUAGES.contains(language.toLowerCase(Locale.ROOT));
    }

    /** Delegates to the wrapped resolver for every language except GraalPy's. */
    record NonPythonResolver(ScriptEnvResolver delegate) implements ScriptEnvResolver {
        @Override
        public String[] resolve(final String language) {
            return isPython(language) ? null : delegate.resolve(language);
        }
    }

    /** Provides the GraalPy Spin environment for GraalPy languages. */
    record SpinEnvResolver(String script) implements ScriptEnvResolver {
        @Override
        public String[] resolve(final String language) {
            return isPython(language) ? new String[] { script } : null;
        }
    }
}
