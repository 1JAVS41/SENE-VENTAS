# Cifrado de reportes SENE — versión 2

## Objetivo

Eliminar la dependencia de una contraseña compartida para los reportes nuevos, sin invalidar reportes ni respaldos históricos.

## Flujo de claves

1. SeneAdmin genera un par RSA de 2048 bits en Android Keystore. La clave privada no es exportable.
2. SeneAdmin registra la clave pública DER/X.509 codificada en Base64 URL-safe durante `POST /admin/pairing/claim` y la vuelve a enviar como `rp` en `POST /admin/payroll/requests/sync`.
3. SERVER-SENE conserva la clave pública asociada al negocio y la entrega únicamente a instalaciones SENE VENTAS autenticadas y vinculadas a ese negocio.
4. SENE VENTAS guarda la clave pública y cifra cada reporte nuevo con una clave AES-256 aleatoria por reporte.
5. La clave AES se envuelve con RSA-OAEP SHA-256 / MGF1 SHA-256 usando la clave pública del patrón.
6. SeneAdmin recupera la clave AES con su clave privada local y descifra el contenido mediante AES-256-GCM.

El servidor solo almacena y transporta el sobre cifrado. No recibe la clave privada ni la clave AES en claro.

## Formato del sobre

Encabezado autenticado por GCM:

- `SENE-ADMIN-REPORT|2`
- `ID`, `WORKER`, `DATE`, `TIME`, `NUMBER`, `TYPE`
- `ALG=RSA-OAEP-SHA256+A256GCM`

Después se incluyen `WRAPPED_KEY`, `IV` y `DATA` en Base64 URL-safe. El encabezado completo es AAD de AES-GCM.

## Compatibilidad y límites

- SENE VENTAS usa v2 cuando ya recibió la clave pública autenticada del patrón.
- Si aún no hay clave pública, conserva el formato v1 para no bloquear reportes históricos o instalaciones que todavía no completaron el vínculo.
- SeneAdmin admite lectura de v1 con la clave histórica y de v2 con su clave privada Android Keystore.
- La clave privada no debe migrarse copiando archivos ni exportarse a otra instalación. Si se cambia de teléfono, el servidor deberá permitir un procedimiento de rotación/revinculación autenticado.
- El servidor aún debe implementar el registro, distribución y rotación de `rp`; este contrato de cliente no afirma que SERVER-SENE ya lo haga.
