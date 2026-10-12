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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proyección del técnico con PostgreSQL y Kafka reales (DD 5.6, P-01, ADR-017). Los mensajes siguen los contratos
 * {@code technician.registered} y {@code technician.verification-changed} v1, igual que los publica Identity.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TechnicianEventsConsumerIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

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

    @Test
    void registroYSuspension_ActualizanLaProyeccionSinTocarLaDisponibilidad() throws Exception {
        var tenant = UUID.randomUUID();
        var tecnico = UUID.randomUUID();
        var especialidad = UUID.randomUUID();

        publicar("technician.registered", tecnico, registro(tenant, tecnico, especialidad, "2026-10-12T09:00:00Z"));
        await().atMost(Duration.ofSeconds(30)).until(() -> estado(tecnico) != null);
        assertThat(estado(tecnico)).isEqualTo("pendiente");
        assertThat(admin("SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'"))
                .isEqualTo("no_disponible");
        assertThat(admin("SELECT tenant_id::text || '/' || specialty_id::text FROM technician_availability "
                + "WHERE technician_id = '" + tecnico + "'")).isEqualTo(tenant + "/" + especialidad);

        publicar("technician.verification-changed", tecnico,
                cambio(UUID.randomUUID(), tenant, tecnico, especialidad, "pendiente", "aprobado", "2026-10-12T10:00:00Z"));
        publicar("technician.verification-changed", tecnico,
                cambio(UUID.randomUUID(), tenant, tecnico, especialidad, "aprobado", "suspendido", "2026-10-12T11:00:00Z"));

        await().atMost(Duration.ofSeconds(30)).until(() -> "suspendido".equals(estado(tecnico)));
        assertThat(admin("SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'"))
                .isEqualTo("no_disponible");
    }

    @Test
    void cambioQueLlegaAntesQueElRegistro_GanaPorVersion() throws Exception {
        var tenant = UUID.randomUUID();
        var tecnico = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var cambio = UUID.randomUUID();

        publicar("technician.verification-changed", tecnico,
                cambio(cambio, tenant, tecnico, especialidad, "pendiente", "aprobado", "2026-10-12T10:00:00Z"));
        await().atMost(Duration.ofSeconds(30)).until(() -> "aprobado".equals(estado(tecnico)));
        var registro = UUID.randomUUID();
        publicar("technician.registered", tecnico, registro(registro, tenant, tecnico, especialidad, "2026-10-12T09:00:00Z"));

        await().atMost(Duration.ofSeconds(30)).until(() -> procesado(registro));
        assertThat(estado(tecnico)).isEqualTo("aprobado");
    }

    @Test
    void rls_ElRolDelServicioNoVeTecnicosDeOtroTenant() throws Exception {
        var tenant = UUID.randomUUID();
        var tecnico = UUID.randomUUID();
        publicar("technician.registered", tecnico, registro(tenant, tecnico, UUID.randomUUID(), "2026-10-12T09:00:00Z"));
        await().atMost(Duration.ofSeconds(30)).until(() -> estado(tecnico) != null);

        try (var conexion = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "matching_app", "app-pruebas")) {
            conexion.setAutoCommit(false);
            conexion.createStatement().execute("SELECT set_config('app.current_tenant', '" + UUID.randomUUID() + "', true)");
            var otro = conexion.createStatement().executeQuery(
                    "SELECT count(*) FROM technician_availability WHERE technician_id = '" + tecnico + "'");
            otro.next();
            assertThat(otro.getLong(1)).isZero();
            conexion.rollback();
        }
    }

    private static String registro(UUID tenant, UUID tecnico, UUID especialidad, String updatedAt) {
        return registro(UUID.randomUUID(), tenant, tecnico, especialidad, updatedAt);
    }

    private static String registro(UUID eventId, UUID tenant, UUID tecnico, UUID especialidad, String updatedAt) {
        return """
                {"eventId":"%s","eventType":"technician.registered","eventVersion":1,
                 "occurredAt":"%s","correlationId":"%s","tenantId":"%s","producer":"identity-service",
                 "data":{"technicianId":"%s","specialtyId":"%s","providerId":null,
                         "verificationStatus":"pendiente","updatedAt":"%s"}}
                """.formatted(eventId, updatedAt, UUID.randomUUID(), tenant, tecnico, especialidad, updatedAt);
    }

    private static String cambio(UUID eventId, UUID tenant, UUID tecnico, UUID especialidad, String anterior,
            String nuevo, String updatedAt) {
        return """
                {"eventId":"%s","eventType":"technician.verification-changed","eventVersion":1,
                 "occurredAt":"%s","correlationId":"%s","tenantId":"%s","producer":"identity-service",
                 "data":{"technicianId":"%s","specialtyId":"%s","previousStatus":"%s",
                         "verificationStatus":"%s","reason":null,"updatedAt":"%s"}}
                """.formatted(eventId, updatedAt, UUID.randomUUID(), tenant, tecnico, especialidad, anterior, nuevo,
                updatedAt);
    }

    private static void publicar(String topic, UUID key, String value) throws Exception {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(topic, key.toString(), value)).get();
        }
    }

    private static String estado(UUID tecnico) throws Exception {
        return admin("SELECT verification_status FROM technician_availability WHERE technician_id = '" + tecnico + "'");
    }

    private static boolean procesado(UUID eventId) throws Exception {
        return "1".equals(admin("SELECT count(*)::text FROM processed_events WHERE event_id = '" + eventId + "'"));
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
