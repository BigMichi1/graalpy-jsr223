package de.bigmichi1.graalpy.jsr223;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.bigmichi1.graalpy.jsr223.GraalPyEngineOptions.HostAccessPolicy;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class GraalPyEngineOptionsTest {

    @Test
    void defaultsTrustScriptsButDenyFilesNativeCodeAndThreads() {
        final GraalPyEngineOptions options = GraalPyEngineOptions.builder().build();

        assertThat(options.hostAccess()).isEqualTo(HostAccessPolicy.ALL);
        assertThat(options.hostClassFilter().test("java.lang.System")).isTrue();
        assertThat(options.allowIO()).isFalse();
        assertThat(options.allowCreateThread()).isFalse();
        assertThat(options.allowNativeAccess()).isFalse();
        assertThat(options.maxIdleContexts()).isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(options.maxEvaluationsPerContext()).isZero();
        assertThat(options.compilationCacheSize()).isEqualTo(256);
        assertThat(options.writeBack()).isTrue();
        assertThat(options.polyglotOptions()).isEmpty();
    }

    @Test
    void readsEveryPropertyFromProperties() {
        final Properties properties = new Properties();
        properties.setProperty("graalpy.jsr223.hostAccess", " explicit ");
        properties.setProperty("graalpy.jsr223.hostClassLookup", "java.util., java.time.");
        properties.setProperty("graalpy.jsr223.allowIO", "true");
        properties.setProperty("graalpy.jsr223.allowCreateThread", "true");
        properties.setProperty("graalpy.jsr223.allowNativeAccess", "true");
        properties.setProperty("graalpy.jsr223.maxIdleContexts", "3");
        properties.setProperty("graalpy.jsr223.maxEvaluationsPerContext", "100");
        properties.setProperty("graalpy.jsr223.compilationCacheSize", "0");
        properties.setProperty("graalpy.jsr223.writeBack", "false");
        properties.setProperty("graalpy.jsr223.option.python.PythonPath", "/opt/scripts");
        properties.setProperty("unrelated.property", "ignored");

        final GraalPyEngineOptions options = GraalPyEngineOptions.fromProperties(properties);

        assertThat(options.hostAccess()).isEqualTo(HostAccessPolicy.EXPLICIT);
        assertThat(options.hostClassFilter().test("java.util.ArrayList")).isTrue();
        assertThat(options.hostClassFilter().test("java.time.Instant")).isTrue();
        assertThat(options.hostClassFilter().test("java.lang.System")).isFalse();
        assertThat(options.allowIO()).isTrue();
        assertThat(options.allowCreateThread()).isTrue();
        assertThat(options.allowNativeAccess()).isTrue();
        assertThat(options.maxIdleContexts()).isEqualTo(3);
        assertThat(options.maxEvaluationsPerContext()).isEqualTo(100);
        assertThat(options.compilationCacheSize()).isZero();
        assertThat(options.writeBack()).isFalse();
        assertThat(options.polyglotOptions()).containsExactly(Map.entry("python.PythonPath", "/opt/scripts"));
    }

    @Test
    void readsSystemProperties() {
        final String key = "graalpy.jsr223.maxIdleContexts";
        final String before = System.getProperty(key);
        System.setProperty(key, "7");
        try {
            assertThat(GraalPyEngineOptions.fromSystemProperties().maxIdleContexts()).isEqualTo(7);
        } finally {
            if (before == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, before);
            }
        }
    }

    @Test
    void rejectsAnUnknownHostAccessPolicy() {
        final Properties properties = new Properties();
        properties.setProperty("graalpy.jsr223.hostAccess", "everything");

        assertThatThrownBy(() -> GraalPyEngineOptions.fromProperties(properties)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostClassLookupSpecs() {
        assertThat(GraalPyEngineOptions.builder().hostClassLookup(" * ").build().hostClassFilter().test("any.Class")).isTrue();
        assertThat(GraalPyEngineOptions.builder().hostClassLookup("").build().hostClassFilter().test("java.lang.String")).isFalse();
        assertThat(GraalPyEngineOptions.builder().hostClassLookup("a.b., ,c.").build().hostClassFilter().test("c.D")).isTrue();
    }

    @Test
    void rejectsInvalidValues() {
        final GraalPyEngineOptions.Builder builder = GraalPyEngineOptions.builder();

        assertThatThrownBy(() -> builder.maxIdleContexts(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.maxEvaluationsPerContext(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.compilationCacheSize(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.hostAccess(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.hostClassFilter(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.polyglotOption(null, "v")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.polyglotOption("k", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void polyglotOptionsAreImmutable() {
        final GraalPyEngineOptions options = GraalPyEngineOptions.builder().polyglotOption("a", "b").build();

        assertThatThrownBy(() -> options.polyglotOptions().put("c", "d")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyHostAccessPolicyMapsToAPolyglotPolicy() {
        for (final HostAccessPolicy policy : HostAccessPolicy.values()) {
            assertThat(policy.hostAccess()).isNotNull();
        }
    }
}
