package co.quickpatch.matching;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/** Tokens RS256 como los que emite Identity (ADR-018), firmados con una llave de pruebas. */
public final class TestTokens {

    private static final KeyPair KEYS = generate();

    public static final String PUBLIC_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----\n";

    private TestTokens() {
    }

    public static String create(UUID userId, UUID tenantId, String role) {
        return sign(userId.toString(), tenantId.toString(), role, "quickpatch-identity", Instant.now().plusSeconds(3600));
    }

    public static String sign(String subject, String tenantId, String role, String issuer, Instant expiresAt) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .audience("quickpatch")
                    .claim("tenant_id", tenantId)
                    .claim("role", role)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiresAt))
                    .build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
