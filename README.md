# AdvanceBoy

Emulador de Game Boy Advance y Nintendo DS para Android. También abre juegos de Game Boy y Game Boy Color.

La emulación de GBA, GB y GBC la hace [mGBA](https://mgba.io) (versión 0.10.5) y la de DS, [melonDS](https://melonds.kuribo64.net) (versión 1.1). Son de lo más preciso que hay. Todo lo demás es una app Android escrita en Kotlin: la lista de juegos, los controles táctiles, el audio, la imagen, las partidas y los ajustes.

## Descargar

Desde el móvil, abre este enlace y se descarga siempre la última versión:

https://github.com/JoseLuixrax/gba-emulator/releases/latest/download/AdvanceBoy.apk

Al abrir el archivo, Android te pedirá permiso para instalar apps de fuera de Play Store desde el navegador o el gestor de archivos. Lo das una vez y listo.

Necesita Android 8.0 o superior. Para los juegos de DS hace falta además un móvil de 64 bits, que son casi todos los de los últimos años. Las versiones nuevas se instalan encima de la anterior sin perder las partidas, pero no desinstales la app para actualizar, porque al desinstalar se borran.

## Qué tiene

- Lista de juegos a partir de una carpeta del móvil (`.gba`, `.gb`, `.gbc`, `.nds` y `.zip`, también en subcarpetas), con los últimos que has jugado arriba.
- Juegos de DS con las dos pantallas, la táctil se usa con el dedo, botones X e Y y un botón para soplar al micrófono, y también con estados, trucos y rebobinado. Más abajo hay un apartado solo para DS.
- 10 ranuras de estado con miniatura y fecha, más un guardado automático al salir para seguir donde lo dejaste.
- Avance rápido de x2 a x8 y cámara lenta de x0,1 a x0,5. La música va a la misma velocidad que el juego.
- Rebobinar (se activa en ajustes).
- Trucos GameShark, Action Replay y CodeBreaker. En Game Boy, GameShark y Game Genie. En DS, Action Replay DS.
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

## Juegos de DS

Se abren igual que los de GBA, desde la misma lista. No hace falta BIOS ni firmware de una consola: melonDS trae los suyos y arranca el juego directamente.

- En vertical las dos pantallas van arriba y los controles debajo. En horizontal van en el centro, una encima de la otra, y los controles a los lados. En Ajustes > Nintendo DS hay más formas de verlas:
  - En horizontal: una al lado de la otra, una grande y otra pequeña, o solo una.
  - En vertical: las dos o solo una.
- El botón ↕ intercambia las pantallas: la de abajo pasa a ser la principal (arriba, a la izquierda, la grande o la única que se ve). Va muy bien con "una grande y otra pequeña" en los juegos que se juegan casi todo con la táctil. También está en el menú del juego y se puede poner en un botón del mando, y la app se acuerda de cómo lo dejaste.
- La pantalla de abajo es táctil: tocas y arrastras con el dedo como si fuera el lápiz. Los botones que estén encima de ella tienen preferencia, y si juegas con mando y los controles están escondidos, toda la pantalla responde al dedo.
- El botón MIC hace como si soplaras al micrófono, que es lo que piden algunos juegos.
- Desde el menú del juego puedes cerrar y abrir la tapa de la consola. La mayoría de juegos se ponen en reposo, pero algunos lo usan en la propia partida. También se puede asignar a un botón del mando.
- En Ajustes > Nintendo DS puedes poner tu nombre (hasta 10 letras), el que tendría la consola. Algunos juegos lo enseñan, y se aplica la próxima vez que abras un juego.
- El idioma de los juegos es el del móvil (español, inglés, francés, alemán, italiano o japonés).
- La partida se guarda sola en cuanto el juego termina de escribirla, y también al salir o al cambiar de app.
- El avance rápido y la cámara lenta funcionan igual que en GBA, con la música a la misma velocidad.
- El modo rápido (JIT) viene activado porque hace que los juegos vayan mucho mejor. Si algún juego se cierra solo al abrirlo, prueba a quitarlo en Ajustes > Nintendo DS.
- Los estados funcionan igual que en GBA: 10 ranuras con miniatura (de la pantalla de arriba) y el automático al salir. Van comprimidos, así que ocupan bastante menos que los ~19 MB que mide un estado de DS por dentro. Si tienes un estado de melonDS 1.1 de PC (`.mln`), también se puede cargar: cópialo a `states/<juego>/` con el nombre `slot1.state` (o el número que quieras) y sale en esa ranura.
- Los trucos son códigos Action Replay DS, de los de toda la vida: parejas de números de 8 cifras, una por línea. Si te equivocas al copiarlo, la app te avisa antes de guardarlo.
- El rebobinado en DS guarda una foto del juego cada 6 fotogramas y al mantener el botón « vuelve atrás al doble de velocidad. Una foto de DS pesa mucho más que una de GBA, así que la app guarda solo los cambios entre una y otra y el historial no pasa de unos 100 MB de memoria; según el juego eso da para más o menos segundos. En móviles justos de potencia se nota algo en la velocidad, así que si no lo usas, mejor dejarlo apagado.

## Dónde se guardan las partidas

Todo va en la carpeta de la app:

```
Android/data/com.joseluixrax.gbaemu/files/
  saves/<juego>.sav            partida del juego (GBA y DS)
  states/<juego>/slotN.state   estados guardados (slot0 es el automático)
  states/<juego>/slotN.png     sus miniaturas
```

Desde Android 11 los gestores de archivos no pueden entrar en `Android/data`, así que para llegar ahí desde el móvil tienes dos caminos: Ajustes > Archivos > Abrir carpeta de partidas, o la app Archivos de Android, donde aparece "AdvanceBoy" en el menú lateral. Desde el ordenador por USB se entra normal.

Las capturas de pantalla van a la galería, en `Pictures/AdvanceBoy`.

## BIOS

No hace falta. mGBA trae una BIOS propia que va bien con casi todo. Si quieres usar la original (`gba_bios.bin`, 16 KB, sacada de tu consola), se instala desde Ajustes > BIOS.

La app no incluye juegos ni BIOS.

## Compilar

Con [Android Studio](https://developer.android.com/studio): abres la carpeta del proyecto y le das a Run. La primera vez descarga solo el NDK, CMake y el código de mGBA y de melonDS (a `third_party/mgba` y `third_party/melonds`).

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
  build.gradle.kts            configuración de la app y descarga de mGBA y melonDS
  src/main/cpp/               parte en C y C++
    CMakeLists.txt            compila los dos núcleos, cada uno en su librería
    emu_core.c                capa sobre mGBA: cargar ROM, frames, audio, estados, trucos...
    jni_bridge.c              lo que se llama desde Kotlin para GBA
    nds/                      lo mismo para DS sobre melonDS (núcleo, archivos e hilos, JNI)
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

Cada frame funciona así: el hilo de emulación le pide un frame al núcleo (mGBA o melonDS), copia la imagen para que OpenGL la pinte con el filtro elegido y manda el audio de ese frame al altavoz. Escribir el audio bloquea justo lo que dura el frame, y eso es lo que hace que el juego vaya a su velocidad real (o a x4, o a x0,5, según lo que toque).

## Tests del núcleo

Se pueden lanzar en Linux o macOS sin móvil:

```bash
mkdir -p third_party
curl -L https://github.com/mgba-emu/mgba/archive/refs/tags/0.10.5.tar.gz | tar xz -C third_party
mv third_party/mgba-0.10.5 third_party/mgba
curl -L https://github.com/melonDS-emu/melonDS/archive/refs/tags/1.1.tar.gz | tar xz -C third_party
mv third_party/melonDS-1.1 third_party/melonds
cmake -S native-test -B build/native-test -DCMAKE_BUILD_TYPE=Release \
  -DMGBA_DIR=$PWD/third_party/mgba -DMELONDS_DIR=$PWD/third_party/melonds
cmake --build build/native-test -j
./build/native-test/host_test native-test/roms build/native-test/out
./build/native-test/nds_host_test native-test/nds-roms/test.nds build/native-test/out-nds
```

El primero ejecuta las ROMs de `native-test/roms` ([gba-tests de jsmolka](https://github.com/jsmolka/gba-tests), licencia MIT), comprueba imagen, audio, estados, partidas Flash y SRAM, rebobinado, trucos y velocidades, y guarda una captura de cada una.

El segundo arranca una ROM de DS casera hecha solo para esto (el código está en `native-test/nds-rom` y se genera con `build.py`) y comprueba las dos pantallas, los botones, el audio a cada velocidad, la carga desde archivo, que la partida se guarde bien, los estados (también los `.mln` de melonDS y que uno roto no estropee la partida), el rebobinado y los trucos Action Replay. La ROM lleva un contador de fotogramas en memoria que se pinta en pantalla, y así se ve si un estado o el rebobinado vuelven de verdad al momento correcto.

## Pendiente

- DS: usar el micrófono de verdad, para los juegos que piden hablar o soplar con más control.
- Cable link por wifi para jugar a dobles o intercambiar Pokémon entre dos móviles.
- Botones turbo y combinaciones tipo A+B.
- Copia de las partidas en la nube.
- Skins para los controles.

## Licencias

El código de esta app se publica con licencia [GNU GPL v3](LICENSE). Es la licencia de melonDS, y al llevarlo dentro la app entera tiene que seguirla: cualquiera puede ver, cambiar y compartir el código, siempre que lo que comparta siga siendo libre.

- mGBA, de Vicki Pfau y colaboradores, con licencia [Mozilla Public License 2.0](https://github.com/mgba-emu/mgba/blob/master/LICENSE). Se descarga tal cual desde su repositorio oficial.
- melonDS, del equipo de melonDS, con licencia [GPL v3](https://github.com/melonDS-emu/melonDS/blob/master/LICENSE). También se descarga tal cual. Trae dentro FreeBIOS, teakra, FatFs, xxHash, libslirp, tiny-AES-c y sha1, con licencias permisivas.
- blip_buf (LGPL 2.1) e inih (BSD), que vienen con mGBA.
- Las ROMs de prueba de jsmolka/gba-tests (MIT), ver `native-test/roms/LICENSE-gba-tests`.

Nintendo DS, Game Boy Advance y Game Boy son marcas de Nintendo. Este proyecto no tiene nada que ver con Nintendo.
