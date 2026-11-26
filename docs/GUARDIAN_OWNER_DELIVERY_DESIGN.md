# SERVER-SENE → SENE GUARDIÁN: entrega privada al propietario

Estado: diseño de desarrollo; no desplegado.
Rama: `feature/guardian-owner-delivery-dev`

## Propósito
Entregar al propietario autenticado de SeneAdmin los eventos de solicitudes de licencia nueva (`activation`) y renovación mensual (`renewal`), incluidos sus códigos temporales, para que el propietario decida cuándo compartirlos. No enviar códigos automáticamente al cliente.

## Contrato propuesto
SERVER-SENE emitirá un evento firmado desde el punto donde ya dispone del código en claro, después de guardar correctamente la solicitud y antes de terminar la respuesta pública.

Campos mínimos:
- `event_id`: UUID único para deduplicación.
- `event_type`: `activation.requested` o `renewal.requested`.
- `folio`, `request_type`, `created_at`, `expires_at`.
- `business_name`, `holder_name`, `whatsapp` (solo porque son necesarios para que el propietario identifique y gestione la solicitud).
- `amount_cents`, `currency`, `payment_status: pending`.
- `code`: código temporal en claro, transportado únicamente por el canal privado cifrado y nunca por Telegram adicional, logs ni analítica.

Autenticación propuesta: HMAC-SHA-256 sobre timestamp + cuerpo exacto, con secreto exclusivo de integración, tolerancia de reloj corta y rechazo de replay. HTTPS obligatorio. El receptor debe deduplicar por `event_id`, limitar tamaño y caducar el evento/código automáticamente.

## Reglas comerciales obligatorias
1. El evento se entrega a GUARDIÁN/Muse para el propietario; no al cliente.
2. No ejecutar `/redeem`, no consumir ni invalidar el código al entregar el evento.
3. No marcar el pago como verificado. La solicitud y el pago permanecen pendientes hasta el flujo real de validación.
4. El propietario es quien decide cuándo compartir el código, después de verificar el pago.
5. No modificar la notificación Telegram existente ni depender de que Telegram y GUARDIÁN estén disponibles para crear una solicitud.
6. No crear un endpoint de lectura D1 ni almacenar el código en claro en D1.
7. No reutilizar secretos de Telegram, tokens de SeneAdmin ni credenciales existentes.
8. El canal debe ser independiente, revocable, con expiración y auditoría sin contenido secreto.

## Implementación pendiente
Este documento no habilita tráfico ni constituye un puente operativo. Antes de codificar el emisor y receptor hay que cerrar y probar el protocolo de cifrado de extremo a extremo, el mecanismo de autenticación del propietario en Muse y la política de reintentos/acuse. Un simple HMAC autentica al emisor, pero no cifra el contenido por sí solo; por eso no se debe enviar el código hasta añadir cifrado de aplicación y validar el flujo completo en staging.

## Producción
No se modifica `main`, no se despliega el Worker, no se cambian secretos, D1 ni APK. No se realizan solicitudes ni canjes reales.
