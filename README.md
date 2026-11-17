# Control de Puerta IoT (Android / Kotlin + Jetpack Compose + Cloud Firestore)

Aplicación Android para control de acceso de puertas IoT con autenticación por roles, bloqueo automático tras 3 intentos fallidos, bloqueo/desbloqueo manual desde administración, validación estricta de contraseñas y bitácora cronológica en Cloud Firestore.

## Características principales
- **Autenticación y roles (`ADMIN` / `USUARIO`):** Hash SHA-512 en [SecurityUtils.kt](app/src/main/java/com/example/controlpuerta/security/SecurityUtils.kt).
- **Política de contraseñas:** Mínimo 8 caracteres, mayúscula, minúscula, número, carácter especial y prohibición de incluir el nombre de usuario (o sus partes) y patrones comunes.
- **Protección contra fuerza bruta:** Bloqueo automático tras 3 intentos fallidos (`BLOQUEO_POR_INTENTOS`) con desbloqueo exclusivo del Administrador (`DESBLOQUEO_ADMIN`).
- **Gestión operativa de usuarios:** Creación, eliminación, bloqueo manual (`BLOQUEO_MANUAL_ADMIN`) y filtro rápido entre `Todos` y `Bloqueados (N)` en [AdminScreen.kt](app/src/main/java/com/example/controlpuerta/ui/screens/AdminScreen.kt).
- **Control y simulación Fail-Safe:** Apertura/cierre en tiempo real sobre `dispositivo_iot/puerta_principal` y cierre automático de seguridad ante pérdida de enlace.

## Configuración local (requerida antes de compilar)
Este repositorio excluye secretos y configuración de Firebase mediante `.gitignore`.

1. Copia [local.properties.example](local.properties.example) a `local.properties` y define tu SDK y la clave inicial de `admin_maestro`:
   ```properties
   sdk.dir=RUTA_AL_ANDROID_SDK
   admin.initial.password=TU_CLAVE_SEGURA
   ```
2. Descarga `google-services.json` desde tu proyecto en Firebase Console y colócalo en `app/google-services.json` (puedes guiarte por [app/google-services.json.example](app/google-services.json.example)).
3. Ejecuta pruebas unitarias y compila desde Android Studio o con:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
