# Despliegue de Matching en k3s

`matching.yaml` crea el ConfigMap, el Deployment, el Service y el Ingress (`/v1/localities` y `/v1/technicians/me`) en el namespace `quickpatch`. Consume de Kafka `service-request.created` y los eventos `technician.*`, y publica `matching.offer-created`, `matching.no-technician-available` y `matching.search-resumed` (los topics deben existir; los crea `topics.yaml` de `quickpatch-kafka`). Antes de desplegar esta versión hay que volver a aplicar `db/roles.sql` (`aplicar-roles-db.yml`), que crea `matching_outbox` y `matching_scheduler`: sin ellos el publicador del Outbox y el barrido fallan y registran el error, aunque el resto del servicio funciona. Flyway aplica las migraciones al arrancar con el rol `matching_migrator`.

## Secreto `matching-secretos`

|Clave|Contenido|
|---|---|
|`app-password`|Contraseña de `matching_app` (sin `BYPASSRLS`)|
|`migrator-password`|Contraseña de `matching_migrator` (dueño de las tablas; solo Flyway)|
|`jwt-public-key`|Llave pública RSA de Identity en PEM, para validar los tokens (ADR-018). Si falta, los endpoints con token responden 401|

```bash
kubectl -n quickpatch create secret generic matching-secretos \
  --from-literal=app-password='<...>' --from-literal=migrator-password='<...>' \
  --from-file=jwt-public-key=identity-public.pem
```

## Primer despliegue

1. Base: `db_matching` (con PostGIS) con dueño `matching_migrator` y el rol `matching_app` (Ansible).
2. Reemplazar `<vm-datos>` y `<vm-mensajeria>` en el ConfigMap y `kubectl apply -f deploy/k8s/matching.yaml`. El pod queda esperando la imagen (`:pendiente`).
3. Push a `release/*`: el pipeline publica la imagen y la fija en el Deployment; Flyway crea `processed_events`.
4. Solo la primera vez, un administrador de la base ejecuta `db/roles.sql` y reinicia con `kubectl -n quickpatch rollout restart deployment/matching`.
