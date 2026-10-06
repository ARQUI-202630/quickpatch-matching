package co.quickpatch.matching;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class MatchingApplicationTests {

    @Test
    void laAplicacionArrancaDesdeMain() {
        assertThatCode(() -> MatchingApplication.main(new String[] {"--server.port=0"}))
                .doesNotThrowAnyException();
    }
}
