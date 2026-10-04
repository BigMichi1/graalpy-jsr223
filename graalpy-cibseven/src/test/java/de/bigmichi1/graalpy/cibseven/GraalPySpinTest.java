package de.bigmichi1.graalpy.cibseven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.cibseven.spin.DataFormats;
import org.cibseven.spin.json.SpinJsonNode;
import org.junit.jupiter.api.Test;

class GraalPySpinTest {

    private static final String JSON = "{\"name\": \"Ada\"}";

    @Test
    void parsesWithTheDefaultFormat() {
        assertThat(((SpinJsonNode) GraalPySpin.S.call(JSON)).prop("name").stringValue()).isEqualTo("Ada");
        assertThat(((SpinJsonNode) GraalPySpin.JSON.call(JSON)).prop("name").stringValue()).isEqualTo("Ada");
    }

    @Test
    void parsesWithANamedOrGivenFormat() {
        assertThat(((SpinJsonNode) GraalPySpin.S.call(JSON, "application/json")).prop("name").stringValue()).isEqualTo("Ada");
        assertThat(((SpinJsonNode) GraalPySpin.S.call(JSON, DataFormats.json())).prop("name").stringValue()).isEqualTo("Ada");
    }

    @Test
    void rejectsWrongArguments() {
        assertThatThrownBy(() -> GraalPySpin.S.call())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1 or 2");
        assertThatThrownBy(() -> GraalPySpin.S.call(JSON, 42))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("data format");
        assertThatThrownBy(() -> GraalPySpin.JSON.call(JSON, JSON))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JSON()");
        assertThatThrownBy(() -> GraalPySpin.XML.call())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("XML()");
    }
}
