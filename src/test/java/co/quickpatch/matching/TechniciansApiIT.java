package co.quickpatch.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Disponibilidad y cobertura de punta a punta (RF-13, DD 8.3.6): PostGIS y Kafka reales, la migración con las
 * 20 localidades, el servicio como {@code matching_app} bajo RLS y tokens RS256 como los de Identity. El técnico
 * llega a Matching por {@code technician.registered}, igual que en producción.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TechniciansApiIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @LocalServerPort
    private int port;

    @BeforeAll
    static void prepararBase() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        admin(Files.readString(Path.of("db", "roles.sql")));
        admin("ALTER ROLE matching_app LOGIN PASSWORD 'app-pruebas'");
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "matching_app");
        registry.add("spring.datasource.password", () -> "app-pruebas");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("quickpatch.jwt.public-key-pem", () -> TestTokens.PUBLIC_KEY_PEM);
        registry.add("logging.structured.format.console", () -> "");
    }

    private RestClient cliente(UUID tecnico, UUID tenant) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TestTokens.create(tecnico, tenant, "tecnico"))
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    @Test
    void localidades_SonLas20DeBogotaYSusPoligonosUbicanPuntosConocidos() throws Exception {
        var respuesta = RestClient.create("http://localhost:" + port).get().uri("/v1/localities")
                .retrieve().toEntity(Map[].class);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        assertThat(respuesta.getBody()).hasSize(20);
        assertThat(respuesta.getBody()[1]).containsEntry("code", "02").containsEntry("name", "Chapinero");
        assertThat(respuesta.getBody()[0]).doesNotContainKey("area");
        // Plaza de Bolívar (La Candelaria) y Parque de la 93 (Chapinero), con las coordenadas en WGS 84.
        assertThat(admin("SELECT code FROM localities WHERE ST_Contains(area, ST_SetSRID(ST_MakePoint(-74.0760, 4.5981), 4326))"))
                .isEqualTo("17");
        assertThat(admin("SELECT code FROM localities WHERE ST_Contains(area, ST_SetSRID(ST_MakePoint(-74.0482, 4.6766), 4326))"))
                .isEqualTo("02");
        assertThat(admin("SELECT count(*)::text FROM localities WHERE NOT ST_IsValid(area)")).isEqualTo("0");
    }

    @Test
    void tecnicoRegistrado_CambiaDisponibilidadYCobertura() throws Exception {
        var tenant = UUID.randomUUID();
        var tecnico = UUID.randomUUID();
        var cliente = cliente(tecnico, tenant);

        var antes = cliente.get().uri("/v1/technicians/me/availability").retrieve().toEntity(String.class);
        assertThat(antes.getStatusCode().value()).isEqualTo(404);

        registrar(tenant, tecnico);
        await().atMost(Duration.ofSeconds(30)).until(() -> cliente.get().uri("/v1/technicians/me/availability")
                .retrieve().toBodilessEntity().getStatusCode().value() == 200);

        var disponible = cliente.put().uri("/v1/technicians/me/availability").contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":\"disponible\"}").retrieve().toEntity(Map.class);
        assertThat(disponible.getStatusCode().value()).isEqualTo(200);
        assertThat(disponible.getBody()).containsEntry("status", "disponible");

        var cobertura = cliente.put().uri("/v1/technicians/me/coverage").contentType(MediaType.APPLICATION_JSON)
                .body("{\"localityCodes\":[\"13\",\"02\"]}").retrieve().toEntity(Map.class);
        assertThat(cobertura.getStatusCode().value()).isEqualTo(200);
        assertThat(cobertura.getBody().get("localities").toString()).contains("Chapinero", "Teusaquillo");
        assertThat(admin("SELECT count(*)::text FROM coverage_zones z JOIN localities l ON l.code = z.locality_code "
                + "WHERE z.technician_id = '" + tecnico + "' AND z.tenant_id = '" + tenant + "' AND ST_Equals(z.area, l.area)"))
                .isEqualTo("2");

        var reemplazo = cliente.put().uri("/v1/technicians/me/coverage").contentType(MediaType.APPLICATION_JSON)
                .body("{\"localityCodes\":[\"11\"]}").retrieve().toEntity(Map.class);
        assertThat(reemplazo.getBody().get("localities").toString()).contains("Suba").doesNotContain("Chapinero");

        var invalida = cliente.put().uri("/v1/technicians/me/coverage").contentType(MediaType.APPLICATION_JSON)
                .body("{\"localityCodes\":[\"99\"]}").retrieve().toEntity(String.class);
        assertThat(invalida.getStatusCode().value()).isEqualTo(400);
        assertThat(admin("SELECT count(*)::text FROM coverage_zones WHERE technician_id = '" + tecnico + "'")).isEqualTo("1");
    }

    @Test
    void tecnicoOcupado_409() throws Exception {
        var tenant = UUID.randomUUID();
        var tecnico = UUID.randomUUID();
        registrar(tenant, tecnico);
        await().atMost(Duration.ofSeconds(30)).until(() -> admin(
                "SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'") != null);
        admin("UPDATE technician_availability SET status = 'ocupado' WHERE technician_id = '" + tecnico + "'");

        var respuesta = cliente(tecnico, tenant).put().uri("/v1/technicians/me/availability")
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"no_disponible\"}")
                .retrieve().toEntity(String.class);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(409);
        assertThat(respuesta.getBody()).contains("transicion-invalida");
    }

    @Test
    void tokenDeOtroTenant_NoVeAlTecnico() throws Exception {
        var tenant = UUID.randomUUID();
        var tecnico = UUID.randomUUID();
        registrar(tenant, tecnico);
        await().atMost(Duration.ofSeconds(30)).until(() -> admin(
                "SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'") != null);

        var ajeno = cliente(tecnico, UUID.randomUUID());

        assertThat(ajeno.get().uri("/v1/technicians/me/availability").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(404);
        assertThat(ajeno.put().uri("/v1/technicians/me/coverage").contentType(MediaType.APPLICATION_JSON)
                .body("{\"localityCodes\":[\"02\"]}").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(404);
        assertThat(admin("SELECT count(*)::text FROM coverage_zones WHERE technician_id = '" + tecnico + "'")).isEqualTo("0");
    }

    @Test
    void rls_HabilitadoYForzadoEnCadaTablaConTenant() throws Exception {
        // DD 10.1: toda tabla con tenant_id tiene RLS habilitado y forzado.
        assertThat(admin("""
                SELECT coalesce(string_agg(c.relname, ', ' ORDER BY c.relname), '')
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
                WHERE c.relkind = 'r'
                  AND EXISTS (SELECT 1 FROM pg_attribute a
                              WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped)
                  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
                """)).isEmpty();
    }

    @Test
    void rls_ElRolDelServicioNoVeZonasDeOtroTenant() throws Exception {
        var tenant = UUID.randomUUID();
        var tecnico = UUID.randomUUID();
        registrar(tenant, tecnico);
        await().atMost(Duration.ofSeconds(30)).until(() -> admin(
                "SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'") != null);
        cliente(tecnico, tenant).put().uri("/v1/technicians/me/coverage").contentType(MediaType.APPLICATION_JSON)
                .body("{\"localityCodes\":[\"08\"]}").retrieve().toBodilessEntity();

        try (var conexion = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "matching_app", "app-pruebas")) {
            conexion.setAutoCommit(false);
            conexion.createStatement().execute("SELECT set_config('app.current_tenant', '" + UUID.randomUUID() + "', true)");
            var otro = conexion.createStatement().executeQuery(
                    "SELECT count(*) FROM coverage_zones WHERE technician_id = '" + tecnico + "'");
            otro.next();
            assertThat(otro.getLong(1)).isZero();
            conexion.rollback();
        }
    }

    private static void registrar(UUID tenant, UUID tecnico) throws Exception {
        var evento = """
                {"eventId":"%s","eventType":"technician.registered","eventVersion":1,
                 "occurredAt":"2026-10-12T09:00:00Z","correlationId":"%s","tenantId":"%s","producer":"identity-service",
                 "data":{"technicianId":"%s","specialtyId":"%s","providerId":null,
                         "verificationStatus":"pendiente","updatedAt":"2026-10-12T09:00:00Z"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), tenant, tecnico, UUID.randomUUID());
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>("technician.registered", tecnico.toString(), evento)).get();
        }
    }

    /** Conexión de administrador (dueño de las tablas, no sujeto a RLS en esta base de pruebas). */
    private static String admin(String sql) throws Exception {
        try (var conexion = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sentencia = conexion.createStatement()) {
            if (!sentencia.execute(sql)) {
                return null;
            }
            var resultado = sentencia.getResultSet();
            return resultado.next() ? resultado.getString(1) : null;
        }
    }
}
