# BJLO Browser

Navegador web minimalista e instalador de APKs para Android TV, Fire OS y móviles (Kotlin nativo, sin Compose).

## Compilar

Requisitos: Android Studio (Ladybug o más nuevo) **o** JDK 17 + Android SDK (plataforma 35 y build-tools).

**Android Studio:** `File > Open` sobre esta carpeta, espera la sincronización y pulsa *Run*.

**Línea de comandos:**

```bash
# crea local.properties con la ruta de tu SDK, por ejemplo:
echo "sdk.dir=/ruta/a/Android/sdk" > local.properties

./gradlew testDebugUnitTest   # tests del enrutador de URLs
./gradlew assembleDebug       # APK en app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # APK optimizado (firmado con la clave debug; cambia a tu keystore para publicar)
```

Instalar en un Fire TV / Android TV por red:

```bash
adb connect IP_DEL_TV:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Uso

- **Barra de direcciones** (acción Buscar / Enter):
  vacío → Google · solo números → `https://aftv.news/<código>` · `http(s)://…` → tal cual ·
  `localhost` / IP → `http://` · texto con punto y sin espacios → `https://dominio` · otra cosa → búsqueda en Google.
- **Menú (≡ o tecla Menú):** filtro DNS, bloqueo de anuncios, modo cursor, modo escritorio, inicio, recargar, limpiar datos.
- **Control remoto:** flechas mueven el cursor, OK hace clic, el cursor al borde superior salta a la barra,
  en los bordes inferior/laterales desplaza la página. Atrás vuelve a la página anterior.
- **APK:** al tocar un enlace `.apk` pide confirmación (muestra archivo y origen), descarga con DownloadManager
  y abre el instalador del sistema. La primera vez Android pedirá permitir "instalar apps desconocidas" para BJLO Browser.

## Filtro antipublicidad

1. Lista local de dominios de anuncios conocidos (funciona siempre, también en modo "Automático (Sistema)").
2. Con AdGuard / NextDNS / Control D, cada dominio nuevo se consulta por DoH; si el resolvedor lo bloquea
   (responde 0.0.0.0, `::` o loopback) la petición se cancela. Resultado en caché por dominio. Si el DNS falla, se deja pasar.
3. CSS inyectado al terminar la carga para colapsar huecos de anuncios (sin ocultar todos los iframes).

NextDNS necesita el ID de tu configuración (se pide la primera vez que lo eliges).

## Estructura

```
app/src/main/java/com/bjlo/browser/
  MainActivity.kt        UI, WebView, menú, cursor del control remoto
  UrlRouter.kt           reglas de la barra de direcciones (con tests)
  DnsFilter.kt           DoH + lista local de anuncios
  CosmeticFilter.kt      CSS/JS de limpieza
  DownloadInstaller.kt   DownloadManager + FileProvider + instalador
  CursorOverlayView.kt   cursor virtual
  Prefs.kt               SharedPreferences "BJLO_PREFS" y proveedores DNS
_icons_src/              SVG simplificados usados para generar los íconos
```

## Notas

- Las descargas se guardan en la carpeta propia de la app (sin permiso de almacenamiento) y se borran tras 24 h.
- `usesCleartextTraffic=true` porque muchos enlaces de descarga siguen siendo `http://`.
- Google Play restringe `REQUEST_INSTALL_PACKAGES`; este proyecto está pensado para distribución directa (sideload) o Amazon Appstore.
