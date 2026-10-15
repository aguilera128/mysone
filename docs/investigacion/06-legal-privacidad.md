# 06 — Marco legal y de privacidad (España / UE) para una app que escucha y transcribe todo el día

Investigación del 15-oct-2026. **No es asesoramiento jurídico.** Resume normas, jurisprudencia y criterios de autoridades con sus fuentes; donde no he podido leer el texto original de una sentencia lo digo. Para un uso que pueda acabar en un juzgado (aportar grabaciones como prueba, grabar a clientes), consultar a un abogado.

Supuesto de partida: app Android instalada por sideload, para uso del propio dueño (persona física en España, autónomo que trabaja con clientes), que capta audio ambiente de forma continua, transcribe, resume, extrae tareas y permite preguntar. Capta inevitablemente voces de terceros.

---

## 0. Conclusiones en una página

1. **Grabar conversaciones en las que el dueño participa es lícito en España** (no vulnera el secreto de las comunicaciones): STC 114/1984, FJ 7, y jurisprudencia constante del Supremo (STS 298/2013, 45/2014, 652/2016, 145/2023). Límites: no provocar ni engañar para obtener manifestaciones, y cuidado con el núcleo íntimo del otro.
2. **Grabar conversaciones ajenas (en las que el dueño no participa) es el riesgo serio**: el TC dice que vulnera el art. 18.3 CE y el art. 197.1 CP lo castiga con 1–4 años de prisión. Condenas recientes por grabar conversaciones ajenas **en contexto familiar**: STS 891/2025 (madre que grabó la llamada del padre con los hijos) y una STS de septiembre de 2026 (mujer que grabó a su marido con sus suegros en vacaciones). Una app "siempre encendida" capta justo eso: la pareja hablando por teléfono, la mesa de al lado, el móvil olvidado grabando en una habitación. **La salvaguarda técnica clave es no conservar lo que no sea una conversación del dueño.**
3. **Difundir es peor que grabar**: aunque grabar la propia conversación sea lícito, su difusión puede vulnerar la intimidad (LO 1/1982, art. 7; indemnización con daño moral presumido) y saca el tratamiento de la exención doméstica del RGPD. La app no debe facilitar compartir transcripciones sin fricción.
4. **RGPD**: el uso exclusivamente personal o doméstico está fuera del RGPD (art. 2.2.c y considerando 18), pero la exención **se interpreta de forma estricta** (Ryneš, Lindqvist, Buivids; EDPB, declaración sobre gafas inteligentes de 13-oct-2026). **Deja de aplicar** en cuanto la app se usa en reuniones de trabajo con clientes, si se publica o difunde lo grabado y, discutiblemente, si capta de forma continua el espacio público (analogía con Ryneš). Y el RGPD **sí se aplica siempre** a quien proporciona los medios (proveedor de la API de IA; el propio desarrollador si algún día distribuye la app).
5. **Transferencias**: mandar transcripciones con voces de terceros a EEUU es posible con proveedores adheridos al Data Privacy Framework (válido hoy: T-553/23 lo confirmó el 3-sep-2025; recurso C-703/25 P pendiente en el TJUE, riesgo de caída). **A China (DeepSeek), no**: sin decisión de adecuación, la política de DeepSeek dice que almacena en la RPC, no menciona cláusulas tipo, y la autoridad de Berlín ya declaró que sus transferencias violan el art. 46 RGPD. Por defecto: procesado local; nube solo opt-in, pseudonimizada, con proveedor UE o DPF.
6. **AI Act**: no impone obligaciones al usuario particular (art. 2.10). Lo que sí importa: **no implementar reconocimiento de emociones** (prohibido en el trabajo, art. 5.1.f, y de alto riesgo en general) ni **identificación biométrica de terceros por la voz**. Los plazos de alto riesgo se retrasaron al 2-dic-2027 (Reglamento (UE) 2026/1744, "Digital Omnibus on AI").
7. **El mercado lo confirma**: Limitless (comprada por Meta) **cortó el servicio en la UE** en diciembre de 2025; Bee (Amazon) vende por Amazon.com sin mencionar la UE, no guarda audio y hace que el usuario pida permiso en cada llamada; Plaud traslada toda la responsabilidad del consentimiento al usuario. Nadie ha resuelto el problema de los terceros: lo descargan en el usuario.

---

## 1. Derecho español sobre grabar conversaciones

### 1.1 Constitución y Tribunal Constitucional: STC 114/1984

- **Art. 18.3 CE** (secreto de las comunicaciones) y **art. 18.1 CE** (intimidad) son derechos distintos.
- **STC 114/1984, de 29 de noviembre**, FJ 7: el secreto protege la comunicación frente a terceros, no frente al interlocutor. Frases clave: «No hay "secreto" para aquél a quien la comunicación se dirige»; quien graba una conversación con otro «no incurre, por este solo hecho» en la vulneración del 18.3; en cambio, **«Quien graba una conversación de otros atenta»** contra ese derecho. El concepto de secreto es «formal»: se protege lo comunicado sea cual sea su contenido.
- FJ 7: la posible lesión de la **intimidad (18.1)** no está en grabar, sino en **difundir**; la grabación es como mucho un acto preparatorio.
- FJ 8: no existe un «derecho a la voz» autónomo; la voz ajena se protege como intimidad cuando se utiliza «ad extra y no meramente registrada».
- Fuente: https://hj.tribunalconstitucional.es/es/Resolucion/Show/367

### 1.2 Tribunal Supremo: grabación por un interlocutor

Doctrina consolidada (resumen de fuentes doctrinales; no he leído las sentencias completas en CENDOJ):

