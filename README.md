# Control de Puerta IoT

Aplicación móvil Android (**Kotlin + Jetpack Compose + Material 3**) para el control y monitoreo de acceso físico IoT mediante arquitectura híbrida (**Cloud Firestore + Bluetooth HC-05**), con dos variantes instalables en paralelo (`user` y `admin`).

## Aspectos destacados

- **Interconexión híbrida (`Wi-Fi / Cloud` + `Bluetooth HC-05`):**
  - **Operador (`user` – `com.example.controlpuerta`):** control por proximidad física vía Bluetooth Clásico (`SPP / RFCOMM` con HC-05), timeouts de conexión/escritura, detección activa de desconexión, lectura de telemetría Serial del sensor magnético Reed y operación sin Internet con sincronización automática al recuperar señal. *(Modo simulación disponible solo en `debug`, deshabilitado en `release`).*
  - **Administrador (`admin` – `com.example.controlpuerta.admin`):** monitoreo y operación remota por Wi-Fi/Internet, gestión de usuarios (alta atómica, bloqueo manual, desbloqueo con restablecimiento de clave y eliminación protegida) y bitácora global en tiempo real.
- **Política OT Fail-Safe / Fail-Secure:** ante corte eléctrico del nodo Arduino/HC-05, pérdida de señal Bluetooth o caída de red con la puerta abierta, la aplicación fuerza automáticamente el estado seguro **CERRADA y bloqueada** (`abierta = false`, `sensorReed = "CERRADO"`, `buzzerActivo = false`), deshabilita los controles y audita el evento `CIERRE_FAIL_SAFE`.
- **Almacenamiento e interoperabilidad en tiempo real (Cloud Firestore):** sincronización instantánea entre múltiples dispositivos Android sobre `usuarios`, `dispositivo_iot/puerta_principal` y `bitacora_accesos`, utilizando escrituras atómicas por lote (`db.batch()`) e índice compuesto `(username, timestamp)` en [firestore.indexes.json](firestore.indexes.json).
- **Seguridad IoT (ISO/IEC 27400):**
  - Contraseñas derivadas con `PBKDF2WithHmacSHA256` (120.000 iteraciones + sal aleatoria por usuario), política de robustez obligatoria y bloqueo atómico al 3.º intento fallido.
  - Sesión persistente de 12 horas cifrada con **Android Keystore (`AES-256-GCM`)**, verificación contra servidor (`Source.SERVER`) y revocación en vivo si el administrador bloquea o elimina la cuenta.
  - Configuración inicial de `admin_maestro` en el primer arranque (sin contraseñas embebidas en el APK), respaldos de Android deshabilitados (`allowBackup="false"`), aislamiento de bitácora por rol, comunicación sobre **HTTPS/TLS** y reglas estrictas de esquema e inmutabilidad de auditoría en [firestore.rules](firestore.rules).
- **Pruebas automatizadas:** 45 pruebas unitarias (`JUnit4`) sobre seguridad, políticas de sesión, intentos fallidos, roles y protocolo Bluetooth/Fail-Safe, más 3 pruebas de interfaz (`Jetpack Compose Test`) en `LoginScreenTest`.

## Compilación y ejecución

1. Copiar [local.properties.example](local.properties.example) a `local.properties` y configurar `sdk.dir`.
2. Agregar `google-services.json` de Firebase en `app/google-services.json` (ver referencia en [app/google-services.json.example](app/google-services.json.example)).
3. Compilar, instalar o ejecutar pruebas desde Android Studio (**Build Variants**: `userDebug` / `adminDebug`) o terminal:
   ```bash
   ./gradlew assembleUserDebug assembleAdminDebug
   ./gradlew testUserDebugUnitTest testAdminDebugUnitTest
   ```
4. Desplegar índices y reglas de Cloud Firestore:
   ```bash
   firebase deploy --only firestore:indexes,firestore:rules
   ```
