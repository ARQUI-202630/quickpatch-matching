package co.quickpatch.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL y Kafka reales. Las migraciones corren como administrador, después se aplican los roles del
 * DD 10.2 ({@code db/roles.sql}) y el servicio se conecta como {@code matching_app}, sin BYPASSRLS.
 * Los mensajes siguen el contrato {@code service-request.created} v1, igual que los publica ServiceRequest (.NET).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceRequestCreatedConsumerIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    private static final String TOPIC = "service-request.created";

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
        registry.add("logging.structured.format.console", () -> "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/health/live", "/health/ready"})
    void elProbeRespondeOk(String ruta) {
        var respuesta = RestClient.create("http://localhost:" + port).get().uri(ruta).retrieve().toBodilessEntity();

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void eventoDelContrato_SeRegistraUnaVezAunqueLlegueRepetido() throws Exception {
        var tenant = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var mensaje = evento(eventId, tenant, "Fuga de agua debajo del lavaplatos");

        publicar(eventId.toString(), mensaje);
        publicar(eventId.toString(), mensaje);
        var marcador = UUID.randomUUID();
        publicar(marcador.toString(), evento(marcador, tenant, "Marcador para saber que ya se consumió todo"));

        await().atMost(Duration.ofSeconds(30)).until(() -> contar(marcador) == 1);
        assertThat(contar(eventId)).isEqualTo(1);
        assertThat(admin("SELECT tenant_id::text FROM processed_events WHERE event_id = '" + eventId + "'"))
                .isEqualTo(tenant.toString());
    }

    @Test
    void mensajeQueNoCumpleElContrato_SeDescartaYElConsumidorSigue() throws Exception {
        var tenant = UUID.randomUUID();
        publicar("x", "esto no es json");
        var sinDescripcion = UUID.randomUUID();
        publicar(sinDescripcion.toString(), evento(sinDescripcion, tenant, "corta"));
        var valido = UUID.randomUUID();
        publicar(valido.toString(), evento(valido, tenant, "Cambio de chapa de la puerta principal"));

        await().atMost(Duration.ofSeconds(30)).until(() -> contar(valido) == 1);
        assertThat(contar(sinDescripcion)).isZero();
    }

    @Test
    void rls_ElRolDelServicioSoloVeLosEventosDeSuTenant() throws Exception {
        var tenantA = UUID.randomUUID();
        var eventoA = UUID.randomUUID();
        publicar(eventoA.toString(), evento(eventoA, tenantA, "Instalación de lámpara en la sala"));
        await().atMost(Duration.ofSeconds(30)).until(() -> contar(eventoA) == 1);

        try (var conexion = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "matching_app", "app-pruebas")) {
            conexion.setAutoCommit(false);
            var sinTenant = conexion.createStatement().executeQuery("SELECT count(*) FROM processed_events");
            sinTenant.next();
            assertThat(sinTenant.getLong(1)).isZero();

            conexion.createStatement().execute("SELECT set_config('app.current_tenant', '" + UUID.randomUUID() + "', true)");
            var otroTenant = conexion.createStatement().executeQuery(
                    "SELECT count(*) FROM processed_events WHERE event_id = '" + eventoA + "'");
            otroTenant.next();
            assertThat(otroTenant.getLong(1)).isZero();
            conexion.rollback();
        }
    }

    private static String evento(UUID eventId, UUID tenant, String descripcion) {
        return """
                {"eventId":"%s","eventType":"service-request.created","eventVersion":1,
                 "occurredAt":"2026-10-06T15:00:00Z","correlationId":"%s","tenantId":"%s",
                 "producer":"service-request-service",
                 "data":{"serviceRequestId":"%s","clientId":"%s","categoryId":"%s","description":"%s",
                         "location":{"latitude":4.6533,"longitude":-74.0836},"createdAt":"2026-10-06T15:00:00Z"}}
                """.formatted(eventId, UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), descripcion);
    }

    private static void publicar(String key, String value) throws Exception {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(TOPIC, key, value)).get();
        }
    }

    private static long contar(UUID eventId) throws Exception {
        return Long.parseLong(admin("SELECT count(*)::text FROM processed_events WHERE event_id = '" + eventId + "'"));
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