- **STS 298/2013, de 13 de marzo**; **STS 45/2014, de 7 de febrero**; **STS 421/2014**; **STS 517/2016**; **STS 652/2016, de 15 de julio**; **STS 736/2022**: la grabación por uno de los interlocutores **no vulnera el secreto de las comunicaciones** y, como regla, tampoco la intimidad, «salvo casos excepcionales» en que el contenido afecte al núcleo íntimo del otro. Se admite como prueba (corroboración), no como confesión.
- **Límites**: que no haya **provocación, engaño o coacción** para inducir manifestaciones; que no se obtenga desde una posición de superioridad (p. ej. policial) para arrancar una confesión.
- **STS 145/2023, de 2 de marzo** (pte. Del Moral): un tercero que graba **con el consentimiento de uno de los interlocutores** produce prueba válida; lo ilícito es la grabación por un tercero sin la aprobación de ningún comunicante (ni del juez). (Fuente secundaria; verificar en CENDOJ.)
- Fuentes: https://www.legaltoday.com/practica-juridica/derecho-publico/derecho-administrativo/las-grabaciones-de-una-conversacion-entre-dos-personas-sin-el-consentimiento-y-conocimiento-del-otro-segun-la-jurisprudencia-2023-03-09/ · https://www.economistjurist.es/actualidad-juridica/la-grabacion-oculta-de-una-conversacion-por-un-tercero-es-licita-si-cuenta-con-el-consentimiento-de-uno-de-los-interlocutores/ · https://www.reclamador.es/blog/es-legal-grabar-una-conversacion-telefonica

### 1.3 Grabar conversaciones ajenas: art. 197 del Código Penal

