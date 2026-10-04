# AGENTS — Matching Service

Repositorio autónomo.

- Stack: Java 25 + Spring Boot 4.1.1.
- Responsabilidad: disponibilidad, cobertura, candidatos, matching y asignación.
- Contratos versionados: `contracts/`.
- Kafka: Spring for Apache Kafka.
- Persistencia geoespacial: PostgreSQL + PostGIS.
- No trasladar este servicio a .NET sin una nueva ADR.
- No acceder directamente a datos propiedad de otros servicios.
- Preservar `tenantId`, `eventId` y `correlationId`.
