# SERVER-SENE · despliegue del Worker

El código del Worker está en `server-sene/worker.js`. Está preparado para el esquema D1 existente de SERVER-SENE y utiliza el binding `DB`.

## Configuración en Cloudflare

- Editor del Worker: https://dash.cloudflare.com/6eeadce2289b24581f94b0f874806eac/workers/services/view/server-sene-api/production
- Panel de D1: https://dash.cloudflare.com/6eeadce2289b24581f94b0f874806eac/workers/d1

1. Abre el Worker **server-sene-api**.
2. En el editor, sustituye el contenido por el archivo `worker.js` completo y pulsa **Deploy**.
3. En **Settings → Bindings**, confirma que el binding D1 se llame exactamente `DB` y apunte a la base `server-sene`.
4. En **Settings → Variables and Secrets**, configura como secretos:
   - `TELEGRAM_BOT_TOKEN`: token privado del bot (opcional; solo aviso genérico).
   - `TELEGRAM_CHAT_ID`: chat del responsable (opcional; solo aviso genérico).
   - `GUARDIAN_OWNER_EVENTS_URL`: URL HTTPS exacta del endpoint `/v1/guardian/owner-events` del Worker SENE GUARDIÁN.
   - `GUARDIAN_DELIVERY_ENC_KEY`: misma clave AES-GCM de 32 bytes codificada en Base64URL que usa SENE GUARDIÁN.
   - `GUARDIAN_DELIVERY_HMAC_KEY`: misma clave HMAC-SHA256 de al menos 32 bytes codificada en Base64URL que usa SENE GUARDIÁN.

   Las dos claves criptográficas deben coincidir exactamente con los secretos del Worker SENE GUARDIÁN. Configúralas como secretos en ambos Workers; nunca las publiques en Git ni las compartas por chat. Si la URL o las claves faltan, la solicitud falla de forma cerrada y el código se invalida.
5. No publiques ni compartas el token del bot.

## Rutas implementadas

- `GET /health`: verifica que el Worker puede consultar D1.
- `GET /config`: devuelve el importe configurado en `app_settings.amount_cents`.
- `POST /requests`: registra activación o renovación, genera folio y código de un solo uso y entrega los datos/código cifrados exclusivamente al MUSE propietario. Telegram, si está configurado, recibe solo un aviso genérico.
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
- Los datos y códigos de activación/renovación se consultan exclusivamente desde el MUSE del propietario mediante el canal central cifrado AES-GCM + HMAC. El MUSE cliente no recibe esos eventos.
- Telegram nunca recibe nombres, teléfonos, importes ni códigos; solo un aviso genérico de que existe una solicitud. Su fallo no bloquea la entrega al MUSE propietario.
- El propietario verifica el pago manualmente y decide cuándo y si comparte el código con el cliente.
- El nombre del negocio es único; cada negocio queda vinculado a una sola instalación y cada instalación solo puede registrar un negocio.
- Una instalación no puede crear otra solicitud mientras tenga una solicitud pendiente dentro de las 48 horas.

## Comprobación posterior

Después de desplegar, abre `https://server-sene-api.nagavs28.workers.dev/health`. Debe responder con `estado: funcionando` y `base_de_datos: conectada`. Después prueba `/config` para verificar el importe. No se debe probar una activación real hasta confirmar que los secretos de Telegram están configurados.
