# SERVER-SENE · despliegue del Worker

El código del Worker está en `server-sene/worker.js`. Está preparado para el esquema D1 existente de SERVER-SENE y utiliza el binding `DB`.

## Configuración en Cloudflare

1. Abre el Worker **server-sene-api**.
2. En el editor, sustituye el contenido por el archivo `worker.js` completo y pulsa **Deploy**.
3. En **Settings → Bindings**, confirma que el binding D1 se llame exactamente `DB` y apunte a la base `server-sene`.
4. En **Settings → Variables and Secrets**, configura como secretos:
   - `TELEGRAM_BOT_TOKEN`: token privado del bot.
   - `TELEGRAM_CHAT_ID`: chat del responsable.
5. No publiques ni compartas el token del bot.

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
- El nombre del negocio es único y cada negocio queda vinculado a una sola instalación.

## Comprobación posterior

Después de desplegar, abre `https://server-sene-api.nagavs28.workers.dev/health`. Debe responder con `estado: funcionando` y `base_de_datos: conectada`. Después prueba `/config` para verificar el importe. No se debe probar una activación real hasta confirmar que los secretos de Telegram están configurados.
