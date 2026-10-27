package co.quickpatch.matching.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.quickpatch.matching.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

/** Lectura de la llave pública y validación del token, sin levantar la API (vienen de TechniciansApiTests). */
class SecurityConfigTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID TECNICO = UUID.randomUUID();

    @Test
    void llavePem_SeLeeYUnaInvalidaFalla() {
        assertThat(SecurityConfig.parse(TestTokens.PUBLIC_KEY_PEM).getAlgorithm()).isEqualTo("RSA");
        assertThatThrownBy(() -> SecurityConfig.parse("no es pem"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sinLlaveConfigurada_NingunTokenEsValido() {
        var decoder = new SecurityConfig().jwtDecoder("", "quickpatch-identity", "quickpatch");

        assertThatThrownBy(() -> decoder.decode(TestTokens.create(TECNICO, TENANT, "tecnico")))
                .isInstanceOf(JwtException.class);
    }
}
