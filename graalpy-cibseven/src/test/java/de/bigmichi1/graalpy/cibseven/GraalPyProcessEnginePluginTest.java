package de.bigmichi1.graalpy.cibseven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.impl.scripting.env.ScriptEnvResolver;
import org.cibseven.spin.plugin.impl.SpinScriptEnvResolver;
import org.junit.jupiter.api.Test;

/** The resolver rewiring, without building an engine. */
class GraalPyProcessEnginePluginTest {

    @Test
    void keepsSpinForOtherLanguagesAndReplacesItForPython() {
        final ProcessEngineConfigurationImpl configuration = configurationWith(new SpinScriptEnvResolver());

        new GraalPyProcessEnginePlugin().postInit(configuration);

        final List<ScriptEnvResolver> resolvers = configuration.getEnvScriptResolvers();
        assertThat(resolvers).hasSize(2);
        assertThat(scripts(resolvers, "javascript")).singleElement().asString().contains("Spin");
        assertThat(scripts(resolvers, "groovy")).hasSize(1);
        for (final String python : List.of("python", "Python", "graalpy", "python3", "py")) {
            assertThat(scripts(resolvers, python)).singleElement().asString().contains("GraalPySpin").doesNotContain("import org.cibseven");
        }
    }

    @Test
    void isIdempotentAcrossBothHooks() {
        final ProcessEngineConfigurationImpl configuration = configurationWith(new SpinScriptEnvResolver());
        final GraalPyProcessEnginePlugin plugin = new GraalPyProcessEnginePlugin();

        plugin.postInit(configuration);
        plugin.postInit(configuration);

        assertThat(configuration.getEnvScriptResolvers()).hasSize(2);
        assertThat(scripts(configuration.getEnvScriptResolvers(), "python")).hasSize(1);
    }

    @Test
    void leavesForeignResolversAlone() {
        final ScriptEnvResolver own = language -> new String[] { "own = 1" };
        final ProcessEngineConfigurationImpl configuration = configurationWith(own);

        new GraalPyProcessEnginePlugin().postInit(configuration);

        assertThat(configuration.getEnvScriptResolvers()).first().isSameAs(own);
        assertThat(scripts(configuration.getEnvScriptResolvers(), "python")).contains("own = 1");
    }

    @Test
    void graalPyResolverIgnoresUnnamedLanguages() {
        assertThat(new GraalPyProcessEnginePlugin.SpinEnvResolver("S = 1").resolve(null)).isNull();
    }

    @Test
    void detectsWhetherAClassIsPresent() {
        assertThat(GraalPyProcessEnginePlugin.isClassPresent("org.cibseven.spin.Spin")).isTrue();
        assertThat(GraalPyProcessEnginePlugin.isClassPresent("org.example.NotThere")).isFalse();
    }

    private static ProcessEngineConfigurationImpl configurationWith(final ScriptEnvResolver resolver) {
        final ProcessEngineConfigurationImpl configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setEnvScriptResolvers(new ArrayList<>(List.of(resolver)));
        return configuration;
    }

    private static List<String> scripts(final List<ScriptEnvResolver> resolvers, final String language) {
        final List<String> scripts = new ArrayList<>();
        for (final ScriptEnvResolver resolver : resolvers) {
            final String[] resolved = resolver.resolve(language);
            if (resolved != null) {
                scripts.addAll(List.of(resolved));
            }
        }
        return scripts;
    }
}
