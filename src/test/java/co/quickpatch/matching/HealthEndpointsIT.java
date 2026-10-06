package co.quickpatch.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointsIT {

    @LocalServerPort
    private int port;

    @ParameterizedTest
    @ValueSource(strings = {"/health/live", "/health/ready"})
    void elProbeRespondeOk(String ruta) {
        var respuesta = RestClient.create("http://localhost:" + port)
                .get().uri(ruta).retrieve().toBodilessEntity();

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
    }
}
