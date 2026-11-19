# Control de Puerta IoT

Aplicación Android (Kotlin + Jetpack Compose + Cloud Firestore) para control de acceso físico con separación de variantes (`user` / `admin`), sesión persistente cifrada y soporte para modo de prueba (solo debug) o módulo Bluetooth HC-05.

## Lo destacado
- **Modelo Híbrido Obligatorio (`Wi-Fi / Cloud` + `Bluetooth HC-05`):**
  - **Administrador (`admin`):** Gestión remota por **Wi-Fi / Internet (Cloud Firestore)** sin depender de cercanía Bluetooth.
  - **Operador en puerta (`user`):** Control por **proximidad física mediante Bluetooth (`SPP / RFCOMM` con HC-05)**. En `release` el modo simulación está deshabilitado.
- **Política Fail-Safe / Fail-Secure (Cerrada y bloqueada por defecto):** Si la puerta está abierta y se corta la energía del Arduino/HC-05, se pierde la señal Bluetooth o cae el enlace de red, el sistema fuerza automáticamente la puerta a **CERRADA (`abierta = false`, `sensorReed = "CERRADO"`, `buzzerActivo = false`)**, bloquea los botones de operación y audita el evento `CIERRE_FAIL_SAFE` en la bitácora.
- **Seguridad:** contraseñas con `PBKDF2WithHmacSHA256` + sal aleatoria por usuario (las cuentas antiguas SHA-512 se migran solas al iniciar sesión), bloqueo atómico tras 3 intentos (transacción de Firestore), sesión de 12 h cifrada con Android Keystore (`AES-256-GCM`), revocación en vivo si el admin bloquea/elimina la cuenta, respaldo de Android desactivado y bitácora inmutable.
- **Bluetooth HC-05 robusto (modo dual):** timeouts de conexión/escritura, detección activa de pérdida de enlace, comandos serializados, operación sin internet (la bitácora se sincroniza al recuperar señal) y lectura de telemetría del sensor magnético Reed si el Arduino responde por Serial (`ABIERTO`/`CERRADO` o `1`/`0`), con confirmación automática por envío si el Arduino solo recibe comandos.
- **Consola administrativa (`admin`, paquete `com.example.controlpuerta.admin`):** coexistencia simultánea con la APK de usuario en el mismo dispositivo, gestión de usuarios (alta atómica, confirmación al eliminar), desbloqueo/cambio de clave, prueba Fail-Safe y bitácora global. La cuenta `admin_maestro` se crea en el primer arranque desde la propia app (ya no hay contraseña embebida en el APK).

## Instalación y compilación
1. Copia [local.properties.example](local.properties.example) a `local.properties` y define `sdk.dir`.
2. Coloca tu archivo `google-services.json` de Firebase en `app/google-services.json` (referencia en [app/google-services.json.example](app/google-services.json.example)).
3. Compila, prueba o instala desde Android Studio (en **Build Variants** elige `userDebug` o `adminDebug`) o por terminal:
   ```bash
   ./gradlew assembleUserDebug assembleAdminDebug
   ./gradlew installUserDebug
   ./gradlew testUserDebugUnitTest testAdminDebugUnitTest
   ```

## Despliegue de Firestore (obligatorio)
```bash
firebase deploy --only firestore:indexes   # índice (username, timestamp) de la bitácora del usuario
firebase deploy --only firestore:rules     # reglas de esquema (ver limitaciones)
```
> **Limitación importante:** la app utiliza autenticación por colección `usuarios` en Firestore (`PBKDF2WithHmacSHA256` + sal), sin Firebase Authentication (`request.auth == null`), por lo que [firestore.rules](firestore.rules) valida estrictamente esquema, tipos e invariantes (bitácora inmutable, protección de `admin_maestro`, bloqueo hasta 3 intentos).
