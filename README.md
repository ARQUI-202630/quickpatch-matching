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

Pasar a `disponible` o cambiar la cobertura reintenta la solicitud en espera más antigua que el técnico puede atender (RN-M10, ver Matching).

## Base de datos

- Migraciones con Flyway en `src/main/resources/db/migration/`. Las ejecuta `FLYWAY_USER` (el rol `_migrator` en el pipeline).
- `localities` trae las 20 localidades de la capa oficial "Localidad" de la Secretaría Distrital de Planeación (Mapa de Referencia IDECA, EPSG 4326), generalizadas a unos 22 m (migración `V3`). Requiere PostGIS, que ya existe en `db_matching`.
- `matching_requests`, `matching_attempts` y `outbox_events` (migración `V4`), con RLS por tenant. Los índices únicos parciales sobre las ofertas `pendiente` garantizan RN-M1 (una por solicitud) y RN-M7 (una por técnico).
- Roles del DD 10.2 en `db/roles.sql`: lo aplica un administrador después de las migraciones. El servicio se conecta como `matching_app` (sin `BYPASSRLS`) y fija `app.current_tenant` en cada transacción. Adopta con `SET LOCAL ROLE`, solo dentro de su transacción, `matching_outbox` (publicador del Outbox) y `matching_scheduler` (barrido, P-06), que tienen `BYPASSRLS` y que `matching_app` recibe con `INHERIT FALSE`.
- Variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `FLYWAY_USER`, `FLYWAY_PASSWORD`.

## Eventos

|Evento|Rol|Efecto|
|---|---|---|
|`service-request.created` v1|Consume (grupo `matching-service`)|Registra el `eventId` en `processed_events` e inicia el matching en la misma transacción, bajo el tenant del evento. Un evento repetido no repite el efecto; un mensaje que no cumple el contrato se registra y se descarta; un error de la base se reintenta cada 2 s sin confirmar el offset.|
|`service-request.cancelled` v1|Consume (grupo `matching-service`, SCRUM-476)|En una transacción bajo el tenant del evento, con el `eventId` en `processed_events`: la oferta pendiente pasa a `cancelado`, la búsqueda a `cancelado` y, si el técnico que la aceptó está `ocupado`, vuelve a `disponible` y se le ofrece la solicitud en espera más antigua (RN-M5, RN-M6, RN-M10). Bloquea oferta → técnico → solicitud, como la aceptación. Una solicitud que Matching no tiene se descarta; el motivo nunca va a los logs.|
|`technician.registered` v1|Consume (grupo `matching-service`)|Crea la fila del técnico en `technician_availability` (DD 5.6, P-01) en `no_disponible`, con su especialidad y su estado de verificación.|
|`technician.verification-changed` v1|Consume (grupo `matching-service`)|Copia el estado de verificación nuevo (aprobado, rechazado, suspendido) sin tocar la disponibilidad; si el registro aún no llegó, crea la fila. Solo los `aprobado` serán candidatos (RN-TP1, RN-TP2).|

Los eventos `technician.*` se aplican una vez por `eventId` y por versión: `projection_updated_at` guarda el `data.updatedAt` del último aplicado y un evento más viejo se descarta (ADR-017), así que el orden de llegada entre los dos topics no deja la copia atrás.

|`matching.offer-created` v1|Publica (Outbox)|Oferta a un técnico; clave `serviceRequestId`.|
|`matching.no-technician-available` v1|Publica (Outbox)|La solicitud quedó en espera sin candidatos.|
|`matching.search-resumed` v1|Publica (Outbox)|Una solicitud en espera vuelve a buscar (`trigger`: `disponibilidad`, `cobertura`, `aprobacion` o `barrido`).|

Los eventos publicados se guardan en `outbox_events` en la misma transacción que el cambio y `OutboxPublisher` los envía cada segundo con el sobre del contrato (`producer` = `matching-service`) y la cabecera `eventId`. Si Kafka falla, suma un intento y reintenta (ADR-007).

## Matching

`MatchingEngine` (SCRUM-29, DD 7.5):

