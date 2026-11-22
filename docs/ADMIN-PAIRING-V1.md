# Vinculación SENE VENTAS ↔ SeneAdmin (cliente V1)

Este contrato prepara las APK para la etapa final de SERVER-SENE. Las APK no crean ni validan licencias por sí mismas: el servidor es la autoridad.

## Flujo

1. El patrón entra en **SOY PATRÓN → VINCULAR SENE ADMIN** en SENE VENTAS.
2. SENE VENTAS consulta en línea `POST /subscription/status` con `installation_id`.
3. Solo si el servidor responde HTTP 2xx y `active=true`, SENE VENTAS solicita `POST /admin/pairing/create`.
4. El servidor vuelve a comprobar que la licencia de esa instalación está activa y obtiene el negocio desde el registro de licencia. No debe confiar en `business_name` enviado por el cliente.
5. El servidor devuelve un código temporal de un solo uso y `expires_at`.
6. El patrón abre SeneAdmin → **VINCULAR NEGOCIO**, introduce el código y SeneAdmin llama `POST /admin/pairing/claim`.
7. El servidor consume el código atómicamente y devuelve `admin_token`, `business_id` y `business_name`.
8. SeneAdmin guarda el token cifrado con Android Keystore y sincroniza con el servidor.

## Payloads de cliente

### Estado de licencia
```json
{"installation_id":"<installation UUID>"}
```
Respuesta mínima:
```json
{"active":true,"business_name":"<negocio>","expires_at":"<ISO-8601>"}
```

### Crear código
```json
{"installation_id":"<installation UUID>","business_name":"<nombre informativo>"}
```
El servidor debe ignorar el nombre como autoridad y resolver el negocio desde la licencia.

Respuesta:
```json
{"pairing_code":"<código temporal>","expires_at":"<ISO-8601>"}
```

### Reclamar código (SeneAdmin)
```json
{"pairing_code":"<código>","admin_installation_id":"<installation UUID>"}
```
Respuesta:
```json
{"admin_token":"<token aleatorio>","business_id":"<id>","business_name":"<negocio>"}
```

## Requisitos obligatorios de SERVER-SENE

- Revalidar licencia activa en `/admin/pairing/create`; no aceptar licencia vencida, suspendida o revocada.
- Generar códigos criptográficamente aleatorios, con expiración corta (recomendado 5 minutos), almacenar solo su hash y permitir un solo canje.
- Consumir el código en una operación atómica para impedir doble vinculación.
- Asociar el código a la instalación SENE VENTAS y al negocio/licencia verificados.
- No permitir que el cliente elija o suplante `business_id` o el negocio.
- Emitir un token de SeneAdmin aleatorio, revocable y limitado al negocio asociado.
- En `/admin/pairing/claim`, rechazar códigos vencidos, usados, revocados o no existentes.
- No usar Telegram ni WhatsApp para generar, transportar o confirmar la vinculación.
- Nunca devolver ni registrar el token completo en logs.
- El cliente no considera vinculada la APK hasta recibir y guardar correctamente el token y el `business_id`.

## Estado de la implementación cliente

- SENE VENTAS comprueba la licencia en línea antes de pedir el código; no utiliza una fecha local almacenada como autorización.
- SeneAdmin ya reclama el código directamente y protege el token local con Android Keystore.
- Los endpoints y sus validaciones de base de datos se implementarán en SERVER-SENE después de cerrar y compilar ambas APK.
