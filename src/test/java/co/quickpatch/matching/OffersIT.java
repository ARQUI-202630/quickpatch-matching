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
 * Respuesta del técnico a la oferta (SCRUM-79, RF-10, RN-M2 a RN-M8) de punta a punta: PostGIS y Kafka reales, el
 * servicio como {@code matching_app} bajo RLS y el vencimiento con {@code matching_scheduler}. Las ofertas vencen a
 * los 6 segundos para probar RN-M8 sin esperar 3 minutos.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OffersIT {

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
        registry.add("quickpatch.matching.offer-ttl", () -> "6s");
        registry.add("quickpatch.matching.expiry-interval", () -> "PT1S");
        registry.add("quickpatch.matching.sweep-interval", () -> "PT1H");
        registry.add("logging.structured.format.console", () -> "");
    }

    @Test
    void aceptar_AsignaYPublicaTechnicianAssignedSegunElContrato() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var tecnico = tecnicoListo(tenant, especialidad, List.of("02"));
        // Otro técnico del mismo tenant, de otra especialidad: se prepara antes para no gastar el plazo de la oferta.
        var otro = tecnicoListo(tenant, UUID.randomUUID(), List.of("05"));
        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());
        var attemptId = esperarEvento("matching.offer-created", solicitud).get("data").get("attemptId").asString();

        // La oferta se ve con la localidad y la distancia, sin la dirección (RN-SR13).
        var lista = JSON.readTree(cliente(tecnico, tenant).get().uri("/v1/matching/offers?status=pendiente")
                .retrieve().body(String.class));
        assertThat(lista.get("totalItems").asInt()).isEqualTo(1);
        var oferta = lista.get("items").get(0);
        assertThat(oferta.get("attemptId").asString()).isEqualTo(attemptId);
        assertThat(oferta.get("locality").get("code").asString()).isEqualTo("02");
        assertThat(oferta.get("locality").get("name").asString()).isEqualTo("Chapinero");
        assertThat(oferta.get("description").asString()).isEqualTo("Fuga de agua debajo del lavaplatos");
        assertThat(oferta.get("distanceMeters").asDouble()).isGreaterThanOrEqualTo(0);
        assertThat(oferta.has("addressText")).isFalse();

        // Otro técnico del mismo tenant no puede aceptarla: 409, igual que una inexistente.
        var ajena = cliente(otro, tenant).post().uri("/v1/matching/offers/" + attemptId + "/accept").retrieve()
                .toEntity(String.class);
        assertThat(ajena.getStatusCode().value()).isEqualTo(409);
        assertThat(JSON.readTree(ajena.getBody()).get("type").asString())
                .isEqualTo("https://quickpatch.internal/problems/oferta-no-disponible");

        var correlacion = UUID.randomUUID();
        var respuesta = cliente(tecnico, tenant).post().uri("/v1/matching/offers/" + attemptId + "/accept")
                .header("X-Correlation-Id", correlacion.toString()).retrieve().toEntity(String.class);
        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(respuesta.getBody()).get("response").asString()).isEqualTo("aceptado");

        var evento = esperarEvento("matching.technician-assigned", solicitud);
        assertThat(evento.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(evento.get("producer").asString()).isEqualTo("matching-service");
        assertThat(evento.get("tenantId").asString()).isEqualTo(tenant.toString());
        assertThat(evento.get("correlationId").asString()).isEqualTo(correlacion.toString());
        var data = evento.get("data");
        assertThat(data.get("technicianId").asString()).isEqualTo(tecnico.toString());
        assertThat(data.get("attemptId").asString()).isEqualTo(attemptId);
        assertThat(data.get("clientId").asString()).isNotBlank();
        assertThat(OffsetDateTime.parse(data.get("assignedAt").asString())).isNotNull();
        assertThat(data.size()).isEqualTo(5);

        assertThat(admin("SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'"))
                .isEqualTo("ocupado");
        assertThat(admin("SELECT status FROM matching_requests WHERE service_request_id = '" + solicitud + "'"))
                .isEqualTo("asignado");
        assertThat(admin("SELECT response FROM matching_attempts WHERE id = '" + attemptId + "'")).isEqualTo("aceptado");

        // Repetir la aceptación ya no aplica: la oferta fue respondida.
        assertThat(cliente(tecnico, tenant).post().uri("/v1/matching/offers/" + attemptId + "/accept").retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(409);
        // Ocupado, el técnico no puede pasar a no_disponible (RN-M6).
        assertThat(cliente(tecnico, tenant).put().uri("/v1/technicians/me/availability")
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"no_disponible\"}").retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void rechazar_OfreceLaSolicitudAlSiguienteCandidato() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var cercano = tecnicoListo(tenant, especialidad, List.of("02"));
        var lejano = tecnicoListo(tenant, especialidad, List.of("02", "05", "11"));
        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());
        var primera = esperarEvento("matching.offer-created", solicitud).get("data");
        assertThat(primera.get("technicianId").asString()).isEqualTo(cercano.toString());

        var respuesta = cliente(cercano, tenant).post()
                .uri("/v1/matching/offers/" + primera.get("attemptId").asString() + "/reject").retrieve()
                .toEntity(String.class);
        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(respuesta.getBody()).get("response").asString()).isEqualTo("rechazado");

        var segunda = esperarEvento("matching.offer-created", solicitud,
                n -> n.get("data").get("technicianId").asString().equals(lejano.toString())).get("data");
        assertThat(segunda.get("attemptId").asString()).isNotEqualTo(primera.get("attemptId").asString());
        // Sin penalización: quien rechazó sigue disponible y sin ofertas.
        assertThat(admin("SELECT status FROM technician_availability WHERE technician_id = '" + cercano + "'"))
                .isEqualTo("disponible");
        assertThat(JSON.readTree(cliente(cercano, tenant).get().uri("/v1/matching/offers").retrieve()
                .body(String.class)).get("totalItems").asInt()).isZero();
    }

    @Test
    void sinRespuesta_LaOfertaVenceYLaSolicitudQuedaEnEspera() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var tecnico = tecnicoListo(tenant, especialidad, List.of("02"));
        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());
        var attemptId = esperarEvento("matching.offer-created", solicitud).get("data").get("attemptId").asString();

        // RN-M8: nadie responde; vence y, sin más candidatos, la solicitud pasa a en espera (RN-M3, RN-M4).
        var evento = esperarEvento("matching.no-technician-available", solicitud);
        assertThat(evento.get("data").get("attemptsCount").asInt()).isEqualTo(1);
        assertThat(admin("SELECT response FROM matching_attempts WHERE id = '" + attemptId + "'")).isEqualTo("expirado");
        assertThat(admin("SELECT status FROM matching_requests WHERE service_request_id = '" + solicitud + "'"))
                .isEqualTo("en_espera");

        var tarde = cliente(tecnico, tenant).post().uri("/v1/matching/offers/" + attemptId + "/accept").retrieve()
                .toEntity(String.class);
        assertThat(tarde.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void cancelar_ConOfertaPendiente_LaOfertaSeCancelaYNoSeOfreceANadieMas() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var tecnico = tecnicoListo(tenant, especialidad, List.of("02"));
        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());
        var attemptId = esperarEvento("matching.offer-created", solicitud).get("data").get("attemptId").asString();

        // RN-M5: la oferta pendiente pasa a cancelado y la búsqueda termina.
        cancelar(tenant, solicitud, null);
        await().atMost(Duration.ofSeconds(30)).until(() -> "cancelado".equals(
                admin("SELECT status FROM matching_requests WHERE service_request_id = '" + solicitud + "'")));
        assertThat(admin("SELECT response FROM matching_attempts WHERE id = '" + attemptId + "'")).isEqualTo("cancelado");
        assertThat(JSON.readTree(cliente(tecnico, tenant).get().uri("/v1/matching/offers").retrieve()
                .body(String.class)).get("totalItems").asInt()).isZero();
        assertThat(cliente(tecnico, tenant).post().uri("/v1/matching/offers/" + attemptId + "/accept").retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(409);
        assertThat(admin("SELECT count(*) FROM matching_attempts WHERE service_request_id = '" + solicitud + "'"))
                .isEqualTo("1");
        assertThat(admin("SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'"))
                .isEqualTo("disponible");
    }

    @Test
    void cancelar_ConTecnicoAsignado_ElTecnicoVuelveADisponible() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var tecnico = tecnicoListo(tenant, especialidad, List.of("02"));
        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());
        var attemptId = esperarEvento("matching.offer-created", solicitud).get("data").get("attemptId").asString();
        assertThat(cliente(tecnico, tenant).post().uri("/v1/matching/offers/" + attemptId + "/accept").retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        assertThat(admin("SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'"))
                .isEqualTo("ocupado");

        // RN-M6: al cancelar, el técnico asignado queda libre; la oferta aceptada no cambia.
        cancelar(tenant, solicitud, tecnico);
        await().atMost(Duration.ofSeconds(30)).until(() -> "disponible".equals(
                admin("SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'")));
        assertThat(admin("SELECT status FROM matching_requests WHERE service_request_id = '" + solicitud + "'"))
                .isEqualTo("cancelado");
        assertThat(admin("SELECT response FROM matching_attempts WHERE id = '" + attemptId + "'")).isEqualTo("aceptado");
    }

    @Test
    void completar_ElTecnicoVuelveADisponible() throws Exception {
        var tenant = UUID.randomUUID();
        var especialidad = UUID.randomUUID();
        var tecnico = tecnicoListo(tenant, especialidad, List.of("02"));
        var solicitud = UUID.randomUUID();
        crearSolicitud(tenant, solicitud, especialidad, LAT_CHAPINERO, LON_CHAPINERO, UUID.randomUUID());
        var attemptId = esperarEvento("matching.offer-created", solicitud).get("data").get("attemptId").asString();
        assertThat(cliente(tecnico, tenant).post().uri("/v1/matching/offers/" + attemptId + "/accept").retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        assertThat(admin("SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'"))
                .isEqualTo("ocupado");

        // RN-M6: al completar el servicio, el técnico queda libre; la búsqueda sigue en asignado.
        publicar("service-request.completed", solicitud.toString(), """
                {"eventId":"%s","eventType":"service-request.completed","eventVersion":1,
                 "occurredAt":"2026-10-20T17:10:00Z","correlationId":"%s","tenantId":"%s",
                 "producer":"service-request-service",
                 "data":{"serviceRequestId":"%s","clientId":"%s","companyId":null,"technicianId":"%s","quoteId":"%s",
                         "totalAmount":"185000.00","completedAt":"2026-10-20T17:10:00Z"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), tenant, solicitud, UUID.randomUUID(), tecnico,
                UUID.randomUUID()));
        await().atMost(Duration.ofSeconds(30)).until(() -> "disponible".equals(
                admin("SELECT status FROM technician_availability WHERE technician_id = '" + tecnico + "'")));
        assertThat(admin("SELECT status FROM matching_requests WHERE service_request_id = '" + solicitud + "'"))
                .isEqualTo("asignado");
    }

    private static void cancelar(UUID tenant, UUID solicitud, UUID tecnico) throws Exception {
        publicar("service-request.cancelled", solicitud.toString(), """
                {"eventId":"%s","eventType":"service-request.cancelled","eventVersion":1,
                 "occurredAt":"2026-10-13T11:00:00Z","correlationId":"%s","tenantId":"%s",
                 "producer":"service-request-service",
                 "data":{"serviceRequestId":"%s","clientId":"%s","technicianId":%s,"cancelledByType":"cliente",
                         "reason":"Ya no necesito el servicio.","cancelledAt":"2026-10-13T11:00:00Z"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), tenant, solicitud, UUID.randomUUID(),
                tecnico == null ? "null" : "\"" + tecnico + "\""));
    }

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

    private static JsonNode esperarEvento(String topic, UUID solicitud) {
        return esperarEvento(topic, solicitud, nodo -> true);
    }

    /** Espera en el topic el evento de la solicitud (la clave de los eventos de Matching) que cumpla {@code filtro}. */
    private static JsonNode esperarEvento(String topic, UUID solicitud, java.util.function.Predicate<JsonNode> filtro) {
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
                        if (filtro.test(nodo)) {
                            return nodo;
                        }
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
