# Diseño seguro de correcciones locales de ventas

Estado de integración: la rama `integration/ventas-completa` ya incorpora el flujo de interfaz, cola remota idempotente, consumo de permiso de un solo uso y aplicación local transaccional. Aún requiere compilación y pruebas completas en dispositivo; no habilitar correcciones en producción hasta cerrar esas pruebas.

## Objetivo y límites

Una corrección solo puede aplicarse a una venta ya registrada y después de que SENE PATRÓN autorice una vez esa venta en ese dispositivo. El permiso autoriza una sola operación. Los productos, códigos, cantidades, precios, subtotales, caja e inventario permanecen en el teléfono; al servidor solo llegan la solicitud mínima, su identificador y el resultado del permiso.

Antes de cobrar, el trabajador puede modificar libremente el ticket abierto. Esa edición no usa el flujo de correcciones.

## Hallazgo del almacenamiento actual

- Las ventas y los movimientos se guardan en SQLite, en las tablas `sales` y `moves`.
- El inventario de productos se guarda en `SharedPreferences` dentro de `datos.products`.
- `sellTicket()` descuenta existencias y guarda primero el inventario en preferencias; después inserta las líneas de venta en SQLite.
- `cashBalance()`, el resumen del turno y los reportes calculan las ventas sumando `subtotal` por línea.
- Por lo tanto, actualizar una venta y el inventario hoy implicaría escribir en dos almacenes distintos. Android podría cerrar el proceso entre ambas escrituras y dejar caja, venta e inventario inconsistentes.

No es seguro conectar todavía el botón de autorización con una modificación directa de esos datos.

## Tipos de corrección permitidos

La solicitud del trabajador permitirá elegir la acción que corresponda y describirla:

- **Cambiar producto:** quitar el producto equivocado y registrar el producto correcto. El producto correcto toma el **precio vigente del catálogo local en el momento de aplicar la corrección**.
- **Cambiar cantidad:** aumentar o reducir las unidades del producto vendido.
- **Eliminar producto(s):** retirar uno o varios renglones que no debieron cobrarse y devolver esas unidades al inventario.
- **Agregar producto omitido:** incorporar un producto que sí se entregó pero no quedó registrado, sujeto a stock y al cobro de la diferencia.
- **Otra corrección:** permitir explicar un caso distinto para que el patrón lo revise; no ejecutar acciones que la aplicación no pueda validar de forma segura.

El trabajador selecciona el renglón afectado y especifica qué ocurrió. El patrón ve la solicitud y decide si autoriza o rechaza. La autorización es para una sola corrección de esa venta. La aplicación vuelve a validar los datos localmente al ejecutarla.

## Regla confirmada para caja e inventario

El propietario confirmó que **la corrección debe conservar el registro real de caja e inventario para que coincidan durante una auditoría**.

- Si el total corregido aumenta, el trabajador debe cobrar la diferencia al cliente al momento de aplicar la corrección.
- Si el total corregido disminuye, el trabajador debe devolver la diferencia al cliente al momento de aplicar la corrección.
- Si el total no cambia, no hay movimiento de efectivo.
- Una corrección que requiera cobro o devolución no se puede finalizar hasta que el trabajador confirme que el movimiento de efectivo se realizó.
- El total histórico de la venta se actualiza al importe corregido. El saldo de caja se calcula con ese total corregido, de modo que el cobro/devolución físico coincida con el saldo.
- Además se registra un movimiento de auditoría explícito con importe, sentido (cobro o devolución), motivo, folio de venta, valores anterior/nuevo, fecha y referencia a la corrección. Ese movimiento es evidencia y **no debe volver a sumarse ni restarse en el cálculo de caja**, porque el total de la venta ya refleja el ajuste; hacerlo duplicaría el efecto.
- Para agregar un producto omitido, el producto debe haberse entregado, debe existir stock suficiente para dejar el inventario correcto y debe cobrarse la diferencia antes de completar la corrección.
- Para eliminar un producto o reducir cantidad, se devuelve el efectivo correspondiente y las unidades retiradas regresan al inventario.
- No se permiten ajustes silenciosos que cambien el total contable sin el movimiento real de efectivo correspondiente.

