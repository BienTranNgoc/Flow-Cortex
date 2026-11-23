package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowcortex.connector.api.HttpConnector;
import com.flowcortex.workflow.application.OutboundTargets.Target;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class OutboundTargetsTest {

    private static final Map<String, List<String>> FIXTURE = Map.of("fixture.send", List.of("url"));

    private static StepDefinition step(int order, String type, String config) {
        return new StepDefinition(order, null, type, config, null);
    }

    @Test
    void aStringDestinationIsListedVerbatim() {
        List<Target> targets = OutboundTargets.targetsOf(
                List.of(step(3, "fixture.send", "{\"url\":\"https://hooks.example.com/x\"}")), FIXTURE);

        assertThat(targets).containsExactly(new Target(3, "fixture.send", "url", "https://hooks.example.com/x"));
    }

    @Test
    void aNonStringDestinationIsListedAsJsonText() {
        List<Target> targets = OutboundTargets.targetsOf(
                List.of(step(1, "fixture.send", "{\"url\":{\"host\":\"a.example\",\"port\":8080}}")), FIXTURE);

        assertThat(targets).containsExactly(
                new Target(1, "fixture.send", "url", "{\"host\":\"a.example\",\"port\":8080}"));
    }

    @Test
    void aStepWithoutTheFieldIsNotListed() {
        assertThat(OutboundTargets.targetsOf(List.of(step(1, "fixture.send", "{\"other\":1}"),
                step(2, "fixture.send", null), step(3, "fixture.send", "{\"url\":null}")), FIXTURE)).isEmpty();
    }

    @Test
    void aTypeOutsideTheTableIsNotListed() {
        assertThat(OutboundTargets.targetsOf(List.of(step(1, "echo", "{\"url\":\"https://x.example\"}")), FIXTURE))
                .isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theProductionTableNamesOnlyRegisteredTypes() {
        ConnectorCallStepExecutor connectorCall = new ConnectorCallStepExecutor(mock(HttpConnector.class),
                mock(WorkflowRunRepository.class), DubFixtures.dub(), mock(ObjectProvider.class));
        StepExecutorRegistry registry = new StepExecutorRegistry(List.of(new EchoStepExecutor(), connectorCall));

        assertThat(registry.knownTypes()).containsAll(OutboundTargets.TABLE.keySet());
    }

    @Test
    void aConnectorCallStepNamesItsUrlAsTheDestinationInTheProductionTable() {
        List<Target> targets = OutboundTargets.targetsOf(List.of(step(2, "connector.call",
                "{\"connector\":\"crm\",\"method\":\"POST\",\"url\":\"https://api.crm.example/v1/export\"}")),
                OutboundTargets.TABLE);

        assertThat(targets).containsExactly(
                new Target(2, "connector.call", "url", "https://api.crm.example/v1/export"));
    }
}
