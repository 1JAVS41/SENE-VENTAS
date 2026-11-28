# SERVER-SENE · despliegue del Worker

El código del Worker está en `server-sene/worker.js`. Está preparado para el esquema D1 existente de SERVER-SENE y utiliza el binding `DB`.

## Configuración en Cloudflare

- Editor del Worker: https://dash.cloudflare.com/6eeadce2289b24581f94b0f874806eac/workers/services/view/server-sene-api/production
- Panel de D1: https://dash.cloudflare.com/6eeadce2289b24581f94b0f874806eac/workers/d1

1. Abre el Worker **server-sene-api**.
2. En el editor, sustituye el contenido por el archivo `worker.js` completo y pulsa **Deploy**.
3. En **Settings → Bindings**, confirma que el binding D1 se llame exactamente `DB` y apunte a la base `server-sene`.
4. En **Settings → Variables and Secrets**, configura como secretos:
   - `TELEGRAM_BOT_TOKEN`: token privado del bot.
   - `TELEGRAM_CHAT_ID`: chat del responsable.
   - `GUARDIAN_DELIVERY_ENC_KEY`: clave AES-GCM de 32 bytes en base64url para cifrar los eventos de licencia de GUARDIÁN.
   - `GUARDIAN_DELIVERY_HMAC_KEY`: clave HMAC-SHA-256 (mínimo 32 bytes) en base64url para firmar los eventos de licencia de GUARDIÁN.
   - Las dos claves GUARDIÁN deben tener **exactamente los mismos valores** que los secretos del mismo nombre en el Worker `sene-guardian-api`; si no coinciden, GUARDIÁN rechaza los eventos.
5. No publiques ni compartas el token del bot ni las claves GUARDIÁN.

## Rutas implementadas

- `GET /health`: verifica que el Worker puede consultar D1.
- `GET /config`: devuelve el importe configurado en `app_settings.amount_cents`.
- `POST /requests`: registra activación o renovación, genera folio y código de un solo uso y notifica a Telegram.
- `POST /redeem`: valida el código y activa 30 días.
- `POST /subscription/status`: comprueba que la instalación tenga suscripción vigente.

## Datos que espera la APK

`POST /requests` recibe JSON con `type` (`activation` o `renewal`), `holder_name`, `whatsapp`, `business_name` e `installation_id`.

`POST /redeem` recibe `folio`, `code` e `installation_id`.

`POST /subscription/status` recibe `installation_id`.

## Reglas

- La solicitud tiene un plazo de 48 horas.
- El código no vence por tiempo; se invalida cuando se crea una solicitud nueva después de que vence la anterior.
- El código es de un solo uso y se almacena como hash en D1.
- La suscripción comienza al validar el código y dura 30 días.
- La renovación del mismo dispositivo suma 30 días a la fecha de vencimiento vigente.
- El responsable verifica el pago manualmente y comparte el código recibido por Telegram solo después de confirmar el depósito.
- El nombre del negocio es único; cada negocio queda vinculado a una sola instalación y cada instalación solo puede registrar un negocio.
- Una instalación no puede crear otra solicitud mientras tenga una solicitud pendiente dentro de las 48 horas.

## Productor de eventos de licencia (SENE GUARDIÁN, solo propietario)

`worker.js` incluye un productor que avisa al Muse del propietario cada vez que se crea una solicitud de licencia y cada vez que una se completa. Los eventos **nunca** se envían a un Muse de cliente: el único destino es el canal del propietario de GUARDIÁN.

- Destino: `POST https://sene-guardian-api.nagavs28.workers.dev/v1/guardian/owner-events` (el mismo endpoint que recibe los eventos en `guardian-worker/src/owner-delivery.ts` del repo SeneAdmin).
- Transporte: el evento viaja cifrado con AES-GCM (clave `GUARDIAN_DELIVERY_ENC_KEY`, 32 bytes) y firmado con HMAC-SHA-256 (clave `GUARDIAN_DELIVERY_HMAC_KEY`). Encabezados `x-sene-timestamp` (segundos unix), `x-sene-event-id` (UUID) y `x-sene-signature` (HMAC base64url de `timestamp.eventId.iv.ciphertext`). Cuerpo JSON `{ event_id, iv, ciphertext }`, todo en base64url. El AES-GCM usa el `event_id` como `additionalData`.

| Evento | Cuándo se envía | Campos propios |
|---|---|---|
| `activation.requested` / `renewal.requested` | En `POST /requests`, después de avisar por Telegram y marcar la notificación como enviada | `payment_status: "pending"`, `expires_at` (48 h) |
| `activation.completed` / `renewal.completed` | En `POST /redeem`, después de guardar la suscripción en D1 | `payment_status: "paid"`, `valid_from` / `valid_until` (30 días) |

Campos comunes en todos los eventos: `event_id` (UUID), `installation_id`, `folio` (`SENE-...`), `request_type` (`activation` o `renewal`), `created_at`, `amount_cents` (> 0), `currency: "MXN"`, `business_name`, `holder_name`, `whatsapp` (solo dígitos), `code` (código de 10 caracteres). Sin estos campos, o con un campo inválido, GUARDIÁN rechaza el evento con `invalid_event`.

Reglas:

- **Flujo en un solo sentido (regla del propietario).** SENE VENTAS solo ENVÍA avisos hacia GUARDIÁN; el administrador (Blowback) solo RECIBE a través de su Muse. Nunca hay flujo del administrador ni del Muse del propietario hacia SENE VENTAS: GUARDIÁN no escribe en server-sene ni en D1, no aprueba, no activa, no envía códigos ni decisiones a SENE VENTAS. Este productor hace exactamente una llamada saliente (`POST` a `/v1/guardian/owner-events`) y no añade ninguna ruta entrante, ninguna escritura en D1 ni ninguna aprobación. El código comercial sigue decidiendo solo, con sus datos, como antes.
- **Best-effort, sin tocar el flujo comercial.** Si las claves GUARDIÁN no están configuradas, si GUARDIÁN no responde en 5 segundos o si rechaza el evento, el productor solo registra el fallo con `console.warn` y la solicitud/activación sigue su curso normal. La única notificación que puede cancelar una solicitud sigue siendo Telegram (comportamiento previo, sin cambios).
- **Sin reintentos ni cola.** Un evento que falla no se reintenta: la solicitud pendiente sigue visible en D1 y la licencia completada sigue en `subscriptions`; el propietario puede consultarlas directamente en D1.
- **Sin códigos a clientes.** El código solo viaja cifrado al canal del propietario; ningún evento se envía al MCP de una instalación.
- **Sin cambios en D1.** El productor solo lee los datos que `/requests` y `/redeem` ya tienen; en `/redeem` la consulta se amplió con `amount_cents`, `created_at`, `business_name`, `holder_name` y `whatsapp` para poder construir el evento.
- **Costo $0.** Solo es una llamada HTTPS saliente por solicitud y por activación, sin servicios de pago.

Para activar el productor en producción: desplegar `worker.js` y configurar los dos secretos GUARDIÁN con los mismos valores que en `sene-guardian-api`. Sin esos secretos, el Worker comercial funciona igual, pero GUARDIÁN no recibe eventos (el propietario seguirá viendo 0 licencias activas y 0 solicitudes pendientes hasta que se configuren).

## Comprobación posterior

Después de desplegar, abre `https://server-sene-api.nagavs28.workers.dev/health`. Debe responder con `estado: funcionando` y `base_de_datos: conectada`. Después prueba `/config` para verificar el importe. No se debe probar una activación real hasta confirmar que los secretos de Telegram están configurados.
