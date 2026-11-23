# SERVER-SENE — API integral (rama de desarrollo)

## Estado

Esta implementación está en `feature/server-sene-complete-api`. No está desplegada en Cloudflare y no reemplaza todavía el Worker productivo.

- Worker completo: `server-sene/worker.js`
- Migraciones aditivas: `0001_complete_sync.sql` y `0002_retention.sql`
- Diagnóstico de esquema: `server-sene/diagnose-schema.sql`

## Infraestructura que se conserva

- Worker existente: `server-sene-api`
- D1 existente: `server-sene` (binding `DB`)
- Secretos existentes: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`
- Tablas originales de licencias, pagos, solicitudes, notificaciones y recuperación.

No crear otra base de datos, no cambiar el binding, no regenerar secretos y no ejecutar el despliegue desde esta rama.

## Instalación controlada

1. Revisar el diff de esta rama.
2. Hacer respaldo/bookmark de la D1.
3. Ejecutar una sola vez `migrations/0001_complete_sync.sql` y después `migrations/0002_retention.sql` en la D1 existente.
4. Confirmar que las tablas nuevas aparecen y que las tablas antiguas conservan sus registros.
5. Validar el Worker en un entorno de prueba con una copia de D1.
6. Solo después de aprobar las pruebas, reemplazar el contenido del Worker y desplegar.

La migración usa únicamente `CREATE TABLE/INDEX IF NOT EXISTS`; no elimina ni modifica las tablas actuales.

## Rutas implementadas

| Método | Ruta | Función |
|---|---|---|
| GET | `/health` | Estado de Worker y D1 |
| GET | `/config` | Precio público |
| POST | `/requests` | Solicitud de activación/renovación |
| POST | `/redeem` | Canje de código |
| POST | `/subscription/status` | Estado de licencia y clave pública de reportes |
| POST | `/account/recovery/request` | Solicitar recuperación |
| POST | `/account/recovery/complete` | Completar recuperación |
| POST | `/account/device/transfer` | Completar transferencia autenticada de dispositivo |
| POST | `/admin/pairing/create` | Código temporal, solo con licencia activa |
| POST | `/admin/pairing/claim` | Vincular SeneAdmin y registrar clave pública RSA |
| POST | `/payroll/requests/sync` | SENE VENTAS envía eventos y recibe decisiones/ACK |
| POST | `/admin/payroll/requests/sync` | SeneAdmin recibe solicitudes, asistencia, inventario y reportes |

## Contrato de sincronización

Se conserva el protocolo v1 y sus códigos existentes. El servidor no inventa códigos de negocio.

- SENE VENTAS: `q` solicitudes `[P$]` / `[C$]` (el Worker también acepta `r` para compatibilidad con la APK cliente actual), `h` asistencia, `g` inventario y `b` sobres de reportes.
- SeneAdmin: `d` decisiones, `ha` ACK de asistencia, `gi` ACK de inventario, `ba` ACK de reportes y `rp` clave pública RSA.
- El servidor devuelve a SENE VENTAS `d`, `ha`, `ga`, `ba` y `rp`. Las decisiones aprobadas/rechazadas se reenvían de forma idempotente en cada sincronización; esta respuesta no usa cursor.
- El servidor devuelve a SeneAdmin `items`, `h`, `g`, `b`, `haa`, `gaa`, `baa` y cursor `n` para paginar las solicitudes.

Los sobres de reportes se almacenan y transportan como texto cifrado; el Worker no posee ni necesita la clave privada de SeneAdmin. Los reportes no se envían por WhatsApp.

## Activación de la limpieza programada en Cloudflare

Después de desplegar este Worker (y solo tras aprobar las pruebas), abrir el Worker existente `server-sene-api` en Cloudflare:

1. Entrar en **Settings → Triggers → Cron Triggers**.
2. Agregar el cron `0 4 * * *` (una vez al día, UTC).
3. Guardar y verificar que el trigger aparece asociado al Worker.

El manejador programado revisa diariamente el vencimiento de licencias, pero la limpieza de historial usa el marcador `server_sync_state` y solo se ejecuta cuando han transcurrido 30 días desde la limpieza anterior. Si el cron aún no está configurado, la misma comprobación de retención se intenta durante la sincronización normal.

## Pendiente antes de producción

- Ejecutar pruebas integrales con una D1 de prueba y APK instaladas.
- Confirmar con eventos reales el esquema exacto de los sobres de asistencia/inventario/reportes.
- Probar idempotencia, reintentos, decisiones y ACK de extremo a extremo.
- Revisar límites de tamaño y paginación con cargas reales.
- Revisar autenticación, revocación y rotación del vínculo de SeneAdmin.
- Retención automática cada 30 días: conserva los 10 préstamos/consumos resueltos más recientes por tipo, los 10 reportes entregados por categoría y los 10 eventos de inventario/asistencia entregados por categoría. Nunca elimina solicitudes pendientes, eventos sin ACK ni reportes sin confirmar entrega.
- Las tablas de asistencia e inventario conservan el historial entregado; el ACK marca la entrega y la limpieza programada conserva los 10 más recientes por categoría y 30 días. Los eventos sin ACK nunca se eliminan.

**No desplegar este Worker ni ejecutar la migración en producción hasta que se complete y apruebe la validación.**
