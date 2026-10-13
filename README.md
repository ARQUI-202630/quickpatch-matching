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

El reporte de cobertura queda en `target/site/jacoco/jacoco.xml` (el CI exige ≥ 80%). Como en los servicios .NET, mide dominio, aplicación y API; la infraestructura se cubre con las pruebas de integración (Testcontainers con PostGIS `postgis/postgis:16-3.4`, la misma imagen de producción, y Kafka; requieren Docker).

## Endpoints

Contrato `matching.v1.yaml` 1.0.0 (`contracts/api-gateway`, v0.5.0).

|Endpoint|Quién|Qué hace|
|---|---|---|
|`GET /v1/localities`|Público|Las 20 localidades de Bogotá ordenadas por código, sin polígono (DD 5.18).|
|`GET /v1/technicians/me/availability`|`tecnico`|Disponibilidad actual; `404` si Matching aún no procesó su `technician.registered`.|
|`PUT /v1/technicians/me/availability`|`tecnico`|Cambia entre `disponible` y `no_disponible`; `409 transicion-invalida` si está `ocupado` (RN-M6). Repetir el estado no es error.|
|`GET /v1/technicians/me/coverage`|`tecnico`|Localidades que cubre; lista vacía si no ha elegido.|
|`PUT /v1/technicians/me/coverage`|`tecnico`|Reemplaza la cobertura en una transacción y copia el polígono de cada localidad (DD 5.7). Lista vacía, repetidos o códigos que no existen: `400 validacion`.|

El técnico y el tenant salen de los claims `sub` y `tenant_id` del JWT RS256 de Identity (ADR-018), que Matching valida con la llave pública (`JWT_PUBLIC_KEY_PEM`), el emisor (`JWT_ISSUER`) y la audiencia (`JWT_AUDIENCE`). Sin llave, todo endpoint con token responde `401`. Los errores son `application/problem+json` con `correlationId` (`X-Correlation-Id`).

Pasar a `disponible` o cambiar la cobertura llama al puerto `SearchResumer` (RN-M10); hoy `PendingSearchResumer` solo lo registra, hasta que exista el reintento de solicitudes en espera (SCRUM-76).

## Base de datos

- Migraciones con Flyway en `src/main/resources/db/migration/`. Las ejecuta `FLYWAY_USER` (el rol `_migrator` en el pipeline).
- `localities` trae las 20 localidades de la capa oficial "Localidad" de la Secretaría Distrital de Planeación (Mapa de Referencia IDECA, EPSG 4326), generalizadas a unos 22 m (migración `V3`). Requiere PostGIS, que ya existe en `db_matching`.
- Roles del DD 10.2 en `db/roles.sql`: lo aplica un administrador después de las migraciones. El servicio se conecta como `matching_app` (sin `BYPASSRLS`) y fija `app.current_tenant` en cada transacción.
- Variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `FLYWAY_USER`, `FLYWAY_PASSWORD`.

## Eventos

|Evento|Rol|Efecto|
|---|---|---|
|`service-request.created` v1|Consume (grupo `matching-service`)|Registra el `eventId` en `processed_events` e inicia el matching en la misma transacción, bajo el tenant del evento. Un evento repetido no repite el efecto; un mensaje que no cumple el contrato se registra y se descarta; un error de la base se reintenta cada 2 s sin confirmar el offset.|
|`technician.registered` v1|Consume (grupo `matching-service`)|Crea la fila del técnico en `technician_availability` (DD 5.6, P-01) en `no_disponible`, con su especialidad y su estado de verificación.|
|`technician.verification-changed` v1|Consume (grupo `matching-service`)|Copia el estado de verificación nuevo (aprobado, rechazado, suspendido) sin tocar la disponibilidad; si el registro aún no llegó, crea la fila. Solo los `aprobado` serán candidatos (RN-TP1, RN-TP2).|

Los eventos `technician.*` se aplican una vez por `eventId` y por versión: `projection_updated_at` guarda el `data.updatedAt` del último aplicado y un evento más viejo se descarta (ADR-017), así que el orden de llegada entre los dos topics no deja la copia atrás.

La búsqueda de candidatos y las ofertas (`matching_attempts`, RN-M1 a RN-M7) quedan detrás del puerto `MatchingStarter`; hoy `PendingMatchingStarter` solo registra la llegada de la solicitud.

Variables: `KAFKA_BOOTSTRAP_SERVERS`. Los logs salen en JSON (ECS) con `correlationId`, `tenantId` y `eventId`.

## Contenedor

- Imagen: `Dockerfile` en la raíz (multi-stage, usuario sin privilegios).
- Puerto: `8080`.
- Probes para k3s: `GET /health/live` y `GET /health/ready` (grupos de salud de Spring Boot Actuator; `ready` incluye la base de datos).
