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

El reporte de cobertura queda en `target/site/jacoco/jacoco.xml` (el CI exige ≥ 80%).

## Contenedor

- Imagen: `Dockerfile` en la raíz (multi-stage, usuario sin privilegios).
- Puerto: `8080`.
- Probes para k3s: `GET /health/live` y `GET /health/ready` (grupos de salud de Spring Boot Actuator).
