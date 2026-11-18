# Control de Puerta IoT (Android / Kotlin + Jetpack Compose + Cloud Firestore)

Aplicación Android para control de acceso de puertas IoT con autenticación por roles, bloqueo automático tras 3 intentos fallidos, bloqueo/desbloqueo manual desde administración, validación estricta de contraseñas y bitácora cronológica en Cloud Firestore.

## Características principales
- **Autenticación, sesión TTL (12h) y roles (`ADMIN` / `USUARIO`):** Hash SHA-512 en [SecurityUtils.kt](app/src/main/java/com/example/controlpuerta/security/SecurityUtils.kt) y persistencia automática de sesión por 12 horas cifrada con Android Keystore (AES-256-GCM) en [SessionManager.kt](app/src/main/java/com/example/controlpuerta/security/SessionManager.kt), revalidando contra Firestore si la cuenta fue bloqueada.
- **Política de contraseñas:** Mínimo 8 caracteres, mayúscula, minúscula, número, carácter especial y prohibición de incluir el nombre de usuario (o sus partes) y patrones comunes.
- **Protección contra fuerza bruta:** Bloqueo automático tras 3 intentos fallidos (`BLOQUEO_POR_INTENTOS`) con desbloqueo exclusivo del Administrador (`DESBLOQUEO_ADMIN`).
- **Gestión operativa de usuarios:** Creación, eliminación, bloqueo manual (`BLOQUEO_MANUAL_ADMIN`) y filtro rápido entre `Todos` y `Bloqueados (N)` en [AdminScreen.kt](app/src/admin/java/com/example/controlpuerta/ui/screens/AdminScreen.kt).
- **Control y simulación Fail-Safe + Bluetooth HC-05:** Apertura/cierre en tiempo real sobre `dispositivo_iot/puerta_principal` en **Modo Simulación** (Firestore sin hardware físico) o mediante enlace Bluetooth clásico SPP/RFCOMM (`"1"` / `"0"`) en [BluetoothController.kt](app/src/main/java/com/example/controlpuerta/repository/BluetoothController.kt), con bloqueo automático Fail-Safe ante pérdida de enlace.

## Variantes de compilación (Product Flavors)
El proyecto utiliza dos *flavors* en Gradle (`role`) para aislar por completo el cliente móvil operativo de la consola administrativa:

- **`user` (APK móvil exclusiva para operadores):** Compila únicamente `LoginScreen` y `UserScreen`. No incluye `AdminScreen`, funciones administrativas de `FirebaseRepository`, ni la propiedad `INITIAL_ADMIN_PASSWORD`, y bloquea cualquier inicio de sesión cuyo rol no sea `USUARIO`. Además, `UserScreen` solo consulta registros propios del usuario autenticado y muestra estado de enlace / Fail-Safe, sensor Reed y buzzer.
- **`admin` (Consola administrativa completa):** Incluye `AdminScreen` (gestión CRUD de usuarios, desbloqueo, bloqueo manual, prueba Fail-Safe y bitácora global) además de `UserScreen`.

## Configuración local (requerida antes de compilar)
Este repositorio excluye secretos y configuración de Firebase mediante `.gitignore`.

1. Copia [local.properties.example](local.properties.example) a `local.properties` y define tu SDK y la clave inicial de `admin_maestro` (requerida solo si usarás la variante `admin` para inicializar por primera vez):
   ```properties
   sdk.dir=RUTA_AL_ANDROID_SDK
   admin.initial.password=TU_CLAVE_SEGURA
   ```
2. Descarga `google-services.json` desde tu proyecto en Firebase Console y colócalo en `app/google-services.json` (puedes guiarte por [app/google-services.json.example](app/google-services.json.example)).
3. Ejecuta pruebas unitarias y compila desde Android Studio o con:
   ```bash
   # Pruebas unitarias en ambas variantes
   ./gradlew testUserDebugUnitTest testAdminDebugUnitTest

   # APK exclusiva de Usuario (móvil operador)
   ./gradlew assembleUserDebug

   # APK Administrativa completa
   ./gradlew assembleAdminDebug
   ```
