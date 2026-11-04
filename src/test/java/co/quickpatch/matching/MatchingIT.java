package co.quickpatch.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Algoritmo de matching de punta a punta (SCRUM-29, RN-M1 a RN-M10): PostGIS y Kafka reales, el servicio como
 * {@code matching_app} bajo RLS, el publicador del Outbox con {@code matching_outbox} y el barrido con
 * {@code matching_scheduler}. Los técnicos llegan por {@code technician.*} y configuran su disponibilidad y su
 * cobertura por la API, igual que en producción; las solicitudes llegan por {@code service-request.created}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MatchingIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    /** Parque de la 93, en Chapinero (02). */
    private static final double LAT_CHAPINERO = 4.6766;
    private static final double LON_CHAPINERO = -74.0482;

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        registry.add("quickpatch.outbox.publisher-role", () -> "matching_outbox");
        registry.add("quickpatch.matching.scheduler-role", () -> "matching_scheduler");
        registry.add("quickpatch.matching.sweep-interval", () -> "PT2S");
        registry.add("logging.structured.format.console", () -> "");
    }

    @Test
    void tecnicoElegible_RecibeLaOfertaYSePublicaConElSobreDelContrato() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var tecnico = tecnicoListo(tenant, especialidad, List.of("02"));
        var lejano = tecnicoListo(tenant, especialidad, List.of("02", "05", "11"));

        var solicitud = UUID.randomUUID();
        var correlacion = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, correlacion);

        var evento = esperarEvento("matching.offer-created", solicitud);
        assertThat(evento.get("eventType").asString()).isEqualTo("matching.offer-created");
        assertThat(evento.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(evento.get("producer").asString()).isEqualTo("matching-service");
        assertThat(evento.get("tenantId").asString()).isEqualTo(tenant.toString());
        assertThat(evento.get("correlationId").asString()).isEqualTo(correlacion.toString());
        var data = evento.get("data");
        assertThat(data.get("technicianId").asString()).isEqualTo(tecnico.toString());
        assertThat(data.size()).isEqualTo(4);

        assertThat(admin("SELECT id::text FROM matching_attempts WHERE service_request_id = '" + solicitud
                + "' AND response = 'pendiente'")).isEqualTo(data.get("attemptId").asString());
        // RN-M8: vence 3 minutos después de ofrecida.
        assertThat(admin("SELECT (expires_at - offered_at = interval '3 minutes')::text FROM matching_attempts "
                + "WHERE service_request_id = '" + solicitud + "'")).isEqualTo("true");
        assertThat(OffsetDateTime.parse(data.get("expiresAt").asString())).isAfter(OffsetDateTime.now());
        assertThat(admin("SELECT (distance_m IS NOT NULL)::text FROM matching_attempts WHERE service_request_id = '"
                + solicitud + "'")).isEqualTo("true");
        assertThat(admin("SELECT status FROM matching_requests WHERE service_request_id = '" + solicitud + "'"))
                .isEqualTo("buscando");
        assertThat(admin("SELECT count(*)::text FROM matching_attempts WHERE technician_id = '" + lejano + "'"))
                .isEqualTo("0");
    }

    @Test
    void tecnicosDeOtroTenantOSuspendidos_NuncaRecibenLaSolicitud() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var deOtroTenant = tecnicoListo(UUID.randomUUID(), especialidad, List.of("02"));
        var suspendido = tecnicoListo(tenant, especialidad, List.of("02"));
        verificacion(tenant, suspendido, especialidad, "aprobado", "suspendido", 3);
        await().atMost(Duration.ofSeconds(30)).until(() -> "suspendido".equals(admin(
                "SELECT verification_status FROM technician_availability WHERE technician_id = '" + suspendido + "'")));

        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());

        var evento = esperarEvento("matching.no-technician-available", solicitud);
        assertThat(evento.get("data").get("attemptsCount").asInt()).isZero();
        assertThat(evento.get("data").get("clientId").asString()).isNotBlank();
        assertThat(admin("SELECT status FROM matching_requests WHERE service_request_id = '" + solicitud + "'"))
                .isEqualTo("en_espera");
        assertThat(admin("SELECT count(*)::text FROM matching_attempts WHERE technician_id IN ('" + deOtroTenant
                + "', '" + suspendido + "')")).isEqualTo("0");
    }

    @Test
    void solicitudEnEspera_SeReanudaCuandoUnTecnicoEsAprobado() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());
        esperarEvento("matching.no-technician-available", solicitud);

        // Pendiente de verificación: configura todo, pero todavía no es candidato.
        var tecnico = UUID.randomUUID();
        registrar(tenant, tecnico, especialidad);
        configurar(tenant, tecnico, List.of("02"));
        assertThat(admin("SELECT status FROM matching_requests WHERE service_request_id = '" + solicitud + "'"))
                .isEqualTo("en_espera");

        verificacion(tenant, tecnico, especialidad, "pendiente", "aprobado", 1);

        var reanudada = esperarEvento("matching.search-resumed", solicitud);
        assertThat(reanudada.get("data").get("trigger").asString()).isEqualTo("aprobacion");
        var oferta = esperarEvento("matching.offer-created", solicitud);
        assertThat(oferta.get("data").get("technicianId").asString()).isEqualTo(tecnico.toString());
    }

    @Test
    void solicitudEnEspera_SeReanudaCuandoElTecnicoCambiaSuCobertura() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var tecnico = tecnicoListo(tenant, especialidad, List.of("11"));
        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());
        esperarEvento("matching.no-technician-available", solicitud);

        var correlacion = UUID.randomUUID();
        var respuesta = cliente(tecnico, tenant).put().uri("/v1/technicians/me/coverage")
                .header("X-Correlation-Id", correlacion.toString()).contentType(MediaType.APPLICATION_JSON)
                .body("{\"localityCodes\":[\"11\",\"02\"]}").retrieve().toBodilessEntity();
        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);

        var reanudada = esperarEvento("matching.search-resumed", solicitud);
        assertThat(reanudada.get("data").get("trigger").asString()).isEqualTo("cobertura");
        assertThat(reanudada.get("correlationId").asString()).isEqualTo(correlacion.toString());
        esperarEvento("matching.offer-created", solicitud);
    }

    @Test
    void barrido_ReanudaLaSolicitudQueNingunDisparadorAlcanzo() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());
        esperarEvento("matching.no-technician-available", solicitud);

        // Un técnico que se vuelve candidato sin pasar por la API ni por los eventos (por ejemplo, una corrección
        // manual de los datos): solo el barrido lo encuentra.
        var tecnico = UUID.randomUUID();
        admin("INSERT INTO technician_availability VALUES ('" + tecnico + "', '" + tenant + "', '" + especialidad
                + "', 'aprobado', 'disponible', now(), now())");
        admin("INSERT INTO coverage_zones SELECT gen_random_uuid(), '" + tecnico + "', '" + tenant
                + "', code, area, now() FROM localities WHERE code = '02'");

        var reanudada = esperarEvento("matching.search-resumed", solicitud);
        assertThat(reanudada.get("data").get("trigger").asString()).isEqualTo("barrido");
        var oferta = esperarEvento("matching.offer-created", solicitud);
        assertThat(oferta.get("data").get("technicianId").asString()).isEqualTo(tecnico.toString());
    }

    @Test
    void rls_ElRolDelServicioNoVeSolicitudesNiOfertasNiEventosAjenos() throws Exception {
        var tenant = UUID.randomUUID();
        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, UUID.randomUUID(), LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(30)).until(() -> admin(
                "SELECT status FROM matching_requests WHERE service_request_id = '" + solicitud + "'") != null);

        try (var conexion = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "matching_app", "app-pruebas")) {
            conexion.setAutoCommit(false);
            conexion.createStatement().execute("SELECT set_config('app.current_tenant', '" + UUID.randomUUID() + "', true)");
            for (var tabla : List.of("matching_requests", "matching_attempts")) {
                var filas = conexion.createStatement().executeQuery(
                        "SELECT count(*) FROM " + tabla + " WHERE service_request_id = '" + solicitud + "'");
                filas.next();
                assertThat(filas.getLong(1)).as(tabla).isZero();
            }
            conexion.rollback();

            // El Outbox solo admite insertar; leerlo exige el rol del publicador.
            conexion.createStatement().execute("SELECT set_config('app.current_tenant', '" + tenant + "', true)");
            assertThatThrownBy(() -> conexion.createStatement().executeQuery("SELECT count(*) FROM outbox_events"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            conexion.rollback();
        }
    }

    // --- Técnicos -------------------------------------------------------------------------------------------

    /** Técnico registrado, aprobado, disponible y con cobertura en las localidades indicadas. */
    private UUID tecnicoListo(UUID tenant, UUID especialidad, List<String> localidades) throws Exception {
        var tecnico = UUID.randomUUID();
        registrar(tenant, tecnico, especialidad);
        verificacion(tenant, tecnico, especialidad, "pendiente", "aprobado", 1);
        await().atMost(Duration.ofSeconds(30)).until(() -> "aprobado".equals(admin(
                "SELECT verification_status FROM technician_availability WHERE technician_id = '" + tecnico + "'")));
        configurar(tenant, tecnico, localidades);
        return tecnico;
    }

    private void configurar(UUID tenant, UUID tecnico, List<String> localidades) {
        var cliente = cliente(tecnico, tenant);
        var codigos = String.join("\",\"", localidades);
        assertThat(cliente.put().uri("/v1/technicians/me/coverage").contentType(MediaType.APPLICATION_JSON)
                .body("{\"localityCodes\":[\"" + codigos + "\"]}").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(200);
        assertThat(cliente.put().uri("/v1/technicians/me/availability").contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":\"disponible\"}").retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(200);
    }

    private static void registrar(UUID tenant, UUID tecnico, UUID especialidad) throws Exception {
        publicar("technician.registered", tecnico.toString(), """
                {"eventId":"%s","eventType":"technician.registered","eventVersion":1,
                 "occurredAt":"2026-10-13T09:00:00Z","correlationId":"%s","tenantId":"%s","producer":"identity-service",
                 "data":{"technicianId":"%s","specialtyId":"%s","providerId":null,
                         "verificationStatus":"pendiente","updatedAt":"2026-10-13T09:00:00Z"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), tenant, tecnico, especialidad));
        await().atMost(Duration.ofSeconds(30)).until(() -> admin(
                "SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'") != null);
    }

    private static void verificacion(UUID tenant, UUID tecnico, UUID especialidad, String anterior, String nuevo,
            int horas) throws Exception {
        publicar("technician.verification-changed", tecnico.toString(), """
                {"eventId":"%s","eventType":"technician.verification-changed","eventVersion":1,
                 "occurredAt":"2026-10-13T09:00:00Z","correlationId":"%s","tenantId":"%s","producer":"identity-service",
                 "data":{"technicianId":"%s","specialtyId":"%s","previousStatus":"%s","verificationStatus":"%s",
                         "reason":null,"updatedAt":"2026-10-13T%02d:00:00Z"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), tenant, tecnico, especialidad, anterior, nuevo,
                9 + horas));
    }

    private RestClient cliente(UUID tecnico, UUID tenant) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TestTokens.create(tecnico, tenant, "tecnico"))
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    // --- Solicitudes y eventos -----------------------------------------------------------------------------

    private static void crearSolicitud(UUID tenant, UUID solicitud, UUID categoria, double lat, double lon,
            UUID correlacion) throws Exception {
        publicar("service-request.created", solicitud.toString(), """
                {"eventId":"%s","eventType":"service-request.created","eventVersion":1,
                 "occurredAt":"2026-10-13T10:00:00Z","correlationId":"%s","tenantId":"%s",
                 "producer":"service-request-service",
                 "data":{"serviceRequestId":"%s","clientId":"%s","categoryId":"%s",
                         "description":"Fuga de agua debajo del lavaplatos",
                         "location":{"latitude":%s,"longitude":%s},"createdAt":"2026-10-13T10:00:00Z"}}
                """.formatted(UUID.randomUUID(), correlacion, tenant, solicitud, UUID.randomUUID(), categoria, lat, lon));
    }

    /** Espera en el topic el evento cuya clave es la solicitud (la clave de los cuatro eventos de Matching). */
    private static JsonNode esperarEvento(String topic, UUID solicitud) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "pruebas-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(topic));
            var limite = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < limite) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (solicitud.toString().equals(record.key())) {
                        var nodo = JSON.readTree(record.value());
                        assertThat(nodo.get("data").get("serviceRequestId").asString()).isEqualTo(solicitud.toString());
                        assertThat(new String(record.headers().lastHeader("eventId").value()))
                                .isEqualTo(nodo.get("eventId").asString());
                        return nodo;
                    }
                }
            }
        }
        throw new AssertionError("No llegó " + topic + " para la solicitud " + solicitud);
    }

    private static void publicar(String topic, String key, String value) throws Exception {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
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
