# SENE REPORT WIRE V1

Estado: contrato de cliente en preparación. El endpoint de servidor y la recepción en SeneAdmin siguen pendientes; esta especificación no significa que SERVER-SENE ya esté implementado.

## Transporte

Los reportes viajan dentro de `POST /payroll/requests/sync`, en el arreglo compacto `b`. No se envían por WhatsApp.

Cada elemento contiene:

| Campo | Significado |
|---|---|
| `v` | Versión del protocolo SENE Wire |
| `t` | `[SYNC]`, envoltura de sincronización |
| `e` | Identificador estable e idempotente del evento |
| `w` | Identificador del trabajador |
| `k` | Código de evento ya acordado: `[EC]` inicio, `[CO]` cierre normal, `[CA]` cierre anticipado, `[AD]` cierre administrativo; `[SYNC]` reporte periódico |
| `i` | Fecha/hora de creación en milisegundos |
| `b` | Reporte cifrado, sin texto legible en el transporte |

El arreglo `b` se conserva localmente en ReportEventOutbox. Un reporte solo se elimina después de que la respuesta del servidor incluya su identificador en `ba` (o `report_acks`). Un error de red o una respuesta sin ACK no elimina datos.

## Reglas obligatorias del servidor

1. Validar instalación, licencia y vínculo antes de aceptar reportes.
2. Guardar cada evento de forma idempotente por `e`; un reintento no debe duplicarlo.
3. Asociar el reporte al negocio y trabajador autenticados; no confiar en nombres enviados por el cliente.
4. Entregar el evento a SeneAdmin mediante su sincronización autenticada.
5. Confirmar en `ba` únicamente después de persistirlo de forma durable.
6. No interpretar el contenido cifrado ni modificar sus bytes.
7. No enviar reportes, nómina ni datos de inventario por WhatsApp.

## Compatibilidad criptográfica pendiente

El contenido `b` todavía usa el formato cifrado histórico para mantener compatibilidad con lectores y respaldos existentes. La migración a cifrado nuevo sin clave compartida requiere definir y desplegar el esquema criptográfico de extremo a extremo antes de retirar el lector histórico. No se debe borrar ni invalidar la clave ni los respaldos anteriores durante esta etapa.

## Estado de implementación

- [x] Cola durable de reportes en SENE VENTAS.
- [x] Inclusión de la cola en el payload de sincronización.
- [x] Eliminación de la ruta de envío WhatsApp para inicio, reportes periódicos y cierre.
- [x] ACK de reportes en el cliente SENE VENTAS.
- [ ] Endpoint y persistencia de SERVER-SENE.
- [ ] Recepción, presentación y ACK de reportes en SeneAdmin.
- [ ] Migración criptográfica compatible y retirada segura de la clave compartida.
- [ ] Pruebas instrumentadas y compilación verificadas de ambas APK.
