/**
 * Puertos transversales de la capa de aplicación (transacción por tenant e idempotencia de eventos), que usan
 * todos los casos de uso. Antes estaban en {@code application.events.Ports} y formaban un ciclo entre paquetes
 * (SCRUM-653).
 */
package co.quickpatch.matching.application.common;