1. **Solicitud nueva:** al consumir `service-request.created` guarda la solicitud en `matching_requests` (`buscando`) y busca candidatos.
2. **Candidatos (RN-M9):** técnicos del mismo tenant, `aprobado`, `disponible`, con la especialidad igual a la categoría, con una zona de cobertura que contiene la ubicación (`ST_Covers`), sin otra oferta pendiente y sin una oferta anterior para esa solicitud. Un suspendido o rechazado no es candidato.
3. **Cercanía:** Matching no guarda la posición del técnico, solo su cobertura. Por eso ordena por la distancia, en metros sobre el elipsoide, entre la solicitud y el centroide de toda la cobertura del técnico: primero quien cubre la zona cercana a la solicitud y no media ciudad. A igual distancia va primero quien lleva más tiempo disponible. La distancia queda en `matching_attempts.distance_m`.
4. **Oferta:** al primer candidato le registra una oferta `pendiente` que vence en `MATCHING_OFFER_TTL` (3 minutos, RN-M8) y publica `matching.offer-created`. Si otra transacción tomó al técnico en paralelo, el índice único lo impide y prueba el siguiente.
5. **Sin candidatos:** la solicitud pasa a `en_espera` y se publica `matching.no-technician-available`, sin relajar el criterio (RN-M4, AC9-E4).
6. **Reintento (RN-M10):** cuando un técnico pasa a `disponible`, cambia su cobertura o es aprobado, la solicitud en espera más antigua que puede atender vuelve a `buscando` con `matching.search-resumed` y se ofrece de nuevo, en la misma transacción del cambio. Cada `MATCHING_SWEEP_INTERVAL` (1 minuto) un barrido de respaldo reanuda las que ya tienen candidato.
7. **Respuesta del técnico (SCRUM-79):** `GET /v1/matching/offers` lista su oferta pendiente con la localidad y la distancia, sin la dirección (RN-SR13). Al aceptar, en una sola transacción la oferta queda `aceptado`, el técnico `ocupado` (RN-M6), la solicitud `asignado` y se publica `matching.technician-assigned`. Al rechazar, la solicitud pasa al siguiente candidato (RN-M3) o a `en_espera`. Una oferta inexistente, ajena, respondida o vencida responde `409 oferta-no-disponible`; un técnico que dejó de estar disponible, `409 tecnico-no-disponible`.
8. **Cancelación (SCRUM-476):** al consumir `service-request.cancelled`, la oferta pendiente y la búsqueda pasan a `cancelado` y no se ofrece a nadie más (RN-M5); el técnico asignado vuelve a `disponible` (RN-M6) y se reanuda para él la solicitud en espera más antigua (`liberacion`, RN-M10).
9. **Vencimiento (RN-M8):** cada `MATCHING_EXPIRY_INTERVAL` (15 segundos) las ofertas pendientes vencidas pasan a `expirado` y la solicitud se ofrece al siguiente candidato. Los tenants se buscan con `matching_scheduler` y cada cambio se aplica como `matching_app` bajo RLS.

`MatchingEngine.offerNext` es el paso que sigue a un rechazo o a un vencimiento (RN-M3). Lo usan la aceptación y el rechazo de ofertas y su vencimiento (SCRUM-79).

Variables: `KAFKA_BOOTSTRAP_SERVERS`, `MATCHING_OFFER_TTL` (`3m`), `MATCHING_SWEEP_INTERVAL` (`PT1M`), `MATCHING_EXPIRY_INTERVAL` (`PT15S`), `OUTBOX_PUBLISHER_ROLE` (`matching_outbox`) y `MATCHING_SCHEDULER_ROLE` (`matching_scheduler`); los roles vacíos solo sirven en desarrollo local, con un usuario que ya ve todas las filas. Los logs salen en JSON (ECS) con `correlationId`, `tenantId` y `eventId`.

## Contenedor

- Imagen: `Dockerfile` en la raíz (multi-stage, usuario sin privilegios).
- Puerto: `8080`.
- Probes para k3s: `GET /health/live` y `GET /health/ready` (grupos de salud de Spring Boot Actuator; `ready` incluye la base de datos).
