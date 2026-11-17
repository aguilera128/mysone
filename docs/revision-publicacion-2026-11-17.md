# Revisión antes de publicar como código libre (17-nov-2026)

Estado tras preparar el repositorio para publicarlo con licencia GPL-3.0, con la app en inglés y español y la nueva
bienvenida. Nada de esto está en un commit todavía.

## Comprobado

- `assembleDebug assembleRelease testDebugUnitTest lint`: compila, 172 tests sin fallos y lint sin errores
  (23 avisos, abajo).
- La bienvenida en inglés (9 pasos) y la app en español con el idioma por app de Android, en el emulador
  `Pixel_7_Pro_API_34`. «Configurar la IA» abre los ajustes de IA dentro de la bienvenida y «Atrás» vuelve al paso 8.
- Las 589 cadenas tienen el mismo nombre y los mismos marcadores en `values/` y `values-es/`, también en cada
  cantidad de los plurales.
- No quedan rutas de la máquina del autor, nombres de dispositivo ni rutas del scratchpad en el código ni en `docs/`.

## Corregido en esta revisión

1. **Idioma de Canary.** Antes salía del idioma de la interfaz: alguien que habla español con el móvil en inglés
   transcribía en inglés.
   - Ahora sale del ajuste nuevo **Ajustes › Escucha › Idioma en que hablas**. Por defecto toma el idioma del
     sistema, no el de la app (`canaryLanguage`, `phoneLocale` en `asr/Transcriber.kt`).
   - El reconocedor guarda el idioma con el que se creó. Si cambia el ajuste, se recarga entre dos trozos, igual
     que al cambiar de modelo.
   - La «ñ» se repara según ese idioma guardado, no según el idioma actual.
2. **Texto del recordatorio rápido en inglés.** Prometía «let me know…», que no lo detecta. Ahora dice
   «don’t let me forget…».
3. **Fechas.** Con el teléfono en un idioma que la app no tiene (alemán), la app sale en inglés pero las fechas
   salían en alemán. Ahora `Time.uiLocale()` usa en/es, o inglés para cualquier otro idioma.
4. **IA falsa (`tools/mock-llm.mjs`).** Ahora detecta también «remind me» y «don't let me forget», así que el
   recordatorio rápido en inglés ya se puede probar.
5. **`.gitignore`.** Añadidos `.agents/` y `skills-lock.json`: son skills de terceros con licencia propia y no
   deben entrar en el repositorio GPL. `.claude/` ya estaba ignorado.
6. **Acerca de.** Nueva línea «Mysone: software libre bajo la GNU GPL v3», con enlace al código fuente
   (`SOURCE_URL` en `AboutScreen.kt`).
7. **Documentación.**
   - README: el texto real del botón «Resume» y lo que se ha probado de verdad.
   - `docs/investigacion/04`: quitadas dos rutas del scratchpad.

## Sin verificar

- **Canary en inglés, alemán o francés con la autoprueba.** La autoprueba transcribe `assets/selftest_es.wav`, que
  está en español. Si Canary, configurado en otro idioma, devuelve vacío con ese audio, la escucha no arranca
  («asr_error_empty»). No se pudo probar: el emulador se quedó sin espacio para instalar el APK.
  - Probar en Ajustes › Transcripción › Probar con el idioma en English.
  - Si falla, añadir un clip de autoprueba por idioma, o aceptar como válida la autoprueba si devuelve cualquier
    texto.
- Lo que ya figuraba en `AGENTS.md`: móvil real durante días, un proveedor de IA real, la pantalla de bloqueo con PIN
  y Android 17.

## Pendiente, no bloquea

- **La hora siempre sale en 24 h** (`Time.hourMinute`). En inglés de EE. UU. el selector de hora de Tareas sigue
  el formato de 12 h del teléfono, y la lista muestra «18:00». Arreglarlo exige pasar el `Context` a los formatos
  de hora.
- **Avisos de lint:**
  - 8 `MissingQuantity`: plurales en español sin `many`. Android solo lo usa con números enormes.
  - 3 `PluralsCandidate`.
  - Versiones nuevas de dependencias y de AGP: AGP 9.1.1 es el último que sincroniza el Android Studio instalado.
- **Documentación técnica.** `docs/` está solo en español. El README en inglés lo avisa.

## Decisiones antes de hacer público el repositorio (del autor)

- **Historial.** Los 107 commits llevan el correo personal del autor. Se decidió conservar el historial. Si no se
  quiere ese correo público, hay que reescribirlo antes de publicar con `git filter-repo --mailmap`, porque después
  ya no se puede.
- **Firma.** La release se firma con la clave debug de la máquina que compila. Para publicar APKs en GitHub Releases
  hace falta una clave de release propia, fuera del repositorio. Sin ella, cada actualización obliga a desinstalar.
- **F-Droid y builds reproducibles.** sherpa-onnx llega por JitPack y trae binarios nativos precompilados. F-Droid
  pediría compilarlos desde el código fuente.
- **CI.** Hecho después: `.github/workflows/ci.yml` (build, tests unitarios y lint en cada push y PR).
- **GitHub.** Hacerlo público, poner descripción y temas, y activar *Private vulnerability reporting*, que es a
  donde apunta la sección de seguridad del README.
