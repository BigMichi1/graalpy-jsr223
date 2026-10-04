package de.bigmichi1.graalpy.cibseven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.bigmichi1.graalpy.jsr223.GraalPyScriptEngine;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.script.ScriptEngine;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineConfiguration;
import org.cibseven.bpm.engine.ProcessEngines;
import org.cibseven.bpm.engine.ScriptEvaluationException;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.spin.plugin.impl.SpinProcessEnginePlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CibSevenIntegrationTest {

    private static ProcessEngine processEngine;
    private static Set<String> registeredBefore;

    @BeforeAll
    static void startEngine() {
        // The engine is named, and the registry is held to its snapshot in stopEngine(), so this test
        // neither takes the "default" registration nor leaves an engine registered behind it.
        registeredBefore = Set.copyOf(ProcessEngines.getProcessEngines().keySet());
        final ProcessEngineConfigurationImpl configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setProcessEngineName("graalpy-jsr223-integration-test");
        configuration.setJdbcUrl("jdbc:h2:mem:graalpy-it;DB_CLOSE_DELAY=-1");
        configuration.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);
        configuration.setJobExecutorActivate(false);
        configuration.setHistory(ProcessEngineConfiguration.HISTORY_NONE);
        configuration.setEnforceHistoryTimeToLive(false);
        // Our plugin is registered first on purpose: it must work regardless of plugin order.
        configuration.setProcessEnginePlugins(List.<ProcessEnginePlugin>of(new GraalPyProcessEnginePlugin(), new SpinProcessEnginePlugin()));
        processEngine = configuration.buildProcessEngine();

        processEngine
            .getRepositoryService()
            .createDeployment()
            .addClasspathResource("bpmn/script-task.bpmn")
            .addClasspathResource("bpmn/spin.bpmn")
            .addClasspathResource("bpmn/bpmn-error.bpmn")
            .addClasspathResource("bpmn/failing.bpmn")
            .addClasspathResource("dmn/risk.dmn")
            .deploy();
    }

    @AfterAll
    static void stopEngine() {
        if (processEngine != null) {
            processEngine.close();
        }
        assertThat(ProcessEngines.getProcessEngines().keySet()).isEqualTo(registeredBefore);
    }

    @Test
    void resolvesGraalPyForPythonScripts() {
        final ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();

        final ScriptEngine engine = configuration.getScriptEngineResolver().getScriptEngine("python", true);

        assertThat(engine).isInstanceOf(GraalPyScriptEngine.class);
        // Engine caching only kicks in for factories declaring a THREADING parameter.
        assertThat(configuration.getScriptEngineResolver().getScriptEngine("python", true)).isSameAs(engine);
    }

    @Test
    void runsScriptTasksListenersMappingsAndConditions() {
        final ProcessInstance instance = processEngine
            .getRuntimeService()
            .startProcessInstanceByKey("scriptTask", Variables.createVariables().putValue("a", 4).putValue("b", 3).putValue("name", "cib seven"));

        final VariableMap variables = processEngine.getRuntimeService().getVariablesTyped(instance.getId());
        assertThat(variables)
            .containsEntry("listenerRan", true)
            .containsEntry("sum", 7)
            .containsEntry("doubled", 14)
            .containsEntry("description", "{\"even\": false, \"value\": 7}")
            .containsEntry("greetingOut", "Hello CIB SEVEN!")
            .doesNotContainKeys("local_only", "describe", "json", "greeting");
        assertThat(activeActivity(instance)).isEqualTo("big");
    }

    @Test
    void takesOtherBranchForSmallValues() {
        final ProcessInstance instance = processEngine
            .getRuntimeService()
            .startProcessInstanceByKey("scriptTask", Variables.createVariables().putValue("a", 1).putValue("b", 2).putValue("name", "x"));

        assertThat(activeActivity(instance)).isEqualTo("small");
    }

    @Test
    void providesSpinFunctions() {
        final String payload = "{\"name\": \"Ada\", \"items\": [1, 2, 3]}";
        final ProcessInstance instance = processEngine.getRuntimeService().startProcessInstanceByKey("spin", Map.of("payload", payload));

        assertThat(processEngine.getRuntimeService().getVariables(instance.getId())).containsEntry("customerName", "Ada").containsEntry("itemCount", 3);
    }

    @Test
    void propagatesBpmnErrorsRaisedFromPython() {
        final ProcessInstance rejected = processEngine.getRuntimeService().startProcessInstanceByKey("bpmnError", Map.of("amount", -5));
        final ProcessInstance accepted = processEngine.getRuntimeService().startProcessInstanceByKey("bpmnError", Map.of("amount", 5));

        assertThat(activeActivity(rejected)).isEqualTo("rejectedTask");
        assertThat(activeActivity(accepted)).isEqualTo("accepted");
    }

    @Test
    void reportsPythonErrors() {
        assertThatThrownBy(() -> processEngine.getRuntimeService().startProcessInstanceByKey("failing"))
            .isInstanceOf(ScriptEvaluationException.class)
            .hasMessageContaining("ZeroDivisionError");
    }

    @Test
    void evaluatesPythonExpressionsInDecisionTables() {
        final Object high = processEngine
            .getDecisionService()
            .evaluateDecisionTableByKey("risk", Variables.createVariables().putValue("amount", 250).putValue("quantity", 4))
            .getSingleEntry();
        final Object low = processEngine
            .getDecisionService()
            .evaluateDecisionTableByKey("risk", Variables.createVariables().putValue("amount", 10).putValue("quantity", 4))
            .getSingleEntry();

        assertThat(high).isEqualTo("HIGH");
        assertThat(low).isEqualTo("LOW-10");
    }

    private static String activeActivity(final ProcessInstance instance) {
        return processEngine.getTaskService().createTaskQuery().processInstanceId(instance.getId()).singleResult().getTaskDefinitionKey();
    }
}
