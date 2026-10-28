package co.quickpatch.matching.api.security;

import co.quickpatch.matching.api.http.Problems;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Valida el JWT RS256 que emite Identity (ADR-018) con su llave pública: firma, emisor, audiencia y vigencia con
 * 30 s de tolerancia, igual que los servicios .NET. El rol sale del claim {@code role}. Sin llave configurada
 * ningún token es válido (falla cerrado). {@code /v1/localities} y los probes no requieren token. Solo aplica con
 * servidor web: los contextos sin web (consumidores de Kafka en pruebas) no la cargan.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    public static final String TECNICO = "tecnico";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("role");
        roles.setAuthorityPrefix("ROLE_");
        var authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(roles);
        authentication.setPrincipalClaimName(JwtClaimNames.SUB);

        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/health/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/localities").permitAll()
                        .requestMatchers("/v1/technicians/me/**").hasRole(TECNICO)
                        .requestMatchers("/v1/matching/offers", "/v1/matching/offers/**").hasRole(TECNICO)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))
                        .authenticationEntryPoint((request, response, e) -> Problems.write(json, request, response,
                                HttpStatus.UNAUTHORIZED, Problems.UNAUTHENTICATED, "Falta el token o no es válido")))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) -> Problems.write(json, request, response,
                                HttpStatus.UNAUTHORIZED, Problems.UNAUTHENTICATED, "Falta el token o no es válido"))
                        .accessDeniedHandler((request, response, e) -> Problems.write(json, request, response,
                                HttpStatus.FORBIDDEN, Problems.FORBIDDEN, "El rol del token no puede hacer esta operación")));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${quickpatch.jwt.public-key-pem:}") String publicKeyPem,
            @Value("${quickpatch.jwt.issuer}") String issuer, @Value("${quickpatch.jwt.audience}") String audience) {
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            return token -> {
                throw new BadJwtException("Matching no tiene la llave pública de Identity configurada.");
            };
        }
        var decoder = NimbusJwtDecoder.withPublicKey(parse(publicKeyPem)).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience))));
        return decoder;
    }

    /** Llave pública RSA en PEM ({@code BEGIN PUBLIC KEY}, SubjectPublicKeyInfo). */
    static RSAPublicKey parse(String pem) {
        try {
            var base64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
            var spec = new X509EncodedKeySpec(Base64.getDecoder().decode(base64));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("quickpatch.jwt.public-key-pem no es una llave pública RSA en PEM.", e);
        }
    }
}
