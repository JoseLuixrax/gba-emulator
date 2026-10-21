# AdvanceBoy — emulador de GBA para Android (estilo My Boy!)

Emulador de **Game Boy Advance** (y de regalo **Game Boy / Game Boy Color**) para Android, con la misma experiencia que *My Boy!*:
biblioteca de juegos, controles táctiles editables, estados guardados con miniatura, avance rápido, rebobinado, trucos, mandos físicos y sensores.

El núcleo de emulación es **[mGBA](https://mgba.io) 0.10.5**, el emulador de GBA de código abierto más preciso que existe. Toda la app Android (interfaz, controles, audio, vídeo, gestión de partidas) está escrita en Kotlin sobre él.

---

## 1. Conseguir el APK sin instalar nada (recomendado)

El repositorio trae un workflow de **GitHub Actions** que compila el APK en la nube en cada `push`:

1. En GitHub, ve a la pestaña **Actions** → workflow **Build APK** → la última ejecución en verde.
2. Abajo, en **Artifacts**, descarga **`AdvanceBoy-apk`** (es un `.zip` con el `.apk` dentro).
3. Pásalo al móvil e instálalo (Android te pedirá permitir *instalar apps desconocidas* para tu navegador / gestor de archivos).

**Versión publicada (más cómodo desde el móvil):** crea un tag y GitHub publicará una *Release* con el APK adjunto:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Luego entra desde el móvil a `github.com/<tu-usuario>/gba-emulator/releases` y descarga el APK.

> Todas las compilaciones se firman con la misma clave (`app/debug.keystore`), así que **cada APK nuevo se instala encima del anterior sin perder partidas**. Si algún día lo publicas en Google Play, crea tu propia clave privada (ver sección 8).

Requisitos del móvil: **Android 8.0 o superior** (ARM64, ARMv7 o x86_64).

---

## 2. Cómo se usa

| Acción | Cómo |
|---|---|
| Añadir juegos | Primera pantalla → **Elegir carpeta de ROMs** (busca `.gba`, `.gb`, `.gbc` y `.zip`, también en subcarpetas). O **Abrir un archivo ROM** suelto. |
| Jugar | Toca el juego. Si saliste a mitad, continúa donde lo dejaste (estado automático). |
| Menú del juego | Botón **≡** en pantalla o botón **Atrás** del móvil: guardar/cargar estado, avance rápido, trucos, captura, editar controles, ajustes, reiniciar, salir. |
| Avance rápido | Botón **»** (toque = activar/desactivar; configurable a "mantener"). Velocidad en Ajustes (2× – 8× o sin límite). |
| Rebobinar | Actívalo en Ajustes → aparece el botón **«** (mantener pulsado). |
| Estados guardados | 10 ranuras + guardado automático, con miniatura y fecha. Mantén pulsada una ranura para borrarla. |
| Editar controles | Menú → *Editar posición de los controles*: arrastra cada botón, pellizca (o usa − / +) para cambiar tamaño. Se guarda por separado para vertical y horizontal. |
| Mandos Bluetooth/USB | Funcionan solos (los controles táctiles se ocultan; toca la pantalla para que vuelvan). Reasigna botones en Ajustes → *Botones del mando*. |
| Trucos | Menú → *Trucos* → *Añadir truco*. GameShark, Action Replay, CodeBreaker (GBA), GameShark y Game Genie (GB/GBC) o dirección:valor. Se detecta el tipo automáticamente. |
| Partidas `.sav` | Mantén pulsado un juego en la biblioteca → *Importar / Exportar partida*. Compatibles con My Boy!, VBA y mGBA. |
| Accesos directos | Mantén pulsado un juego → *Añadir a la pantalla de inicio*. |

### Funciones especiales de cartuchos
- **Reloj en tiempo real** (Pokémon Rubí/Zafiro/Esmeralda y sus hacks): automático.
- **Vibración** (Pokémon Pinball, Drill Dozer…): usa el motor del móvil.
- **Inclinación** (Yoshi Topsy-Turvy, Kirby Tilt 'n' Tumble) y **giroscopio** (WarioWare: Twisted!): usan los sensores del móvil, solo en esos juegos.
- **Sensor solar** (Boktai): menú del juego → *Sensor solar*.

### Dónde se guardan las cosas
En el almacenamiento de la app: `Android/data/com.joseluixrax.gbaemu/files/`

```
saves/<juego>.sav            partida del juego (la batería del cartucho)
states/<juego>/slotN.state   estados guardados (slot0 = automático)
states/<juego>/slotN.png     miniaturas
```

Las capturas de pantalla van a la galería, carpeta `Pictures/AdvanceBoy`.

### BIOS
**No hace falta.** mGBA incluye una BIOS propia que funciona con prácticamente todos los juegos. Si quieres usar la BIOS original (`gba_bios.bin`, 16 KB, que tendrías que extraer tú de tu consola), instálala desde *Ajustes → BIOS*.

> Esta app no incluye juegos ni BIOS. Usa copias de tus propios cartuchos.

---

## 3. Compilar en tu ordenador (Android Studio)

1. Instala **[Android Studio](https://developer.android.com/studio)** (trae el JDK y el SDK).
2. *File → Open* → elige la carpeta del repositorio.
3. Android Studio descargará solo lo que falte: Gradle, el **NDK** (compilador de C) y **CMake**. La primera vez el propio build descarga también el código de mGBA (unos 15 MB) a `third_party/mgba`.
4. Conecta el móvil con *depuración USB* activada y pulsa ▶ **Run**.

Por línea de comandos:

```bash
./gradlew assembleDebug        # APK de pruebas -> app/build/outputs/apk/debug/
./gradlew assembleRelease      # APK optimizado -> app/build/outputs/apk/release/
./gradlew installDebug         # lo instala en el móvil conectado
```

La versión *debug* se instala como una app aparte ("com.joseluixrax.gbaemu.debug"), así puedes probar cambios sin tocar la que usas para jugar.

---

## 4. El proyecto explicado para alguien que viene de web

| Android | Equivalente web |
|---|---|
| **Gradle** (`build.gradle.kts`) | `package.json` + bundler. Dependencias en `gradle/libs.versions.toml`. |
| **Kotlin** | TypeScript, pero compilado y con tipos estrictos. |
| **Activity** | Una "página" o ruta. Cada pantalla es una Activity. |
| **Layouts XML** (`res/layout`) | Plantillas HTML. **ViewBinding** te da acceso tipado a los elementos (como `document.getElementById`, pero con tipos). |
| **`res/values/strings.xml`** | Ficheros de traducción i18n (`values-es` = español). |
| **SharedPreferences** | `localStorage`. |
| **JNI + C (NDK)** | Como cargar un módulo WebAssembly: el código C de mGBA se compila a una librería nativa (`libgbaemu.so`) y Kotlin la llama. |
| **GitHub Actions** | Tu CI/CD de siempre. |

### Estructura

```
app/
├── build.gradle.kts              configuración de la app + descarga de mGBA
├── src/main/AndroidManifest.xml  pantallas y permisos (como un "routes" + metadatos)
├── src/main/cpp/                 PARTE NATIVA (C)
│   ├── CMakeLists.txt            compila mGBA + nuestro puente
│   ├── emu_core.c/.h             envoltorio sobre mGBA (cargar ROM, frames, audio, estados, trucos…)
│   └── jni_bridge.c              funciones que Kotlin puede llamar
├── src/main/java/com/joseluixrax/gbaemu/
│   ├── emulator/                 MOTOR
│   │   ├── NativeBridge.kt       declaración de las funciones nativas
│   │   ├── EmulatorThread.kt     bucle de emulación (60 fps, sincronizado con el audio)
│   │   ├── AudioOutput.kt        sonido (AudioTrack)
│   │   ├── FrameBuffer.kt        pasa cada fotograma al renderizador
│   │   ├── RomLoader.kt          lee ROMs y .zip
│   │   └── GameFiles.kt          rutas de partidas y estados
│   ├── ui/library/               pantalla inicial (lista de juegos)
│   ├── ui/game/                  pantalla de juego
│   │   ├── GameActivity.kt       ciclo de vida, menú, estados, mandos
│   │   ├── GameRenderer.kt       dibuja la imagen con OpenGL ES
│   │   ├── Shaders.kt            filtros: nítido, pixelado, suave, scanlines, LCD, color GBA
│   │   ├── VirtualPadView.kt     mando táctil + editor
│   │   ├── PadLayout.kt          posiciones por defecto de los botones
│   │   └── InputMapping.kt       mandos físicos
│   ├── ui/cheats/                pantalla de trucos
│   ├── ui/settings/              ajustes y asignación de botones
│   └── Prefs.kt                  lectura de ajustes
└── src/main/res/                 layouts, textos, iconos, ajustes (xml/preferences.xml)
native-test/                      prueba el núcleo en Linux con ROMs de test (lo usa CI)
.github/workflows/android.yml     CI: tests nativos + APK + Release
```

### Cómo fluye un fotograma
1. `EmulatorThread` llama a `NativeBridge.runFrame()` → mGBA emula 1/60 s de GBA.
2. Copia la imagen (240×160) a `FrameBuffer` y pide a OpenGL que la pinte (`GameRenderer`, con el filtro elegido).
3. Lee el audio de ese fotograma (~800 muestras a 48 kHz) y lo escribe en el altavoz. Esa escritura **bloquea** justo lo necesario, por eso el juego va exactamente a su velocidad real.
4. Las teclas (pantalla táctil + mando) se juntan en una máscara de bits que se envía antes del siguiente fotograma.

---

## 5. Recetas de personalización

- **Cambiar el nombre de la app:** `app/src/main/res/values/strings.xml` → `app_name`.
- **Cambiar colores:** `app/src/main/res/values/colors.xml`.
- **Posición por defecto de los botones:** `PadLayout.defaults()` en `ui/game/PadLayout.kt`.
- **Añadir un ajuste:** añade la entrada en `res/xml/preferences.xml`, su texto en `strings.xml` (+ `values-es`) y léelo en `Prefs.kt`.
- **Nuevo filtro de pantalla:** añade un caso en `Shaders.kt` y su opción en `res/values/arrays.xml`.
- **Versión:** `versionCode` / `versionName` en `app/build.gradle.kts` (sube `versionCode` en cada release).

---

## 6. Pruebas del núcleo (Linux / macOS)

```bash
mkdir -p third_party
curl -L https://github.com/mgba-emu/mgba/archive/refs/tags/0.10.5.tar.gz | tar xz -C third_party
mv third_party/mgba-0.10.5 third_party/mgba
cmake -S native-test -B build/native-test -DCMAKE_BUILD_TYPE=Release -DMGBA_DIR=$PWD/third_party/mgba
cmake --build build/native-test -j
./build/native-test/host_test native-test/roms build/native-test/out
```

Ejecuta las ROMs de prueba de `native-test/roms` ([gba-tests de jsmolka](https://github.com/jsmolka/gba-tests), licencia MIT), comprueba vídeo, audio, estados, partidas Flash/SRAM, rebobinado y trucos, y guarda una captura PNG de cada una.

---

## 7. Diferencias con My Boy! y siguientes pasos

Ya incluido: biblioteca, estados con miniatura, autoguardado, avance rápido, rebobinado, trucos, filtros y shaders, corrección de color, mando táctil editable, mandos físicos con reasignación, vibración, sensores, BIOS opcional, GB/GBC, importar/exportar `.sav`, accesos directos.

Pendiente / ideas:
- **Cable link** (multijugador / intercambios Pokémon entre dos móviles por Wi-Fi). mGBA tiene la base (`GBASIOLockstep`), falta la parte de red.
- Botones turbo y combinaciones (A+B).
- Copia de seguridad en la nube de las partidas (Google Drive).
- Skins / temas para el mando.

---

## 8. Publicar en Google Play (opcional)

1. Crea una clave privada: `keytool -genkeypair -v -keystore release.jks -alias advanceboy -keyalg RSA -keysize 2048 -validity 10000` (¡guárdala bien, sin ella no podrás actualizar la app!).
2. Añade un `signingConfig` de release que lea la clave desde variables de entorno / *GitHub Secrets* y genera un `.aab` con `./gradlew bundleRelease`.
3. Cambia el `applicationId` si quieres otro identificador, y revisa que el nombre no use marcas registradas.

---

## Licencias

- Núcleo **mGBA** © Vicki Pfau y colaboradores — [Mozilla Public License 2.0](https://github.com/mgba-emu/mgba/blob/master/LICENSE). Se descarga sin modificar desde su repositorio oficial.
- **blip_buf** (LGPL 2.1) e **inih** (BSD), incluidos en mGBA.
- ROMs de prueba de **jsmolka/gba-tests** (MIT), ver `native-test/roms/LICENSE-gba-tests`.

Game Boy Advance y Game Boy son marcas de Nintendo. Este proyecto no está afiliado a Nintendo.
