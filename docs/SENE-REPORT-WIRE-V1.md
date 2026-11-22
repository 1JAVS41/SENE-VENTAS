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

El arreglo `b` se conserva localmente en ReportEventOutbox. Un reporte solo se elimina después de que la respuesta del servidor incluya su identificador en `ba` (o `report_acks`). Un error de red o una respuesta sin ACK no elimina datos. El reporte de movimientos se encola también por este canal con `k=[SYNC]` y `TYPE=MOVEMENTS` dentro del encabezado cifrado; no se crea un código wire nuevo. SeneAdmin lo presenta como «Reporte de movimientos».

## Reglas obligatorias del servidor

1. Validar instalación, licencia y vínculo antes de aceptar reportes.
2. Guardar cada evento de forma idempotente por `e`; un reintento no debe duplicarlo.
3. Asociar el reporte al negocio y trabajador autenticados; no confiar en nombres enviados por el cliente.
4. Entregar el evento a SeneAdmin mediante `POST /admin/payroll/requests/sync`, autenticado por el token admin y filtrado por negocio.
5. En la respuesta a SENE VENTAS, confirmar en `ba` únicamente después de persistir el reporte en el servidor.
6. En la respuesta a SeneAdmin, incluir los reportes pendientes en `b`; SeneAdmin los guarda localmente cifrados y devuelve sus IDs en `ba`.
7. Confirmar los ACK de SeneAdmin en `baa`; no borrar eventos de entrega hasta recibir su ACK.
8. No interpretar el contenido cifrado ni modificar sus bytes.
9. No enviar reportes, nómina ni datos de inventario por WhatsApp.

## Compatibilidad criptográfica pendiente

Los reportes nuevos usan `SENE-ADMIN-REPORT|2` cuando SENE VENTAS ya recibió `rp`, la clave pública RSA del SeneAdmin vinculado. Cada reporte usa AES-256-GCM y envuelve su clave de contenido con RSA-OAEP SHA-256. Si aún no se recibió `rp`, se conserva v1 como compatibilidad transitoria. El lector histórico y los respaldos antiguos no se borran.

## Estado de implementación

- [x] Cola durable de reportes en SENE VENTAS.
- [x] Inclusión de la cola en el payload de sincronización.
- [x] Eliminación de la ruta de envío WhatsApp para inicio, reportes periódicos y cierre.
- [x] ACK de reportes en el cliente SENE VENTAS.
- [ ] Endpoint y persistencia de SERVER-SENE.
- [x] Recepción autenticada, buzón local cifrado y cola de ACK de reportes en SeneAdmin.
- [ ] Presentación e importación sin depender de la clave compartida histórica (la importación actual conserva compatibilidad y todavía requiere esa clave).
- [x] Cifrado E2E v2 en ambos clientes y lectura compatible con reportes v1.
- [ ] SERVER-SENE debe registrar/distribuir `rp` y habilitar rotación de claves; hasta entonces las instalaciones sin clave pública siguen usando v1.
- [ ] Pruebas instrumentadas y compilación verificadas de ambas APK.
