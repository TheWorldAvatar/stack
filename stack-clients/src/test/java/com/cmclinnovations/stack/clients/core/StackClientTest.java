package com.cmclinnovations.stack.clients.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class StackClientTest {

    @ParameterizedTest
    @CsvSource({
            "simple-model",
            "financial-model",
            "fault-propagation-model"
    })
    void testKubernetesServiceHostName(String serviceName) {
        try (MockedStatic<StackClient> stackClient = Mockito.mockStatic(StackClient.class,
                Mockito.CALLS_REAL_METHODS)) {
            stackClient.when(StackClient::isRunningInKubernetes).thenReturn(true);

            Assertions.assertEquals(serviceName, StackClient.getServiceHostName(serviceName));
            stackClient.verify(() -> StackClient.prependStackName(serviceName), Mockito.never());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "central, simple-model, central-simple-model",
            "central, financial-model, central-financial-model",
            "central, fault-propagation-model, central-fault-propagation-model",
            "central_stack, simple-model, central-stack-simple-model",
            "central, custom_model, central-custom-model"
    })
    void testDockerServiceHostName(String stackName, String serviceName, String expected) {
        try (MockedStatic<StackClient> stackClient = Mockito.mockStatic(StackClient.class,
                Mockito.CALLS_REAL_METHODS)) {
            stackClient.when(StackClient::isRunningInKubernetes).thenReturn(false);
            stackClient.when(() -> StackClient.prependStackName(serviceName))
                    .thenReturn(stackName + "_" + serviceName);

            Assertions.assertEquals(expected, StackClient.getServiceHostName(serviceName));
        }
    }
}