## Avance implementado en la base local

- El inventario tiene almacenamiento SQLite con marcador de migración transaccional.
- La migración de ventas/movimientos históricos usa también un marcador dentro de la misma transacción SQLite para evitar duplicarlos si Android cierra la aplicación antes de limpiar las preferencias.
- El registro de una venta nueva ya cuenta con una operación que guarda el descuento de inventario y todas las líneas de venta dentro de una sola transacción SQLite.
- Se añadió `HistoryDb.applySaleCorrection()` y su fachada `Store.applySaleCorrection()`: aplica cambios de renglones, precio vigente, existencias, total y auditoría dentro de una sola transacción SQLite; exige que el journal esté en `grant_consumed`, que coincidan folio/operación/permiso y que el trabajador confirme el cobro o devolución cuando cambia el total.
- El registro de auditoría `CORRECTION_AUDIT` incluye total anterior/nuevo, importe, sentido, motivo, folio, operación y permiso; `countedInCash=false` evita duplicarlo en el saldo. El respaldo de inventario en preferencias se refresca después del commit, mientras SQLite sigue siendo la fuente principal.
- El folio UUID de venta se valida para rechazar un reintento si ese ticket ya fue guardado. Las preferencias y el TXT quedan como respaldos secundarios, no como fuente principal.
- La integración actual conecta el flujo de solicitud, autorización y consumo remoto con `Store.applySaleCorrection()`. La compilación automática y las pruebas de migración, recuperación, caja, inventario y fallos en dispositivo siguen siendo obligatorias antes de habilitarlo en producción.

## Arquitectura propuesta

### 1. Una sola base transaccional para ventas e inventario

Agregar una tabla SQLite `products` que almacene cada producto como JSON, conservando los campos actuales (`code`, `name`, `price`, `stock`) y cualquier campo adicional que ya exista.

La migración debe:
1. Crear la tabla y una tabla de metadatos de migración.
2. Copiar `datos.products` desde preferencias dentro de una transacción SQLite.
3. Marcar la migración en la misma transacción, para que un cierre inesperado no duplique ni pierda productos al reintentar.
4. Cambiar `Store.products()` y `Store.saveProducts()` para leer y escribir SQLite.
5. Mantener una ruta de compatibilidad y verificación de conteos/códigos antes de dejar de consultar la copia antigua.

No se deben borrar datos de preferencias hasta comprobar que la migración quedó completa y que la aplicación puede volver a abrir el inventario.

### 2. Aplicación de una corrección como una única transacción

Crear una operación central, por ejemplo `Store.applySaleCorrection(...)`, que dentro de una sola transacción SQLite:

1. Busque la venta original por `saleId` y compruebe que existe y no fue corregida previamente con el mismo permiso.
2. Compruebe que el permiso local corresponde a esa venta y que aún no se ha consumido.
3. Valide el producto/código original, producto final, cantidades y existencias disponibles.
4. Ajuste las existencias: devolver al inventario las unidades que dejan de pertenecer a la venta y descontar las unidades del producto correcto.
5. Actualice, sustituya o elimine los renglones afectados; para un producto agregado, crear el renglón correspondiente. Recalcular subtotales con las reglas de precio aprobadas, conservando fecha, folio y datos originales de auditoría.
6. Recalcule el total de la venta. Compare el nuevo total con el anterior y exija el cobro o devolución de la diferencia antes de confirmar la operación.
7. Inserte en `moves` un registro de auditoría del ajuste de efectivo (sin volver a contabilizarlo en el saldo), con folio, motivo, importe, sentido, valores anteriores y nuevos, fecha, dispositivo y referencia al permiso.
8. Marque el permiso como consumido y la corrección como aplicada.
9. Confirme la transacción. Si cualquier paso falla, hacer rollback completo: ni venta, ni stock, ni caja, ni permiso quedan parcialmente modificados.

