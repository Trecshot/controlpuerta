# Control de Puerta IoT

Aplicación Android (Kotlin + Jetpack Compose + Cloud Firestore) para control de acceso físico con separación de variantes (`user` / `admin`), sesión persistente cifrada y soporte para modo de prueba o módulo Bluetooth HC-05.

## Lo destacado
- **APK móvil exclusiva de usuario (`user`):** Variante por defecto que excluye vistas y funciones administrativas y bloquea cuentas `ADMIN`.
- **Seguridad y auditoría:** Contraseñas en `SHA-512`, bloqueo automático tras 3 intentos fallidos, sesión persistente de 12 h cifrada con Android Keystore (`AES-256-GCM`) y bitácora en Cloud Firestore.
- **Modo de prueba y Bluetooth HC-05:** Operación directa sobre Firestore o por Bluetooth clásico (`SPP / RFCOMM`), con bloqueo de seguridad ante pérdida de conexión.
- **Consola administrativa (`admin`):** Gestión de usuarios, desbloqueo/cambio de clave, prueba Fail-Safe y bitácora global.

## Instalación y compilación
1. Copia [local.properties.example](local.properties.example) a `local.properties` y define:
   ```properties
   sdk.dir=RUTA_AL_ANDROID_SDK
   admin.initial.password=TU_CLAVE_SEGURA
   ```
   *(La clave inicial solo es necesaria si compilas la variante `admin` para crear `admin_maestro`).*
2. Coloca tu archivo `google-services.json` de Firebase en `app/google-services.json` (referencia en [app/google-services.json.example](app/google-services.json.example)).
3. Compila, prueba o instala desde Android Studio (en **Build Variants** elige `userDebug` o `adminDebug`) o por terminal:
   ```bash
   # Compilar APK de usuario (por defecto) y APK administrativa
   ./gradlew assembleUserDebug assembleAdminDebug

   # Instalar APK de usuario en móvil conectado por USB
   ./gradlew installUserDebug

   # Ejecutar pruebas unitarias
   ./gradlew testUserDebugUnitTest testAdminDebugUnitTest
   ```
