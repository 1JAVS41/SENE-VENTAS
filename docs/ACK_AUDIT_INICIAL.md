# Auditoría inicial de ACK en SENE VENTAS

Fecha: 2026-11-12  
Rama base: `integration/ventas-completa`  
Cliente auditado: `app/src/main/java/com/sene/ventas/SeneApiClient.kt` (blob `bce23289`).

## Hallazgos comprobados

El cliente actual tiene operaciones firmadas para:
- `requestUpdates`: descarga decisiones usando `since`.
- `sendShiftReport`: envía reporte de cierre con `shiftKey`.
- `sendPayrollReport`: envía reporte con `periodKey`.
- `sendDailyInventory`: envía snapshot por fecha.

No se encontró en este cliente un método general de ACK para decisiones, reportes, snapshots ni paquetes. El método `requestUpdates` utiliza marca de tiempo, no un cursor monotónico confirmado. Las operaciones de envío reciben respuesta del servidor, pero esa respuesta solo puede confirmar la aceptación del Worker; no confirma que PATRÓN haya descargado y guardado el reporte.

## Cambios requeridos sin alterar la lógica funcional

1. Mantener ventas, caja, gastos, productos e inventario operativo en el teléfono; no subir esos datos.
2. Cada solicitud/reporte/snapshot que sí se transmite debe tener un identificador idempotente persistido antes del primer intento.
3. Reintentar con el mismo identificador y el mismo cuerpo canónico; no duplicar operaciones.
4. Guardar localmente decisiones y respuestas remotas antes de confirmar el cursor/entrega al Worker.
5. Sustituir `since` por cursor monotónico cuando el Worker esté listo para entregas durables.
6. Reportes compactos y snapshots deben permanecer en cola local hasta recibir recibo de aceptación; el recibo de aceptación no equivale al ACK posterior de PATRÓN.
7. No introducir solicitudes adicionales por cada fila: usar lotes y un ACK agregado por cursor/entrega.
8. Mantener el flujo local de reportes cada dos horas y WhatsApp manual del trabajador fuera del ACK del servidor.

## Seguridad de reintentos

Un `operationId` repetido con el mismo hash debe devolver el mismo recibo sin volver a escribir ni consumir cuota. El mismo `operationId` con otro hash debe producir conflicto. Los ACK de descarga serán firmados con P-256 y ligados al dispositivo, destinatario, cursor y hash.

## Dependencias

La integración requiere el protocolo transversal `docs/PROTOCOLO_ACK_UNIVERSAL.md` del repositorio SENE-ADMIN y el Worker actualizado en la rama `integration/ack-universal` del repositorio SENE-PATRON.

Este documento es una auditoría de brechas; no afirma que el ACK general ya esté implementado ni modifica la lógica de ventas.