La confirmación de efectivo debe formar parte de la operación protegida: no debe ser posible marcar la corrección como aplicada si no se registró el cobro/devolución requerido. La operación debe ser idempotente: reintentar el mismo identificador de corrección no puede duplicar el ajuste ni consumir otro permiso.


### Contrato de la operación local implementada

La operación guardada en `correction_ops` debe conservar el mismo JSON y actualizar su estado de forma durable antes de ejecutarse:

```json
{
  "opId": "UUID de operación",
  "saleId": "folio de venta existente",
  "state": "grant_consumed",
  "grantId": "permiso de un solo uso",
  "cashConfirmed": true,
  "reason": "motivo local",
  "changes": [
    { "fromCode": "SKU-ANTERIOR", "toCode": "SKU-NUEVO", "newQty": 2 },
    { "fromCode": "SKU-ELIMINADO", "toCode": "", "newQty": 0 },
    { "fromCode": "", "toCode": "SKU-OMITIDO", "newQty": 1 }
  ]
}
```

`fromCode` vacío agrega un renglón omitido; `newQty=0` elimina el renglón. `toCode` siempre se resuelve contra el catálogo local y toma su precio vigente. Para cambiar el producto, `newQty` es la cantidad final de ese renglón. La operación rechaza códigos ambiguos/duplicados, productos inexistentes, stock insuficiente, permisos que no coinciden, ventas sin folio real y operaciones no autorizadas. El método es interno y no debe llamarse desde UI hasta integrar la fase de consumo remoto idempotente y las pruebas de fallos.

### 3. Caja, reportes e historial

- Caja y reportes deben sumar los subtotales corregidos de las ventas.
- El movimiento de efectivo de la corrección se conserva como evidencia de auditoría, pero no se vuelve a contabilizar en el saldo para evitar duplicidad.
- El reporte de cierre debe mostrar por separado la venta corregida, el importe anterior, el importe nuevo y el cobro/devolución realizado.
- El registro de auditoría conserva el antes/después; nunca se borra ni se oculta la operación original.
- Los pagos a proveedores permanecen separados de las correcciones.
- Una corrección no debe cambiar la fecha ni el turno de la venta original.
- Los datos comerciales detallados no deben aparecer en solicitudes, respuestas ni logs del servidor.

## Pruebas obligatorias antes de habilitarlo

- Migración de inventario vacío, inventario grande, productos duplicados y cierre inesperado durante migración.
- Corrección de cantidad hacia arriba y hacia abajo.
- Sustitución de producto con precio vigente, stock insuficiente y producto inexistente.
- Eliminación total/parcial de productos.
- Agregar producto omitido con y sin stock suficiente y verificar cobro de diferencia.
- Cobro/devolución exactos, cancelación antes de confirmar efectivo y reintentos sin duplicar caja.
- Venta histórica sin metadatos de checklist.
- Dos intentos simultáneos y reintento después de cierre inesperado.
- Permiso equivocado, revocado o ya consumido.
- Fallo inyectado en cada etapa: rollback completo.
- Verificar que total de caja, reporte del turno, inventario, ticket y auditoría coincidan antes y después.
- Verificar que los datos comerciales nunca aparecen en solicitudes, respuestas ni logs del servidor.

## Secuencia de implementación

1. Completar y verificar la compilación de la pantalla de tickets/checklist.
2. Migrar inventario a SQLite con pruebas de preservación y compatibilidad.
3. Completar pruebas de la operación transaccional y auditoría local, sin activar aún el flujo de permisos.
4. Conectar el journal con el consumo remoto idempotente y la recuperación tras cierre inesperado; luego integrar el registro de cobro/devolución y su conciliación con el total corregido.
5. Probar con datos de prueba y fallos simulados.
6. Conectar el permiso de un solo uso únicamente después de pasar las pruebas locales.
7. Más adelante, integrar QR de ticket e impresora Bluetooth en una etapa separada.

No desplegar el Worker ni aplicar migraciones a D1 real como parte de este trabajo.
