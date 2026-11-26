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

## Primer código aislado añadido (no conectado)

Se añadió `server-sene/guardian-delivery.js` en esta rama. Implementa el formato de sobre cifrado AES-256-GCM, firma HMAC-SHA-256, timestamp anti-replay de transporte y envío HTTPS. Requiere secretos nuevos exclusivos para GUARDIÁN. No se importó desde `worker.js`, no se activó en ninguna ruta y no se ejecutó contra producción.

En SeneAdmin se añadió `guardian-worker/src/owner-delivery.ts` en la rama `feature/sene-guardian-muse-readonly`. Recibe sobres autenticados, conserva únicamente el sobre cifrado en KV durante 24 horas y permite descifrarlos tras validar el token Muse de la instalación. Es un módulo aislado: aún no está montado en el router principal ni cubierto por la suite de pruebas existente.

### Pendientes bloqueantes antes de usar datos reales
1. Revisar y probar interoperabilidad AES-GCM/HMAC con vectores de prueba compartidos.
2. Corregir/validar el aislamiento por instalación en el ingreso: el evento cifrado debe estar criptográficamente ligado a la instalación y el receptor debe aplicar límites de frecuencia y cuota.
3. Añadir acuse y política segura de reintentos/deduplicación sin duplicar avisos.
4. Montar la ruta en el Worker y añadir pruebas unitarias/integración, incluyendo replay, firma inválida, ciphertext alterado, token revocado, separación entre instalaciones, expiración y payload excesivo.
5. Añadir el emisor al punto correcto de creación de solicitudes en `worker.js`, conservando el flujo Telegram actual y evitando que una falla del canal GUARDIÁN cancele o duplique una solicitud comercial.
6. Confirmar la autenticación real de Muse y la confidencialidad del espacio donde el propietario verá el código.
7. Solo después, desplegar primero en staging con datos sintéticos y revisión explícita antes de cualquier cambio de producción.

**No listo para producción.** Los dos módulos son scaffolds de protocolo, no una integración terminada. No se han creado secretos, modificado D1, cambiado `worker.js`, desplegado Workers ni tocado APK.
