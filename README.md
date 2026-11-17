# AdvanceBoy

Emulador de Game Boy Advance, Nintendo DS y Nintendo 3DS para Android. También abre juegos de Game Boy y Game Boy Color.

La emulación de GBA, GB y GBC la hace [mGBA](https://mgba.io) (versión 0.10.5), la de DS, [melonDS](https://melonds.kuribo64.net) (versión 1.1) y la de 3DS, [Azahar](https://azahar-emu.org) (versión 2126.2), que es la continuación de Citra. Son de lo más preciso que hay. Todo lo demás es una app Android escrita en Kotlin: la lista de juegos, los controles táctiles, el audio, la imagen, las partidas y los ajustes.

## Descargar

Desde el móvil, abre este enlace y se descarga siempre la última versión:

https://github.com/JoseLuixrax/gba-emulator/releases/latest/download/AdvanceBoy.apk

Al abrir el archivo, Android te pedirá permiso para instalar apps de fuera de Play Store desde el navegador o el gestor de archivos. Lo das una vez y listo.

Necesita Android 8.0 o superior. Para los juegos de DS hace falta además un móvil de 64 bits, que son casi todos los de los últimos años. Los de 3DS piden bastante más: un móvil ARM de 64 bits con OpenGL ES 3.2 y, a ser posible, de gama media-alta (más detalles en su apartado). Las versiones nuevas se instalan encima de la anterior sin perder las partidas, pero no desinstales la app para actualizar, porque al desinstalar se borran.

## Qué tiene

- Lista de juegos a partir de una carpeta del móvil (`.gba`, `.gb`, `.gbc`, `.nds`, `.3ds`, `.cci`, `.cxi`, `.3dsx`, `.cia` y `.zip`, también en subcarpetas), con los últimos que has jugado arriba.
- Juegos de DS con las dos pantallas, la táctil se usa con el dedo, botones X e Y y un botón para soplar al micrófono, y también con estados, trucos y rebobinado. Más abajo hay un apartado solo para DS.
- Juegos de 3DS con las dos pantallas, la táctil, el stick circular, ZL y ZR, estados y avance rápido. También tienen su apartado más abajo.
- 10 ranuras de estado con miniatura y fecha, más un guardado automático al salir para seguir donde lo dejaste.
- Avance rápido de x2 a x8 y cámara lenta de x0,1 a x0,5. La música va a la misma velocidad que el juego.
- Rebobinar (se activa en ajustes).
- Trucos GameShark, Action Replay y CodeBreaker. En Game Boy, GameShark y Game Genie. En DS, Action Replay DS.
- Controles táctiles que puedes mover y cambiar de tamaño, por separado en vertical y en horizontal.
- Mandos Bluetooth o USB y teclado, con los botones configurables.
- Botones turbo: Turbo A y Turbo B en pantalla (se activan en Ajustes > Controles) y también Turbo A, B, X e Y para el mando. Mientras los mantienes pulsan el botón una y otra vez, a 30, 15, 10 o 7,5 pulsaciones por segundo según elijas.
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
- También se puede usar el micrófono del móvil de verdad (Ajustes > Nintendo DS > Usar el micrófono del móvil), para los juegos en los que hay que hablar o soplar con más control. Android pide el permiso la primera vez. El micrófono solo se enciende mientras el juego está escuchando (el puntito verde de Android sale solo entonces) y se apaga al salir, al abrir el menú o al cambiar de app. Lo que se graba va directo al juego y no se guarda en ningún sitio. El botón MIC sigue funcionando igual.
- Desde el menú del juego puedes cerrar y abrir la tapa de la consola. La mayoría de juegos se ponen en reposo, pero algunos lo usan en la propia partida. También se puede asignar a un botón del mando.
- En Ajustes > Nintendo DS puedes poner tu nombre (hasta 10 letras), el que tendría la consola. Algunos juegos lo enseñan, y se aplica la próxima vez que abras un juego.
- El idioma de los juegos es el del móvil (español, inglés, francés, alemán, italiano o japonés).
- La partida se guarda sola en cuanto el juego termina de escribirla, y también al salir o al cambiar de app.
- El avance rápido y la cámara lenta funcionan igual que en GBA, con la música a la misma velocidad.
- El modo rápido (JIT) viene activado porque hace que los juegos vayan mucho mejor. Si algún juego se cierra solo al abrirlo, prueba a quitarlo en Ajustes > Nintendo DS.
- Los estados funcionan igual que en GBA: 10 ranuras con miniatura (de la pantalla de arriba) y el automático al salir. Van comprimidos, así que ocupan bastante menos que los ~19 MB que mide un estado de DS por dentro. Si tienes un estado de melonDS 1.1 de PC (`.mln`), también se puede cargar: cópialo a `states/<juego>/` con el nombre `slot1.state` (o el número que quieras) y sale en esa ranura.
- Los trucos son códigos Action Replay DS, de los de toda la vida: parejas de números de 8 cifras, una por línea. Si te equivocas al copiarlo, la app te avisa antes de guardarlo.
- El rebobinado en DS guarda una foto del juego cada 6 fotogramas y al mantener el botón « vuelve atrás al doble de velocidad. Una foto de DS pesa mucho más que una de GBA, así que la app guarda solo los cambios entre una y otra y el historial no pasa de unos 100 MB de memoria; según el juego eso da para más o menos segundos. En móviles justos de potencia se nota algo en la velocidad, así que si no lo usas, mejor dejarlo apagado.

## Juegos de 3DS

Salen en la misma lista y se abren igual. No hace falta nada de una consola (ni BIOS, ni firmware, ni claves): Azahar arranca el juego directamente.

- Los juegos tienen que estar **descifrados**. Cuando hagas la copia de tu propio cartucho o de tus descargas con una 3DS, sácala ya descifrada (GodMode9 lo hace). Si el juego está cifrado, la app te lo dice al abrirlo en vez de quedarse en negro.
- Formatos: `.3ds` y `.cci` (cartuchos), `.cxi`, `.3dsx` (homebrew), `.cia` y los comprimidos de Azahar (`.zcci`, `.zcxi`, `.z3dsx`). Un `.cia` no se instala: la app abre directamente el juego que lleva dentro. Los `.cia` que son solo actualizaciones o DLC no se pueden abrir solos, y la app te avisa.
- **Qué móvil hace falta**: un ARM de 64 bits con OpenGL ES 3.2 (casi todos los de los últimos años), y cuanto más potente, mejor. En un gama media-alta de hace un par de años van bien muchos juegos a velocidad completa, pero los más pesados (Pokémon Sol y Luna, Monster Hunter...) pueden ir a tirones en móviles justos. Los móviles con procesador Intel o AMD (algunos Chromebook) no pueden con ellos.
- Los controles: stick circular (lo arrastras con el pulgar), cruceta, A, B, X, Y, L, R, ZL, ZR, START y SELECT. Con mando, el stick izquierdo es el stick circular, el derecho el C-Stick de la New 3DS, y ZL y ZR van en los gatillos L2 y R2 si no les has puesto otro botón. Todo se puede recolocar y cambiar de tamaño, por separado de los de GBA y DS.
- La pantalla de abajo es táctil, igual que en DS.
- El botón ↕ cambia qué pantalla va arriba, y la app se acuerda para la próxima vez.
- En vertical las pantallas van arriba y los controles debajo. En horizontal las dos van en el centro, una encima de la otra.
- En Ajustes > Nintendo 3DS puedes subir la resolución interna (doble o triple). Se ve mucho más nítido, pero pide bastante más al móvil. También está el modo New 3DS (activado), que algunos juegos necesitan.
- El idioma del juego es el del móvil, si el juego lo trae.
- Estados: 10 ranuras más el automático, como en el resto. Un estado de 3DS pesa más (decenas de MB, según el juego) y tarda un poco en guardarse.
- El avance rápido y la cámara lenta funcionan, pero sin música. Rebobinar y trucos no hay en 3DS de momento.
- Los juegos de 3DS se abren en un proceso aparte de la app. Si algo falla dentro del emulador, se cierra solo el juego y no la app entera.
- La primera vez que juegas a algo puede dar algún tirón mientras se preparan los gráficos. Se guardan, así que la siguiente vez va más fluido.

Lo que falta en 3DS: el micrófono, el giroscopio, el modo 3D, las partidas en red y los juegos que necesitan archivos del sistema de la consola (algunos pocos).

## Dónde se guardan las partidas

Todo va en la carpeta de la app:

```
Android/data/com.joseluixrax.gbaemu/files/
  saves/<juego>.sav            partida del juego (GBA y DS)
  states/<juego>/slotN.state   estados guardados (slot0 es el automático)
  states/<juego>/slotN.png     sus miniaturas
  3ds/saves/Azahar/            todo lo de 3DS: partidas (sdmc/), memoria de la consola (nand/) y gráficos ya preparados (shaders/)
```

Las partidas de 3DS siguen la misma estructura que Azahar en PC, así que se pueden pasar de uno a otro copiando la carpeta `sdmc`.

Desde Android 11 los gestores de archivos no pueden entrar en `Android/data`, así que para llegar ahí desde el móvil tienes dos caminos: Ajustes > Archivos > Abrir carpeta de partidas, o la app Archivos de Android, donde aparece "AdvanceBoy" en el menú lateral. Desde el ordenador por USB se entra normal.

Las capturas de pantalla van a la galería, en `Pictures/AdvanceBoy`.

## BIOS

No hace falta. mGBA trae una BIOS propia que va bien con casi todo. Si quieres usar la original (`gba_bios.bin`, 16 KB, sacada de tu consola), se instala desde Ajustes > BIOS.

La app no incluye juegos ni BIOS.

## Compilar

Con [Android Studio](https://developer.android.com/studio): abres la carpeta del proyecto y le das a Run. La primera vez descarga solo el NDK, CMake, el código de mGBA y de melonDS (a `third_party/mgba` y `third_party/melonds`) y el núcleo de Azahar ya compilado para Android (a `third_party/azahar`).

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
    n3ds/                     el 3DS: carga el núcleo de Azahar (libretro), le da OpenGL ES, audio y archivos
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

El 3DS va por otro camino, porque Azahar dibuja con la GPU. Azahar se distribuye como núcleo libretro (una librería con una interfaz estándar para emuladores) y `n3ds/` hace de programa anfitrión: en un hilo propio crea dos contextos de OpenGL ES que comparten texturas, uno para Azahar y otro para la pantalla. Azahar pinta cada frame en una textura y el otro contexto la dibuja en la parte de la pantalla que le dice la app. El audio sale por AAudio y, como en el resto, es lo que marca el ritmo. La ROM no se copia: Azahar la lee a través de un sistema de archivos virtual que va directo al archivo que elegiste (y en un `.cia`, solo a la parte donde está el juego).

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
# 3DS (necesita EGL de Mesa: apt install libegl-dev libegl-mesa0 libgl1-mesa-dri)
curl -LO https://github.com/azahar-emu/azahar/releases/download/2126.2/azahar-libretro-linux-x86_64-2126.2.zip
unzip azahar-libretro-linux-x86_64-2126.2.zip -d third_party/azahar-linux
./build/native-test/n3ds_host_test third_party/azahar-linux/azahar_libretro.so native-test/3ds-roms/test.3dsx build/native-test/out-3ds
```

El primero ejecuta las ROMs de `native-test/roms` ([gba-tests de jsmolka](https://github.com/jsmolka/gba-tests), licencia MIT), comprueba imagen, audio, estados, partidas Flash y SRAM, rebobinado, trucos y velocidades, y guarda una captura de cada una.

El segundo arranca una ROM de DS casera hecha solo para esto (el código está en `native-test/nds-rom` y se genera con `build.py`) y comprueba las dos pantallas, los botones, el audio a cada velocidad, la carga desde archivo, que la partida se guarde bien, el micrófono (la ROM lo lee como un juego, a través del controlador de la pantalla táctil, y se comprueba el silencio, el soplido y lo que llega del móvil), los estados (también los `.mln` de melonDS y que uno roto no estropee la partida), el rebobinado y los trucos Action Replay. La ROM lleva un contador de fotogramas en memoria que se pinta en pantalla, y así se ve si un estado o el rebobinado vuelven de verdad al momento correcto.

El tercero prueba la parte de 3DS con el núcleo de Azahar para Linux, pintando con el OpenGL por software de Mesa. Primero mira cómo se reconocen los archivos (cartuchos, `.cxi`, `.cia` con el juego dentro, cifrados, actualizaciones, archivos cortados) y el sistema de archivos virtual. Luego arranca un homebrew mínimo hecho para esto (`native-test/3ds-rom`, se genera con `build.py`), deja correr 120 frames y comprueba que se pintan, que el juego queda en su sitio de la pantalla, los estados, el reinicio y los botones.

## Pendiente

- Cable link por wifi para jugar a dobles o intercambiar Pokémon entre dos móviles.
- Combinaciones tipo A+B en un solo botón.
- Copia de las partidas en la nube.
- Skins para los controles.

## Licencias

El código de esta app se publica con licencia [GNU GPL v3](LICENSE). Es la licencia de melonDS, y al llevarlo dentro la app entera tiene que seguirla: cualquiera puede ver, cambiar y compartir el código, siempre que lo que comparta siga siendo libre.

- mGBA, de Vicki Pfau y colaboradores, con licencia [Mozilla Public License 2.0](https://github.com/mgba-emu/mgba/blob/master/LICENSE). Se descarga tal cual desde su repositorio oficial.
- melonDS, del equipo de melonDS, con licencia [GPL v3](https://github.com/melonDS-emu/melonDS/blob/master/LICENSE). También se descarga tal cual. Trae dentro FreeBIOS, teakra, FatFs, xxHash, libslirp, tiny-AES-c y sha1, con licencias permisivas.
- blip_buf (LGPL 2.1) e inih (BSD), que vienen con mGBA.
- Azahar, del Azahar Emulator Project (antes Citra Emulator Project), con licencia [GPL v3 o posterior](https://github.com/azahar-emu/azahar/blob/master/LICENSE.txt). La app lleva el núcleo libretro para Android que publica el propio proyecto en su [release 2126.2](https://github.com/azahar-emu/azahar/releases/tag/2126.2), sin tocar; el código fuente de esa versión está en esa misma etiqueta de su repositorio. Dentro lleva dynarmic, Boost, fmt, cryptopp, zstd, teakra, glslang y otras librerías libres.
- `libretro.h`, de libretro (MIT).
- Las ROMs de prueba de jsmolka/gba-tests (MIT), ver `native-test/roms/LICENSE-gba-tests`.

Nintendo 3DS, Nintendo DS, Game Boy Advance y Game Boy son marcas de Nintendo. Este proyecto no tiene nada que ver con Nintendo.
