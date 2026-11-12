# Consolidación de ramas — SENE VENTAS

Fecha de auditoría: 2026-11-12  
Rama consolidada de trabajo: `consolidation/ventas-unificada`  
Rama base: `integration/ack-universal`  
Repositorio: `1JAVS41/SENE-VENTAS`

## Ramas inspeccionadas

| Rama | Commit observado | Relación con la rama base |
|---|---|---|
| `integration/ack-universal` | `7dcb7916f49ac2730d54bc603e04c11998206fdb` | Base seleccionada |
| `integration/ventas-completa` | `653cc47aac2707c5194cdc149455b6063db3b9da` | Antecesora de la base; 22 commits detrás |
| `fix/ventas-cloudflare-client` | `798f49a3c12192bfe81b756aa8ffd1bcf4c59402` | Divergente; 36 commits propios y 120 commits de la base que no contiene |
| `main` | `cf392c38616a5554181b34f64116b8e2a502f5df` | Rama antigua; 120 commits detrás de la base |

La base elegida contiene el desarrollo local más amplio de VENTAS, el almacenamiento SQLite reforzado, el flujo de correcciones con persistencia y recuperación, la cola de reportes, sincronización de solicitudes y la integración inicial del ACK universal. La rama `integration/ventas-completa` ya está contenida en su historia. `main` no es una base adecuada para esta consolidación.

## Cambios rescatados de `fix/ventas-cloudflare-client`

Se incorporaron de forma adaptada a las clases ya existentes en la base:

1. Recuperación gratuita del espacio VENTAS después de reinstalar en el mismo teléfono, usando el endpoint firmado `POST /v1/ventas/restore`.
2. Persistencia local del identificador de equipo, slot (`VENTAS_1` / `VENTAS_2`) y nombre de empresa devueltos por el servidor.
3. Validación de que la sesión autenticada pertenece a un dispositivo VENTAS y persistencia del slot devuelto.
4. Lectura del prefijo de QR `SENEPAIR1:` y `SENEPAIR2:` sin distinguir mayúsculas/minúsculas.
5. Acceso visible a la recuperación desde el menú de herramientas.
6. Ejecución del workflow de compilación y smoke test también en esta rama de consolidación.

La recuperación reutiliza la identidad de continuidad existente en `DeviceIdentity`; no se copió la clase de identidad alternativa de la rama fix para evitar dos implementaciones criptográficas con el mismo alias de Android Keystore.

## Cambios de la rama fix que NO se copiaron

- Se conservó el cliente `SeneApiClient` de la rama base en lugar de añadir en paralelo `SeneVentasApiClient`; la base ya contiene pairing, solicitudes, reportes, correcciones y ACK universal.
- No se reemplazó `App.kt` ni `Store.kt` por las versiones más antiguas de la rama fix. La base tiene un `App.kt` considerablemente más amplio y un `Store.kt` con validación, migraciones y operaciones transaccionales que no están en esa rama.
- No se adoptaron los cambios de PIN configurable/salado de la rama fix. El PIN fijo del patrón acordado para SENE VENTAS es `2910`.
- No se copiaron los cambios de reportes, correcciones y nómina que ya están cubiertos por implementaciones más recientes en la base.

## Cambios de esta rama de consolidación

- `SeneApiClient.kt`: pairing tolerante a prefijos QR, almacenamiento validado del slot y nombre de empresa, sesión validada y método firmado de recuperación del mismo teléfono.
- `App.kt`: nueva opción de menú para recuperación en el mismo teléfono, con validación de datos, mensajes de error y reanudación de colas tras recuperar.
- `.github/workflows/build-test-apk.yml`: habilita compilación y smoke test en esta rama.

## Verificación pendiente

El push de esta rama disparó el workflow `Build SENE VENTAS test APK`, ejecución `43313862764`. Al redactar esta auditoría, Gradle aún estaba compilando; no se declara compilación, instalación ni smoke test aprobados hasta que la ejecución termine satisfactoriamente.

La recuperación de mismo teléfono también requiere una prueba E2E contra el Worker y D1 de pruebas con una licencia y un dispositivo de prueba existentes. No ejecutar esta recuperación en producción durante la validación.


## Evidencia de CI de las ramas de origen

- `fix/ventas-cloudflare-client`: compilación de APK completada correctamente en las ejecuciones `42950820680` y `42950814402` (11 de noviembre). Estas ejecuciones no prueban por sí solas la integración actual ni los cambios de ACK posteriores.
- `integration/ventas-completa`: ejecución `43184203356` completó la compilación y el smoke test Android 35 correctamente (12 de noviembre).
- `integration/ack-universal`: ejecución `43308365180` compiló el APK y verificó identidad/recursos, pero falló el smoke test porque, tras instalar y lanzar la app con `monkey`, `pidof com.sene.ventas` no encontró un proceso activo tres segundos después. El registro de esa ejecución no contiene una captura de Logcat en el punto de fallo, así que todavía no se atribuye la causa a un crash específico.
- Consolidación: ejecución `43313862764` fue cancelada al entrar un commit posterior; la ejecución vigente es `43314309396`, sobre el commit `63736edf268d7430c8e0b2577ebada90a0b6b8ec`. Su resultado todavía no está disponible.

## Regla para seguir consolidando

No fusionar archivos completos desde ramas divergentes solo por cantidad de commits. Cada cambio exclusivo debe compararse con la implementación de la rama base, comprobarse con el backend y conservarse únicamente si añade una capacidad útil sin duplicar ni degradar el flujo existente.
