package de.bigmichi1.graalpy.jsr223;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClasspathResourcesTest {

    @Test
    void readsAShippedResource() {
        assertThat(ClasspathResources.readUtf8(ContextPool.class, "jsr223_support.py")).contains("def _make_api");
    }

    @Test
    void reportsAMissingResource() {
        assertThatThrownBy(() -> ClasspathResources.readUtf8(ContextPool.class, "missing.py"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing.py");
    }
}
