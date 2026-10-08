# Despliegue de Matching en k3s

`matching.yaml` crea el ConfigMap, el Deployment y el Service en el namespace `quickpatch`. Matching todavía no expone endpoints REST, así que no tiene Ingress: consume `service-request.created` de Kafka. Flyway aplica las migraciones al arrancar con el rol `matching_migrator`.

## Secreto `matching-secretos`

|Clave|Contenido|
|---|---|
|`app-password`|Contraseña de `matching_app` (sin `BYPASSRLS`)|
|`migrator-password`|Contraseña de `matching_migrator` (dueño de las tablas; solo Flyway)|

```bash
kubectl -n quickpatch create secret generic matching-secretos \
  --from-literal=app-password='<...>' --from-literal=migrator-password='<...>'
```

## Primer despliegue

1. Base: `db_matching` (con PostGIS) con dueño `matching_migrator` y el rol `matching_app` (Ansible).
2. Reemplazar `<vm-datos>` y `<vm-mensajeria>` en el ConfigMap y `kubectl apply -f deploy/k8s/matching.yaml`. El pod queda esperando la imagen (`:pendiente`).
3. Push a `release/*`: el pipeline publica la imagen y la fija en el Deployment; Flyway crea `processed_events`.
4. Solo la primera vez, un administrador de la base ejecuta `db/roles.sql` y reinicia con `kubectl -n quickpatch rollout restart deployment/matching`.
