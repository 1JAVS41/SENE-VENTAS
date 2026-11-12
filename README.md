# SENE VENTAS

Aplicación Android nativa en Kotlin. Las ventas, el inventario, la caja y los gastos siguen funcionando localmente; la sincronización remota se limita a licencias, solicitudes, respuestas, reportes de turno y nómina.

## Compilación y estado de APK

GitHub Actions compila un APK de prueba y publica el artefacto **SENE VENTAS-online**. El nombre del archivo de prueba es `SENE VENTAS-online.apk`; el nombre mostrado al instalar es **SENE-VENTAS**.

**Este artefacto de CI es una compilación Debug para pruebas, no una versión comercial Release firmada con una clave de distribución estable.** Antes de entregar actualizaciones comerciales se debe configurar y validar la firma Release protegida fuera del repositorio.

## Funciones locales preservadas

- Ventas, inventario, caja, gastos a proveedores y cierre de turno locales.
- El cierre de turno genera el reporte TXT para WhatsApp y lo encola para el servidor; los reportes pendientes se reintentan al recuperar conectividad.
- Los datos comerciales no se sincronizan con el servidor.
- El trabajador puede enviar solicitudes al patrón y consultar sus respuestas.
- Día de pago: consulta el historial reciente y solo permite enviar el reporte cuando el servidor confirma la fecha configurada.
- Antes de enviar nómina, actualiza las decisiones del patrón. Si no puede completar esa actualización, bloquea el envío.
- Los reportes de nómina conservan el historial reciente y separan los identificadores del periodo para evitar aplicar descuentos dos veces.

## Vinculación y seguridad

- Identidad criptográfica por instalación con clave P-256 en Android Keystore.
- Cliente HTTPS con retos de un solo uso y firma ECDSA.
- Vinculación QR con SENE PATRÓN y asignación de Equipo 1 o Equipo 2.
- La identidad local no activa ni vincula el equipo por sí sola; el servicio central debe autorizarlo.

## Backend

La integración Android apunta al Worker AISLADO de pruebas `sene-licensing-api-testing.1codex41.workers.dev`; no es producción ni debe usarse para cobrar clientes reales. La D1 de pruebas se migra automáticamente. La D1 y el Worker de producción permanecen separados. No activar licencias reales hasta completar pruebas E2E y una liberación comercial firmada.

