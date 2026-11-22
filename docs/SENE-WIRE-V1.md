# SENE Wire Protocol v1

Este contrato corresponde a las dos APK de cliente. El servidor se implementará al final y actuará únicamente como transporte autenticado, cola y reenviador. **No calcula nómina, no decide autorizaciones y no modifica importes ni reglas de asistencia.**

## Diccionario compartido

| Código | Significado visible |
|---|---|
| `[P$]` | Solicitud de préstamo |
| `[C$]` | Solicitud de consumo |
| `[SR]` | Aún no tenía registro |
| `[F]` | Falta |
| `[EC]` | En curso |
| `[CO]` | Jornada completa |
| `[CA]` | Cierre anticipado |
| `[AD]` | Cierre administrativo |
| `[DP][L]` ... `[DP][D]` | Día de pago, lunes a domingo |
| `[PD]` | Producto dañado |
| `[PC]` | Producto caducado |
| `[ME]` | Merma |
| `[DV]` | Devolución |
| `[EA]` | Entrada de almacén |
| `[SA]` | Salida de almacén |
| `[AJ]` | Ajuste de inventario |
| `[AG]` | Producto agotado |
| `[OK]` / `[NO]` | Decisión autorizada / rechazada |
| `[SYNC]`, `[AT_SYNC]`, `[IN_SYNC]` | Controles internos de transporte |

Los códigos nunca se muestran al trabajador ni al patrón; ambas APK los traducen a español.

## SENE VENTAS → servidor

`POST /payroll/requests/sync`

Sobre: `{v:1,t:"[SYNC]",i:<installation_id>,c:<cursor>,r:[...],h:[...],g:[...]}`

- `r`: solicitudes pendientes. Cada elemento contiene `v,t,e,w,a,n,r,z,p,q,i,k`. `a` es importe en centavos; `i` es fecha/hora Unix en milisegundos; `e` es ID idempotente.
- `h`: eventos de asistencia. Cada elemento contiene `v,t:"[AT_SYNC]",e,w,d,x,s,f,m,o`. `s` inicio y `f` cierre en milisegundos; `d` es fecha local `yyyy-MM-dd`; `x` es `[EC]`, `[CO]`, `[CA]` o `[AD]`.
- `g`: eventos de inventario. Cada elemento contiene `v,t:"[IN_SYNC]",e,g,i,p`. `g` identifica ámbito `M` (inventario maestro) o `S` (inventario de trabajador); `i` es timestamp Unix en milisegundos. `p` es la lista de productos cambiados, con `c,n,a,s,q,x,l,h`: código, nombre, precio en centavos, existencia absoluta, delta, tipo de movimiento, pasillo y estante.

Respuesta del servidor:
- `d`: decisiones de solicitudes, con `e,x,by,at,rr`; `x` es `[OK]` o `[NO]`.
- `n`: cursor siguiente.
- `ha`: IDs de eventos de asistencia que el servidor confirma haber guardado durablemente.
- `ga`: IDs de eventos de inventario que el servidor confirma haber guardado durablemente.

La APK elimina un evento de su cola local únicamente después de recibir su ID en el ACK correspondiente.

## SeneAdmin → servidor

`POST /admin/payroll/requests/sync`

Sobre: `{v:1,t:"[SYNC]",a:<admin_token>,c:<cursor>,d:[...],ha:[...],gi:[...]}`

- `d`: decisiones pendientes, cada una con `e,x,r`.
- `ha`: ACKs de asistencia aplicados durablemente por SeneAdmin.
- `gi`: ACKs de inventario aplicados durablemente por SeneAdmin.

Respuesta del servidor:
- `items`: solicitudes, con `e,x,w,i,a,t,z,r,p,q,by,at,rr`.
- `h`: eventos de asistencia pendientes de aplicar.
- `g`: eventos de inventario pendientes de aplicar.
- `n`: cursor siguiente.
- `haa`: IDs de ACK de asistencia ya confirmados por el servidor.
- `gaa`: IDs de ACK de inventario ya confirmados por el servidor.

SeneAdmin conserva cada ACK localmente hasta que el servidor confirme su recepción. Los eventos se deduplican por `e`.

## Reglas obligatorias del servidor

1. Autenticar cada dispositivo y negocio; nunca confiar en códigos de evento para autorizar.
2. Mantener colas durables y deduplicar por negocio, tipo e ID `e`.
3. Reenviar decisiones del patrón a SENE VENTAS; el servidor no las inventa ni las aprueba.
4. Reenviar asistencia e inventario a SeneAdmin y conservarlos hasta recibir ACK.
5. No descontar sueldo, no calcular faltas, no calcular límites, no cambiar inventario por su cuenta y no aplicar reglas de nómina.
6. Rechazar versiones incompatibles sin borrar colas locales ni eventos pendientes.
7. Mantener compatibilidad temporal con las claves largas existentes mientras se migra el despliegue.
