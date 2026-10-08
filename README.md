# matching

**Tecnología:** Java + Spring Boot

## Responsabilidad

Disponibilidad, cobertura, búsqueda de candidatos, matching y asignación.

## Reglas

- Mantener el ownership definido en DD/SDD.
- No escribir directamente en tablas de otros servicios.
- Publicar/consumir eventos únicamente mediante contratos versionados.
- Mantener aislamiento multi-tenant cuando corresponda.

## Estructura

Cuatro capas, según el SDD (secciones 6.3 y 6.5), como paquetes de un solo módulo Maven:

```text
pom.xml
src/main/java/co/quickpatch/matching/
  MatchingApplication.java
  api/             Controladores REST y validación de entrada
  application/     Casos de uso, comandos y consultas
  domain/          Entidades, value objects y reglas del matching (sin Spring, Kafka ni JPA)
  infrastructure/  Spring Data/JPA con PostGIS, Kafka y adaptadores externos
src/test/java/...  *Tests.java (unitarias, Surefire) y *IT.java (integración, Failsafe)
```

Dependencias permitidas: `api → application → domain`; `infrastructure → application, domain`.

## Desarrollo local

Requiere JDK 25 (`.java-version`). Se usa el wrapper de Maven.

```bash
./mvnw test jacoco:report   # unitarias y cobertura (CI en pull requests)
./mvnw verify               # unitarias, integración y cobertura (CI en push)
./mvnw spring-boot:run
```

El reporte de cobertura queda en `target/site/jacoco/jacoco.xml` (el CI exige ≥ 80%). Como en los servicios .NET, mide dominio, aplicación y API; la infraestructura se cubre con las pruebas de integración (Testcontainers con PostgreSQL y Kafka, requieren Docker).

## Base de datos

- Migraciones con Flyway en `src/main/resources/db/migration/`. Las ejecuta `FLYWAY_USER` (el rol `_migrator` en el pipeline).
- Roles del DD 10.2 en `db/roles.sql`: lo aplica un administrador después de las migraciones. El servicio se conecta como `matching_app` (sin `BYPASSRLS`) y fija `app.current_tenant` en cada transacción.
- Variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `FLYWAY_USER`, `FLYWAY_PASSWORD`.

## Eventos

|Evento|Rol|Efecto|
|---|---|---|
|`service-request.created` v1|Consume (grupo `matching-service`)|Registra el `eventId` en `processed_events` e inicia el matching en la misma transacción, bajo el tenant del evento. Un evento repetido no repite el efecto; un mensaje que no cumple el contrato se registra y se descarta; un error de la base se reintenta cada 2 s sin confirmar el offset.|

La búsqueda de candidatos y las ofertas (`matching_attempts`, RN-M1 a RN-M7) quedan detrás del puerto `MatchingStarter`; hoy `PendingMatchingStarter` solo registra la llegada de la solicitud.

Variables: `KAFKA_BOOTSTRAP_SERVERS`. Los logs salen en JSON (ECS) con `correlationId`, `tenantId` y `eventId`.

## Contenedor

- Imagen: `Dockerfile` en la raíz (multi-stage, usuario sin privilegios).
- Puerto: `8080`.
- Probes para k3s: `GET /health/live` y `GET /health/ready` (grupos de salud de Spring Boot Actuator; `ready` incluye la base de datos).