Texto literal vigente (BOE, consolidado, https://www.boe.es/buscar/act.php?id=BOE-A-1995-25444#a197):

- **197.1**: «El que, para descubrir los secretos o vulnerar la intimidad de otro, sin su consentimiento, [...] utilice artificios técnicos de escucha, transmisión, grabación o reproducción del sonido o de la imagen, o de cualquier otra señal de comunicación, será castigado con las penas de prisión de uno a cuatro años y multa de doce a veinticuatro meses.»
- **197.3**: prisión de 2 a 5 años «si se difunden, revelan o ceden a terceros los datos o hechos descubiertos» (se refiere a lo obtenido ilícitamente según los números anteriores).
- **197.5**: penas en su mitad superior si afecta a datos que revelen «ideología, religión, creencias, salud, origen racial o vida sexual», o si la víctima es **menor** o persona con discapacidad necesitada de especial protección.
- **197.7**: castiga difundir sin autorización «imágenes o grabaciones audiovisuales» obtenidas **con** la anuencia del afectado en un domicilio o lugar fuera de la mirada de terceros, cuando la divulgación menoscabe gravemente su intimidad (3 meses a 1 año o multa). **Incertidumbre**: el texto dice «audiovisuales»; si alcanza a una grabación solo de audio es discutible.
- **201.1**: estos delitos requieren **denuncia de la persona agraviada** (salvo menores, pluralidad de personas o interés general, 201.2); el perdón extingue la acción (201.3).

Casos recientes relevantes para una app de escucha continua (todos en el ámbito familiar, que es justo donde la exención doméstica del RGPD no protege frente al Código Penal):

- **STS 891/2025, de 30 de octubre (ECLI:ES:TS:2025:5025)**: la madre grabó la llamada del padre con sus hijos (ella no era interlocutora) y envió el audio a un grupo de WhatsApp de 23 padres. El TS confirma el **197.1** con la agravación por minoría de edad y eleva la pena (≈ 2 años y 6 meses según la fuente; varía entre reseñas). No aplica el 197.3 porque no se acreditó la intención de difundir (dijo que lo envió por error y lo borró). Fuentes: https://www.iberley.es/noticias/el-ts-condena-una-madre-2-anos-carcel-difundir-un-grupo-whatsapp-padres-una-conversacion-sus-hijos-su-exmarido-35720 · https://www.legaltoday.com/practica-juridica/derecho-penal/el-ts-respalda-a-un-padre-que-denuncio-a-su-mujer-por-grabar-una-llamada-con-sus-hijos-menores-de-edad-2025-12-01/
- **STS de septiembre de 2026** (número no verificado): confirma la condena (Juzgado de lo Penal de Benidorm y AP de Alicante: 1 año de prisión, multa e inhabilitación) a una mujer que grabó a escondidas una conversación entre su marido y los padres de este durante unas vacaciones y la aportó como prueba en un procedimiento de violencia de género. Que se use como prueba no la hace lícita. Fuente: https://www.diarioconstitucional.cl/2026/09/24/tribunal-supremo-de-espana-confirma-condena-a-mujer-que-grabo-conversacion-privada-y-la-aporto-como-prueba-en-juicio/

**Incertidumbre importante**: el 197.1 exige un elemento subjetivo («para descubrir los secretos o vulnerar la intimidad»). Una captación **incidental** por un dispositivo siempre encendido no es lo mismo que colocar una grabadora para espiar, y no he encontrado jurisprudencia española sobre asistentes de grabación continua. Pero si la app **conserva** esa conversación ajena y luego permite **consultarla** ("¿qué dijo mi pareja por teléfono ayer?"), la diferencia con espiar se diluye. Por eso la salvaguarda no es "avisar", sino **no retener** lo ajeno.

### 1.4 Vía civil: LO 1/1982 (honor, intimidad e imagen)

Texto en https://www.boe.es/buscar/act.php?id=BOE-A-1982-11196

- **Art. 7.1**: intromisión ilegítima colocar aparatos de escucha o grabación para grabar o reproducir «la vida íntima de las personas».
- **Art. 7.2**: usar aparatos de escucha «para el conocimiento de la vida íntima de las personas» o de manifestaciones privadas no destinadas a quien los usa, y su grabación o reproducción.
- **Art. 7.3**: divulgar hechos de la vida privada que afecten a la reputación.
- **Art. 2.2–2.3**: no hay intromisión si hay consentimiento expreso; es revocable.
- **Art. 9.3**: acreditada la intromisión **se presume el perjuicio** e incluye daño moral; **art. 9.5**: las acciones caducan a los **4 años**.

Consecuencia práctica: compartir una transcripción con terceros (aunque la conversación fuera propia) puede costar una indemnización aunque no haya delito.

### 1.5 Matriz de escenarios (orientativa)

| Escenario | Penal (197 CP) | Civil (LO 1/1982) | RGPD | Qué debe hacer la app |
|---|---|---|---|---|
| Conversación presencial en la que participa el dueño (amigos, familia) | Lícito (STC 114/1984) | Lícito salvo difusión o núcleo íntimo | Exención doméstica | Conservar transcripción; sin audio |
| Llamada propia por altavoz | Lícito | Igual | Exención doméstica si es personal | Recordatorio "avisa de que lo apuntas" (como Bee Eclipse) |
| Reunión de trabajo con un cliente | Lícito como interlocutor | Igual | **Aplica el RGPD**: el dueño es responsable | Modo trabajo: aviso al cliente, base jurídica, nube solo con DPA |
| Pareja o hijos hablando entre ellos o por teléfono, dueño no participa | **Riesgo alto** (STS 891/2025) | Riesgo | — | **Descartar** (filtro de participación del dueño) |
| Mesa de al lado en un bar o tren | Riesgo (conversación ajena) | Riesgo | Posible pérdida de la exención (espacio público, analogía Ryneš) | Descartar |
| Móvil olvidado grabando en una sala | **Riesgo alto** (equivale a dejar una grabadora) | Art. 7.1 | — | Pausa automática si no se oye al dueño |
| TV, radio, megafonía | Sin riesgo relevante | — | — | Descartar por utilidad |
| Médico, abogado, colegio, vestuarios | Lícito si participa, pero datos especialmente sensibles (197.5) | Riesgo alto si se difunde | Art. 9 si aplica el RGPD | Geovallas de pausa |

"Participar" en una conversación de grupo en la que el dueño está presente pero callado es una **zona gris**: no he encontrado criterio judicial claro.

---

## 2. RGPD y LOPDGDD

### 2.1 La exención doméstica

- **Art. 2.2.c RGPD**: no se aplica al tratamiento «efectuado por una persona física en el ejercicio de actividades exclusivamente personales o domésticas».
- **Considerando 18**: «sin conexión alguna con una actividad profesional o comercial»; ejemplos: correspondencia, agenda de direcciones, redes sociales en ese contexto. Y añade: «No obstante, el presente Reglamento se aplica a los responsables o encargados del tratamiento que proporcionen los medios para tratar datos personales relacionados con tales actividades personales o domésticas».
- La **LOPDGDD** (LO 3/2018) excluye igualmente los tratamientos fuera del RGPD por su art. 2.2 (art. 2.2.a LOPDGDD).
- Textos: https://www.boe.es/buscar/doc.php?id=DOUE-L-2016-80807 · https://www.boe.es/buscar/act.php?id=BOE-A-2018-16673

### 2.2 Cuándo deja de aplicar (y por qué importa aquí)

- **Interpretación estricta**: TJUE **Ryneš (C-212/13, 11-dic-2014)**: una cámara doméstica que graba de forma continua y cubre, aunque sea parcialmente, la vía pública **no** es actividad exclusivamente doméstica. **Lindqvist (C-101/01, 6-nov-2003)**: publicar datos en internet accesibles a un número indefinido de personas no es doméstico. **Buivids (C-345/17)**: publicar un vídeo en YouTube, igual. **Jehovan todistajat (C-25/17)**: recogida de datos que se hacen accesibles a terceros no es doméstica.
- **EDPB, declaración sobre gafas inteligentes, adoptada el 13-oct-2026** (lo más reciente y aplicable por analogía directa a un dispositivo que capta audio ambiente), párr. 13–18:
  - El usuario no está sujeto al RGPD si actúa en un ámbito exclusivamente personal (p. ej. traducción personal en un contexto privado).
  - La exención «must, however, be narrowly construed» caso por caso; no aplica si se publica a un número indefinido de personas o si el dispositivo se usa en actividades «official, professional, political or other non-personal». Entonces el usuario es **responsable** y debe cumplir todo, incluida la transparencia.
  - El usuario puede estar sujeto además a otras leyes, «some uses may be subject to criminal law» (párr. 15).
  - El RGPD **sí aplica** al fabricante y al proveedor del servicio de IA vinculado; **no pueden escudarse en que el usuario avise** a los terceros (párr. 16–18).
  - Advierte expresamente de micrófonos que captan «background conversations to which the user is not participating» (párr. 7).
  - Medidas por defecto: procesado en el dispositivo, seudonimización o anonimización antes de compartir, **almacenamiento persistente de audio desactivado por defecto** cuando la función principal es asistencia en tiempo real, **geovallas** en lugares sensibles y «privacy frictions» antes de compartir.
  - Fuente primaria: https://www.edpb.europa.eu/system/files/2026-10/20261310_edpb_statement_smart-glasses_en.pdf · nota: https://www.edpb.europa.eu/news/edpb-publishes-statement-on-the-data-protection-challenges-of-smart-glasses_en

Aplicado a esta app:

| Uso | ¿Exención doméstica? |
|---|---|
| Vida privada, conversaciones propias, sin difundir | Sí (razonable) |
| Reuniones, llamadas o visitas con **clientes** (el dueño es autónomo) | **No**: actividad profesional |
| Compartir transcripciones fuera del círculo íntimo, publicarlas | **No** |
| Captación continua de desconocidos en la calle o el transporte | **Dudoso**: Ryneš lo excluyó para vídeo continuo que cubre la vía pública; no hay sentencia para audio |
| El desarrollador distribuye la app a otros (GitHub, Play) | El desarrollador pasa a estar sujeto (considerando 18) |

### 2.3 Si aplica el RGPD (modo trabajo)

- El dueño es **responsable del tratamiento** de los datos de sus clientes.
- **Base jurídica** (art. 6): interés legítimo (6.1.f) con ponderación documentada, o consentimiento. Consentimiento a posteriori no vale (EDPB, declaración citada, párr. 29: «the legal basis must exist prior to and during any processing»).
- **Informar** al cliente en el momento de la recogida (art. 13): quién, para qué, a qué proveedores va, cuánto tiempo y qué derechos tiene.
- **Categorías especiales** (art. 9.1): «datos biométricos dirigidos a identificar de manera unívoca a una persona física», salud, etc. Una huella de voz de un tercero para reconocerlo es dato biométrico (art. 4.14) y exige una excepción del art. 9.2 (en la práctica, consentimiento explícito).
- **Encargados** (art. 28): contrato o DPA con el proveedor de IA. **Transferencias** (cap. V): ver §3.
- **Seguridad** (art. 32) y **protección de datos desde el diseño y por defecto** (art. 25).
- **Evaluación de impacto** (art. 35): probablemente exigible para monitorización continua con IA y datos de terceros. No lo he contrastado con la lista concreta de la AEPD.
- Derechos de los terceros (acceso, supresión, oposición del art. 21): la app debe permitir localizar y borrar todo lo de una persona.

### 2.4 Voz y biometría: criterios del EDPB para asistentes de voz

**EDPB Guidelines 02/2021 on Virtual Voice Assistants, v2.0 (7-jul-2021)**, https://www.edpb.europa.eu/system/files/2021-07/edpb_guidelines_202102_on_vva_v2.0_adopted_en.pdf

- Párr. 30: las **activaciones accidentales** difícilmente tienen base jurídica; si se detectan, **borrar**.
- Párr. 31: «voice data is inherently biometric personal data».
- Párr. 130–131: activar el reconocimiento de la voz del usuario implica **procesar biométricamente a los no registrados** que hablan alrededor.
- Párr. 132: preferir soluciones basadas **solo en los datos del propio usuario**.
- Párr. 133: los modelos de voz deben generarse, guardarse y compararse **exclusivamente en el dispositivo**.
- Párr. 136: no analizar la voz para inferir estado mental, enfermedades o circunstancias vitales.
- Párr. 137–139: minimización por defecto; considerar tecnologías que **eliminen las voces de fondo**.

Traducción a diseño: verificación de voz **solo del dueño**, plantilla local y cifrada; para terceros, como mucho diarización anónima ("Hablante 2"), sin crear huellas persistentes; el nombre lo pone el dueño a mano si quiere.

---

## 3. Transferencias de transcripciones con datos de terceros

### 3.1 Principio

Si el uso es doméstico, el capítulo V del RGPD no vincula al dueño. Pero el proveedor que recibe los datos sí está sujeto (considerando 18; EDPB párr. 16–18), y en cuanto haya una conversación de trabajo el dueño sí queda obligado. Como la app no puede saber de antemano qué fragmento es doméstico y cuál profesional, **conviene diseñar como si siempre aplicara**.

### 3.2 Estados Unidos

- **Decisión de adecuación UE-EEUU (Data Privacy Framework)** del 10-jul-2023. El Tribunal General la confirmó en **Latombe v Comisión, T-553/23, 3-sep-2025**. **Recurso de casación C-703/25 P pendiente** ante el TJUE (Microsoft admitida como coadyuvante de la Comisión el 4-jun-2026; sin fecha de vista conocida en julio de 2026). noyb ha anunciado otra impugnación. Riesgo de un "Schrems III": diseñar para poder cambiar de proveedor.
  - https://www.dataguidance.com/news/eu-cjeu-admits-microsoft-intervener-appeal-concerning · https://btlj.org/2026/02/third-times-the-charm-the-fate-of-the-eu-u-s-data-privacy-framework/ · https://edpl.lexxion.eu/article/EDPL/2026/1/15
- Solo vale para empresas **adheridas**: comprobar al proveedor concreto en https://www.dataprivacyframework.gov/list (no lo he comprobado para Anthropic ni OpenAI).
- Opciones con menos exposición (detalle en `05-llm-coste.md`): OpenAI ofrece residencia de datos en Europa para proyectos nuevos de clientes elegibles (https://openai.com/index/introducing-data-residency-in-europe/), con dudas sobre si la retención cero es automática o requiere aprobación. Mistral (UE) aloja en la UE por defecto, según fuentes de terceros. Según el informe 05, la API de primera parte de Anthropic no tiene opción de residencia en la UE.

### 3.3 China (DeepSeek)

- **Sin decisión de adecuación** de la Comisión para China.
- Política de privacidad de DeepSeek (actualizada el 10-feb-2026): «we directly collect, process and store your Personal Data in People's Republic of China»; los datos de entrada pueden usarse para entrenar (opt-out por solicitud); representante en la UE: Prighter; **no menciona cláusulas contractuales tipo**. https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html
- **Autoridad de Berlín** (BlnBDI): requerimiento el 6-may-2025 y aviso a Apple y Google por la vía del art. 16 DSA el 27-jun-2025, por transferir datos a China sin garantías adecuadas (**art. 46.1 RGPD**). **Garante italiano**: bloqueo en enero de 2025. Otras investigaciones en Francia, Países Bajos, Luxemburgo y Portugal. https://regulations.ai/enforcement/enf-germany-hangzhoudeepseek-20250627 · https://www.techradar.com/computing/cyber-security/deepseek-faces-ban-in-germany-as-privacy-watchdog-reports-the-app-to-google-and-apple-as-illegal-content
- Algunas webs de 2026 hablan de nuevas prohibiciones de DeepSeek (incluida su API); **no he encontrado fuente oficial** que lo confirme.
- **Recomendación**: no enviar a la API oficial de DeepSeek transcripciones con voces de terceros. Coincide con el informe 05: solo con aceptación explícita del dueño, nunca por defecto. Si se quiere ese modelo, usarlo con pesos abiertos en local o en un proveedor de la UE (no verificado qué proveedores lo sirven en la UE).

### 3.4 Otros "terceros" que pasan desapercibidos

- **Sentry** (obligatorio según la nota global del dueño): ningún contenido de transcripción, título ni resumen debe ir en eventos, breadcrumbs ni mensajes de error. `sendDefaultPii: false` y filtrado en `beforeSend`. Comprobar la opción de región de datos en la UE de Sentry (no verificado aquí).
- **Copias de seguridad de Android/Google** (Auto Backup): excluir la base de datos y las claves, o el contenido acaba en Google Drive.
- **Teclados, accesibilidad y notificaciones** que se reflejan en el reloj o el coche: no poner texto de transcripciones en notificaciones.

---

## 4. AI Act (Reglamento (UE) 2024/1689)

- **Art. 2.10**: el Reglamento «does not apply to obligations of deployers who are natural persons using AI systems in the course of a purely personal non-professional activity». La propia definición de *deployer* (art. 3.4) excluye el uso personal no profesional. Usando la app en el trabajo, el dueño sí es *deployer*. https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-2
- **El dueño también es "proveedor"** en sentido técnico: desarrolla el sistema y lo **pone en servicio para uso propio** (arts. 3.3 y 3.11). Si el sistema no es de alto riesgo ni prohibido, sus obligaciones son mínimas: art. 4 sobre alfabetización en IA, reformulado por el Omnibus como deber de «take measures to support» sin garantizar un nivel; y art. 50.
- **Art. 50** (aplicable desde el 2-ago-2026): 50.1, avisar de que se interactúa con una IA, salvo que sea «obvious» (lo es para el dueño); 50.2, marcado legible por máquina del texto sintético, con excepciones para funciones de edición asistida. En uso propio el impacto es residual, pero **es una incertidumbre** si algún día se distribuye. 50.3: quien despliegue **reconocimiento de emociones o categorización biométrica** debe informar a las personas expuestas. https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-50
- **Art. 5.1.f** (prohibición vigente desde el 2-feb-2025): sistemas que infieren emociones en el **lugar de trabajo** y en centros educativos, salvo por motivos médicos o de seguridad. Un "análisis del tono de la reunión con el cliente" entraría ahí. **No implementarlo.**
- **Anexo III** (alto riesgo): identificación biométrica remota, categorización biométrica y reconocimiento de emociones. **Digital Omnibus on AI, Reglamento (UE) 2026/1744** (en vigor el 27-jul-2026): retrasa las obligaciones del Anexo III al **2-dic-2027** y añade el art. 4a (datos sensibles para detectar sesgos). **No toca la exención del art. 2.10.** https://artificialintelligenceact.eu/ai-act-explorer/digital-omnibus/ · https://www.gibsondunn.com/eu-ai-act-omnibus-agreement-postponed-high-risk-deadlines-and-other-key-changes/
- **Omnibus digital de datos (reforma del RGPD)**: en negociación; el Consejo rechazó redefinir "dato personal". No he encontrado cambios propuestos a la exención doméstica. https://edri.org/wp-content/uploads/2026/03/EDRi-Data-Omnibus-Analysis-Council-Compromise-text.pdf

Conclusión AI Act: con transcripción, resúmenes, tareas y preguntas, la app no es de alto riesgo ni prohibida. Lo que la metería en problemas son tres funciones que **no hay que construir**: emociones, identificación de terceros por la voz e inferencias sobre la salud o la personalidad de terceros.

---

## 5. Qué hacen Bee, Limitless y Plaud

| | Bee (Amazon) | Limitless (Meta) | Plaud |
|---|---|---|---|
| Producto (oct-2026) | **Bee Frontier**: pulsera o clip, 74,99 $, a la venta. **Bee Eclipse**: tarjeta magnética para el móvil, 99,99 $, «coming soon» (anunciado el 15-oct-2026). Bee Pro 11,99 $/mes; gratis: resúmenes y **14 días de memoria** | Pendant. Comprada por Meta (anuncio del 9-dic-2025); dejó de venderse a clientes nuevos | Grabadoras (NotePin, Note) y apps |
| Audio | «No audio is ever stored»: se procesa en tiempo real y se borra | Graba audio, transcripción y resumen | Se graba localmente y se sube a la nube con el consentimiento del usuario |
| Terceros | Eclipse: **pide al usuario permiso de los participantes cada vez que empieza a grabar una llamada**. En 2025 prometió grabar solo voces que hubieran consentido; no consta que lo hiciera | "Consent Mode" por voz (anunciado; sin pruebas independientes). Términos: el usuario **debe avisar y obtener consentimiento**; si alguien no consiente, apagar; si grabaste una conversación ajena, **borrarla de inmediato** | El usuario es responsable de informar y obtener los consentimientos; Plaud **no recoge consentimientos** por él |
| Indicador | LED visible cuando procesa; Frontier se apaga tras 5 o 15 min de silencio | LED blanco; taparlo viola los términos (y no basta con él) | — |
| Procesado y cifrado | "Bee Private Compute": entorno aislado al que Amazon dice no tener acceso; claves creadas y controladas en el dispositivo; borrado de transcripciones cuando el usuario quiera | Puede usar los datos para mejorar el servicio y **entrenar modelos de terceros** | AES-256 en reposo, TLS 1.2+; sin entrenamiento por defecto (opt-in); dice alojar los datos de la UE en AWS Frankfurt (un competidor afirma que la política dice EEUU: sin resolver) |
| UE | Solo Amazon.com; no menciona la UE | **Cortó el servicio en la UE, el Reino Unido, Brasil, Israel, Corea del Sur, Turquía y China**; exportación hasta el 19-dic-2025 | Vende en la UE; se declara "GDPR compliant" (afirmación propia) |

Fuentes: https://www.aboutamazon.com/news/devices/amazon-personal-ai-bee-frontier-bee-eclipse · https://bee.computer/products/eclipse · https://www.cbsnews.com/news/amazon-personal-ai-wearable-devices-bee/ · https://bgr.com/2079772/amazon-bee-ai-gadget-privacy-problems/ · https://help.limitless.ai/en/articles/10540861-how-to-ask-for-consent-and-let-others-know-you-are-recording · https://help.limitless.ai/en/articles/13004190-talking-to-someone-wearing-the-pendant-what-to-expect-and-how-we-handle-your-information · https://techinformed.com/meta-acquires-limitless-pendant-users-moved-to-free-unlimited-plan/ · https://kulturegeek.fr/news-343366/meta-rachete-limitless-met-fin-service-europe-lia-pression-reglementaire · https://support.plaud.ai/hc/en-us/articles/52645336070681-Do-I-need-to-obtain-consent-before-recording-with-Plaud-devices-or-Apps · https://support.plaud.ai/hc/en-us/articles/52645355064217-Does-Plaud-obtain-recording-consent-on-my-behalf · https://www.plaud.ai/pages/trust · https://www.sally.io/blog/plaud-gdpr-data-protection

Lecciones: (1) **no guardar audio** es el estándar de mercado; (2) el consentimiento de terceros nadie lo automatiza de forma fiable, todos lo cargan al usuario, y el EDPB dice que eso no basta para el proveedor; (3) un recordatorio de consentimiento **por llamada** (Bee Eclipse) es barato y útil; (4) la retención limitada por defecto (14 días en el plan gratuito de Bee) es razonable.

---

## 6. Salvaguardas que la app debe implementar

Orden: primero lo imprescindible. Entre corchetes, el fundamento.

### 6.1 Imprescindibles (v1)

1. **Aviso inicial obligatorio** (onboarding) con aceptación expresa y fecha guardada: qué capta, que capta a terceros, qué es lícito y qué no (§7.1). Sin aceptarlo no arranca la escucha. [Responsabilidad proactiva; prueba de diligencia]
2. **Notificación persistente** mientras escucha: es obligatoria en Android para un servicio en primer plano de tipo `microphone` (`FOREGROUND_SERVICE_MICROPHONE` + `RECORD_AUDIO`). Texto claro ("Escuchando: se transcribe y se borra el audio") y acciones **Pausar 15 min / Pausar 1 h / Detener**. No ocultarla ni minimizar su importancia. El indicador de micrófono del sistema (Android 12+) no se puede suprimir: no intentarlo. Nota técnica: Android no deja **arrancar** el servicio de micrófono desde segundo plano ni al arrancar el sistema (`BOOT_COMPLETED`), salvo excepciones. Tras reiniciar, el usuario debe abrir la app, lo que de paso es una salvaguarda. [https://developer.android.com/develop/background-work/services/fgs/service-types · https://source.android.com/docs/core/permissions/privacy-indicators]
3. **Pausa rápida en un toque**: acción en la notificación, *tile* de Ajustes rápidos y widget. Pausa temporizada que se reanuda sola y lo avisa. Botón **"Olvida los últimos 5/15/60 min"**, que borra transcripción y derivados (tareas, resúmenes, índice). [EDPB 13-oct-2026; Limitless, borrar lo grabado sin consentimiento]
4. **Sin audio persistente por defecto**: audio solo en un búfer de RAM, detección de voz, transcripción en el dispositivo y descarte inmediato. Si alguna vez se ofrece "guardar audio", que esté desactivado por defecto, por conversación y con caducidad corta. [EDPB gafas: almacenamiento persistente desactivado por defecto; Bee]
5. **Filtro de participación del dueño** (la medida que más reduce el riesgo penal): verificación de la voz **solo del dueño**, con su plantilla generada, guardada y comparada en el dispositivo y cifrada. Un segmento o conversación en el que **el dueño no habla** en una ventana razonable (p. ej. ±2–3 min) se **descarta sin guardar**. Los embeddings de voces ajenas se calculan en memoria y no se persisten. Diarización anónima ("Hablante 2"); nombrar a alguien solo a mano. [STC 114/1984 FJ 7; STS 891/2025; EDPB VVA párr. 130–133, 139]
6. **Auto-pausa por contexto**: (a) tras N minutos sin la voz del dueño (como Bee Frontier tras 5–15 min de silencio); (b) **geovallas** configurables (centro de salud, juzgado, colegio, gimnasio o vestuarios, lugar de culto), con pausa y aviso; (c) horario nocturno opcional. [EDPB gafas: geofencing; 197.5 CP]
7. **Procesado local por defecto y opción "Solo en el dispositivo"** que desactiva cualquier red para el contenido: transcripción y búsqueda locales. Con esa opción, nada sale del móvil. [Art. 25 RGPD; EDPB VVA párr. 133; EDPB gafas]
8. **Nube solo opt-in y por función** (resúmenes, preguntas), con una pantalla que diga **qué** se envía, **a quién** y **dónde**. Antes de enviar, **seudonimizar**: sustituir nombres propios, teléfonos, emails, DNI/NIE, IBAN o tarjetas y matrículas por marcadores reversibles solo en local. Proveedor de la UE o del DPF, con retención cero o mínima y sin entrenamiento; **nunca a China** por defecto. Lista blanca de dominios: la app no habla con nadie más. [Cap. V RGPD; §3]
9. **Cifrado local**: base de datos cifrada (p. ej. SQLCipher) con clave protegida en Android Keystore; bloqueo de la app con biometría o PIN; `FLAG_SECURE` en las pantallas de transcripciones (sin capturas ni miniaturas en Recientes); **excluir de Auto Backup** la base de datos, las plantillas de voz y las claves. [Art. 32 RGPD]
10. **Retención con caducidad por defecto**: p. ej. transcripción literal 30 días; resúmenes, tareas y recordatorios hasta que el dueño los borre. Configurable. Borrado automático diario. Son cifras de diseño, no exigencia legal. [Minimización y limitación del plazo; Bee: 14 días gratis]
11. **Borrado real**: por segmento, conversación, día o rango, y **por persona o palabra clave** (para atender a un tercero que lo pida). Borrado en cascada de derivados (resúmenes, tareas, índice de texto o embeddings). "Borrar todo" = destruir la clave (borrado criptográfico). Si se envió algo a un proveedor con retención, registro de qué y cuándo para poder pedir su supresión. [Arts. 17 y 21 RGPD; EDPB VVA párr. 30]
12. **Exportación** en formato abierto (JSON + Markdown), cifrada con contraseña. Aviso al exportar o compartir (§7.4) y **fricción**: confirmación explícita y sin "compartir rápido" con transcripciones literales. [EDPB "privacy frictions"; LO 1/1982 art. 7.3; 197.3 y 197.7 CP]
13. **Nada de contenido en logs, Sentry, notificaciones ni recordatorios visibles en pantalla bloqueada**: el recordatorio dice "Tarea: llamar a X", no la cita literal. [Nota global del dueño sobre datos personales; art. 32]
14. **Funciones vetadas**: reconocimiento de emociones, inferencias de salud o personalidad sobre terceros, identificación de desconocidos por la voz y perfiles de terceros construidos automáticamente. [AI Act art. 5.1.f y anexo III; EDPB VVA párr. 136; art. 9 RGPD]

### 6.2 Muy recomendables

15. **Modo trabajo / cliente** (interruptor o detección por calendario): muestra un texto breve para leer o enviar al cliente (§7.5), registra que se informó, desactiva la nube salvo proveedor con DPA y aplica una retención propia. Alternativa más simple: **modo trabajo = no escuchar**.
16. **Recordatorio en llamadas**: al detectar una llamada (si se capta por altavoz), aviso discreto "Si vas a guardar esta llamada, díselo a la otra persona". [Bee Eclipse]
17. **Etiquetado de contenido sensible** (salud, religión, política, sexualidad, menores): marcar y **no enviar a la nube**, con opción de redacción automática. [197.5 CP; art. 9 RGPD]
18. **Registro de auditoría local**: qué se envió, a qué proveedor, cuándo y cuántos caracteres; visible para el dueño. [Responsabilidad proactiva]
19. **Modo "invitados" o "en casa con niños"**: pausa con un toque y con recordatorio programable (p. ej. las tardes con los hijos). [197.5 CP; EDPB gafas: menores]

### 6.3 Si algún día se distribuye (GitHub, Play, a otra persona)

20. El desarrollador pasa a ser quien "proporciona los medios" (considerando 18) y probablemente responsable de lo que procese su servicio en la nube (EDPB gafas, párr. 16–18): política de privacidad, información a terceros, DPA y EIPD. AI Act: proveedor que comercializa, con art. 50 y, en código abierto, la exención del art. 2.12 con sus límites. Revisar también las políticas de Google Play para micrófono en segundo plano (no investigado aquí).

---

## 7. Avisos al usuario (textos propuestos)

### 7.1 Onboarding (obligatorio, con casilla y fecha)

> **Antes de empezar.** Esta app escucha de forma continua mientras ves la notificación, transcribe en el teléfono y **borra el audio**. Guarda texto con fecha y hora.
>
> - En España **puedes guardar conversaciones en las que participas**. **No puedes grabar conversaciones ajenas** (por ejemplo, la llamada de otra persona o la mesa de al lado): puede ser delito (art. 197 del Código Penal). La app intenta descartar lo que no dices tú, pero **no es infalible**: si dejas el móvil en una sala sin ti, pausa la escucha.
> - **No difundas** transcripciones: compartirlas puede vulnerar la intimidad de otros aunque la conversación fuera tuya.
> - Si la usas **con clientes o en el trabajo**, se aplica la normativa de protección de datos: **avisa** antes y usa el modo trabajo.
> - Por defecto **nada sale del teléfono**. Si activas funciones en la nube, verás qué se envía y a quién.
> - Pausa: notificación, ajustes rápidos o widget. "Olvida lo último" borra los últimos minutos.
>
> Esto no es asesoramiento jurídico.

### 7.2 Notificación persistente

> Escuchando · el audio se borra al transcribir · [Pausar 15 min] [Pausar 1 h] [Detener]

### 7.3 Al activar la nube

> Se enviará **texto seudonimizado** (sin nombres, teléfonos ni emails) a **[proveedor, país]** para [resumir / responder]. El proveedor [no guarda los datos / los guarda N días] y no entrena con ellos. Puede contener lo que dijeron otras personas. [Activar] [Mantener solo en el teléfono]

### 7.4 Al exportar o compartir

> Esta transcripción contiene palabras de **otras personas**. Compartirla fuera de tu uso personal puede vulnerar su intimidad o su protección de datos. ¿Seguro? [Compartir resumen sin nombres] [Compartir literal] [Cancelar]

### 7.5 Modo trabajo (texto para el cliente)

> «Para no tomar notas a mano, uso una app que transcribe esta conversación en mi móvil. No guarda el audio, el texto es solo para mí y lo borro en [N] días. ¿Te parece bien? Si prefieres que no, la paro.»

### 7.6 Llamada detectada

> ¿Vas a guardar esta llamada? Díselo a la otra persona. [Pausar durante la llamada]

---

## 8. Incertidumbres y cosas no verificadas

- **No hay jurisprudencia española sobre asistentes de grabación continua.** El encaje de la captación incidental de conversaciones ajenas en el 197.1 (elemento subjetivo) es una interpretación.
- **Participar en una conversación de grupo** estando presente pero sin hablar: zona gris.
- **197.7 CP y grabaciones solo de audio**: el texto dice «audiovisuales»; su alcance es discutible.
- **Pérdida de la exención doméstica por captar el espacio público**: aplicar Ryneš (vídeo) al audio es analogía, no doctrina.
- **STS de septiembre de 2026** (caso de Benidorm): no he podido verificar número ni texto. Las sentencias del TS citadas con número proceden de reseñas doctrinales, no del texto en CENDOJ.
- **DPF**: válido hoy, pero con recurso C-703/25 P pendiente.
- **Adhesión al DPF de proveedores concretos** (Anthropic, OpenAI): no comprobada.
- **Plaud y la ubicación de los datos de la UE**: Frankfurt según Plaud; EEUU según un competidor.
- **Bee en la UE**: no he encontrado información; no consta que se venda en España.
- **AI Act art. 50.2** (marcado de texto sintético) en sistemas para uso propio: impacto residual, sin guía específica.
- **EIPD**: probablemente exigible en modo trabajo; no contrastado con la lista de la AEPD.
- **Captura del audio de llamadas en Android**: no investigado aquí. Solo cuenta lo que el micrófono capte por altavoz.

---

## 9. Fuentes

Normas y jurisprudencia
- Código Penal, art. 197 y 201 (BOE consolidado): https://www.boe.es/buscar/act.php?id=BOE-A-1995-25444#a197
- LO 1/1982: https://www.boe.es/buscar/act.php?id=BOE-A-1982-11196
- RGPD (texto en español, BOE): https://www.boe.es/buscar/doc.php?id=DOUE-L-2016-80807
- LOPDGDD: https://www.boe.es/buscar/act.php?id=BOE-A-2018-16673
- STC 114/1984: https://hj.tribunalconstitucional.es/es/Resolucion/Show/367
- Doctrina TS (reseña): https://www.legaltoday.com/practica-juridica/derecho-publico/derecho-administrativo/las-grabaciones-de-una-conversacion-entre-dos-personas-sin-el-consentimiento-y-conocimiento-del-otro-segun-la-jurisprudencia-2023-03-09/
- STS 145/2023: https://www.economistjurist.es/actualidad-juridica/la-grabacion-oculta-de-una-conversacion-por-un-tercero-es-licita-si-cuenta-con-el-consentimiento-de-uno-de-los-interlocutores/
- STS 891/2025: https://www.iberley.es/noticias/el-ts-condena-una-madre-2-anos-carcel-difundir-un-grupo-whatsapp-padres-una-conversacion-sus-hijos-su-exmarido-35720 · https://www.legaltoday.com/practica-juridica/derecho-penal/el-ts-respalda-a-un-padre-que-denuncio-a-su-mujer-por-grabar-una-llamada-con-sus-hijos-menores-de-edad-2025-12-01/
- STS sept-2026 (Benidorm): https://www.diarioconstitucional.cl/2026/09/24/tribunal-supremo-de-espana-confirma-condena-a-mujer-que-grabo-conversacion-privada-y-la-aporto-como-prueba-en-juicio/
- TJUE Ryneš C-212/13: https://fra.europa.eu/en/node/17583 · Lindqvist C-101/01: https://curia.europa.eu/es/actu/communiques/cp03/aff/cp0396es.htm · Jehovan todistajat C-25/17: https://www.scl.org/10255-door-to-door-preaching-and-classification-as-a-data-controller/

EDPB y autoridades
- EDPB, Statement on Smart glasses (13-oct-2026): https://www.edpb.europa.eu/system/files/2026-10/20261310_edpb_statement_smart-glasses_en.pdf
- EDPB Guidelines 02/2021 on VVA v2.0: https://www.edpb.europa.eu/system/files/2021-07/edpb_guidelines_202102_on_vva_v2.0_adopted_en.pdf
- G7 DPAs, Compendium of approaches on smart glasses (jun-2026), citado por el EDPB: https://www.cnil.fr/sites/default/files/2026-06/g7%20dpas%20compendium%20of%20approches%20on%20smart%20glasses.pdf
- Berlín sobre DeepSeek: https://regulations.ai/enforcement/enf-germany-hangzhoudeepseek-20250627

Transferencias
- DPF y Latombe: https://www.dataguidance.com/news/eu-cjeu-admits-microsoft-intervener-appeal-concerning · https://btlj.org/2026/02/third-times-the-charm-the-fate-of-the-eu-u-s-data-privacy-framework/ · https://edpl.lexxion.eu/article/EDPL/2026/1/15
- Lista de adheridos al DPF: https://www.dataprivacyframework.gov/list
- DeepSeek, política de privacidad: https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html
- OpenAI, residencia en la UE: https://openai.com/index/introducing-data-residency-in-europe/

AI Act
- Art. 2: https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-2 · Art. 4: https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-4 · Art. 50: https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-50
- Digital Omnibus on AI (Reg. (UE) 2026/1744): https://artificialintelligenceact.eu/ai-act-explorer/digital-omnibus/ · https://www.gibsondunn.com/eu-ai-act-omnibus-agreement-postponed-high-risk-deadlines-and-other-key-changes/
- Omnibus de datos (RGPD): https://edri.org/wp-content/uploads/2026/03/EDRi-Data-Omnibus-Analysis-Council-Compromise-text.pdf

Producto
- Bee: https://www.aboutamazon.com/news/devices/amazon-personal-ai-bee-frontier-bee-eclipse · https://bee.computer/products/eclipse · https://www.cbsnews.com/news/amazon-personal-ai-wearable-devices-bee/ · https://bgr.com/2079772/amazon-bee-ai-gadget-privacy-problems/
- Limitless: https://help.limitless.ai/en/articles/10540861-how-to-ask-for-consent-and-let-others-know-you-are-recording · https://help.limitless.ai/en/articles/13004190-talking-to-someone-wearing-the-pendant-what-to-expect-and-how-we-handle-your-information · https://techinformed.com/meta-acquires-limitless-pendant-users-moved-to-free-unlimited-plan/
- Plaud: https://support.plaud.ai/hc/en-us/articles/52645336070681-Do-I-need-to-obtain-consent-before-recording-with-Plaud-devices-or-Apps · https://www.plaud.ai/pages/trust · https://www.sally.io/blog/plaud-gdpr-data-protection
- Android: https://developer.android.com/develop/background-work/services/fgs/service-types · https://source.android.com/docs/core/permissions/privacy-indicators
