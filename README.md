# AdvanceBoy

Emulador de Game Boy Advance para Android. También abre juegos de Game Boy y Game Boy Color.

La emulación la hace [mGBA](https://mgba.io) (versión 0.10.5), que es de lo más preciso que hay. Todo lo demás es una app Android escrita en Kotlin: la lista de juegos, los controles táctiles, el audio, la imagen, las partidas y los ajustes.

## Descargar

Desde el móvil, abre este enlace y se descarga siempre la última versión:

https://github.com/JoseLuixrax/gba-emulator/releases/latest/download/AdvanceBoy.apk

Al abrir el archivo, Android te pedirá permiso para instalar apps de fuera de Play Store desde el navegador o el gestor de archivos. Lo das una vez y listo.

Necesita Android 8.0 o superior. Las versiones nuevas se instalan encima de la anterior sin perder las partidas, pero no desinstales la app para actualizar, porque al desinstalar se borran.

## Qué tiene

- Lista de juegos a partir de una carpeta del móvil (`.gba`, `.gb`, `.gbc` y `.zip`, también en subcarpetas), con los últimos que has jugado arriba.
- 10 ranuras de estado con miniatura y fecha, más un guardado automático al salir para seguir donde lo dejaste.
- Avance rápido de x2 a x8 y cámara lenta de x0,1 a x0,5. La música va a la misma velocidad que el juego.
- Rebobinar (se activa en ajustes).
- Trucos GameShark, Action Replay y CodeBreaker. En Game Boy, GameShark y Game Genie.
- Controles táctiles que puedes mover y cambiar de tamaño, por separado en vertical y en horizontal.
- Mandos Bluetooth o USB y teclado, con los botones configurables.
- Filtros de imagen (nítido, pixelado, suavizado), líneas de escaneo, rejilla LCD y colores como los de la pantalla original de la GBA.
- Reloj de los Pokémon de GBA (también en hacks), vibración, sensor de inclinación, giroscopio y sensor solar para los juegos que los usan.
- BIOS opcional. Sin ella funciona igual con casi todos los juegos.

## Cómo se usa

La primera vez eliges la carpeta donde tienes las ROMs y te salen todos los juegos. Tocas uno y empieza.

El menú del juego se abre con el botón ≡ de la pantalla o con el botón Atrás del móvil. Desde ahí guardas y cargas estados, activas el avance rápido, metes trucos, haces capturas, recolocas los controles o reinicias.

El botón » activa el avance rápido o la cámara lenta, según la velocidad que tengas en Ajustes > Emulación. Si prefieres mantenerlo pulsado en vez de tocar para activar y desactivar, también se puede cambiar ahí.

Si juegas con mando, los controles de la pantalla se esconden solos y vuelven en cuanto tocas la pantalla.

Si mantienes pulsado un juego en la lista puedes importar o exportar su partida `.sav` (sirven las de VBA y mGBA), borrar sus estados o crear un acceso directo en la pantalla de inicio.

## Dónde se guardan las partidas

Todo va en la carpeta de la app:

```
Android/data/com.joseluixrax.gbaemu/files/
  saves/<juego>.sav            partida del juego
  states/<juego>/slotN.state   estados guardados (slot0 es el automático)
  states/<juego>/slotN.png     sus miniaturas
```

Desde Android 11 los gestores de archivos no pueden entrar en `Android/data`, así que para llegar ahí desde el móvil tienes dos caminos: Ajustes > Archivos > Abrir carpeta de partidas, o la app Archivos de Android, donde aparece "AdvanceBoy" en el menú lateral. Desde el ordenador por USB se entra normal.

Las capturas de pantalla van a la galería, en `Pictures/AdvanceBoy`.

## BIOS

No hace falta. mGBA trae una BIOS propia que va bien con casi todo. Si quieres usar la original (`gba_bios.bin`, 16 KB, sacada de tu consola), se instala desde Ajustes > BIOS.

La app no incluye juegos ni BIOS.

## Compilar

Con [Android Studio](https://developer.android.com/studio): abres la carpeta del proyecto y le das a Run. La primera vez descarga solo el NDK, CMake y el código de mGBA (unos 15 MB, a `third_party/mgba`).

Desde terminal:

```bash
./gradlew assembleDebug     # APK de pruebas en app/build/outputs/apk/debug/
./gradlew assembleRelease   # APK optimizado en app/build/outputs/apk/release/
./gradlew installDebug      # lo instala en el móvil conectado por USB
```

La versión debug se instala como una app aparte (`com.joseluixrax.gbaemu.debug`), así puedo probar cambios sin tocar la que uso para jugar.

Las dos se firman con la clave que hay en `app/debug.keystore`, que está en el repo a propósito para que cualquier APK nuevo se pueda instalar encima del anterior. Para subir la app a Google Play habría que crear una clave privada aparte.

### GitHub Actions

Cada push compila el APK y pasa los tests del núcleo. El APK queda en la pestaña Actions, dentro de la ejecución, en Artifacts.

Para sacar una versión nueva:

1. Subir `versionCode` y `versionName` en `app/build.gradle.kts`.
2. Hacer commit y push.
3. Ir a Actions > Build APK > Run workflow, marcar "release" y lanzarlo.

Eso crea el tag `v<versión>` y la release con `AdvanceBoy.apk`, y el enlace de descarga de arriba pasa a dar esa versión. También vale crear el tag a mano (`git tag v1.1.0 && git push origin v1.1.0`). Si el tag no coincide con `versionName`, la compilación falla a propósito.

## Cómo está organizado

```
app/
  build.gradle.kts            configuración de la app y descarga de mGBA
  src/main/cpp/               parte en C
    CMakeLists.txt            compila mGBA y el puente
    emu_core.c                capa sobre mGBA: cargar ROM, frames, audio, estados, trucos...
    jni_bridge.c              lo que se llama desde Kotlin
  src/main/java/com/joseluixrax/gbaemu/
    emulator/                 bucle de emulación, audio, ROMs y rutas de archivos
    ui/library/               pantalla de inicio con la lista de juegos
    ui/game/                  pantalla de juego, OpenGL, filtros, controles y mandos
    ui/cheats/                trucos
    ui/settings/              ajustes y botones del mando
    storage/                  la carpeta de partidas en la app Archivos
  src/main/res/               layouts, textos (inglés y español), iconos y ajustes
native-test/                  prueba el núcleo en Linux con ROMs de test
.github/workflows/            compilación y releases
```

Cada frame funciona así: el hilo de emulación le pide a mGBA un frame, copia la imagen para que OpenGL la pinte con el filtro elegido y manda el audio de ese frame al altavoz. Escribir el audio bloquea justo lo que dura el frame, y eso es lo que hace que el juego vaya a su velocidad real (o a x4, o a x0,5, según lo que toque).

## Tests del núcleo

Se pueden lanzar en Linux o macOS sin móvil:

```bash
mkdir -p third_party
curl -L https://github.com/mgba-emu/mgba/archive/refs/tags/0.10.5.tar.gz | tar xz -C third_party
mv third_party/mgba-0.10.5 third_party/mgba
cmake -S native-test -B build/native-test -DCMAKE_BUILD_TYPE=Release -DMGBA_DIR=$PWD/third_party/mgba
cmake --build build/native-test -j
./build/native-test/host_test native-test/roms build/native-test/out
```

Ejecuta las ROMs de `native-test/roms` ([gba-tests de jsmolka](https://github.com/jsmolka/gba-tests), licencia MIT), comprueba imagen, audio, estados, partidas Flash y SRAM, rebobinado, trucos y velocidades, y guarda una captura de cada una.

## Pendiente

- Cable link por wifi para jugar a dobles o intercambiar Pokémon entre dos móviles.
- Botones turbo y combinaciones tipo A+B.
- Copia de las partidas en la nube.
- Skins para los controles.

## Licencias

- mGBA, de Vicki Pfau y colaboradores, con licencia [Mozilla Public License 2.0](https://github.com/mgba-emu/mgba/blob/master/LICENSE). Se descarga tal cual desde su repositorio oficial.
- blip_buf (LGPL 2.1) e inih (BSD), que vienen con mGBA.
- Las ROMs de prueba de jsmolka/gba-tests (MIT), ver `native-test/roms/LICENSE-gba-tests`.

Game Boy Advance y Game Boy son marcas de Nintendo. Este proyecto no tiene nada que ver con Nintendo.
