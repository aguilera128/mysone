// Servidor falso de IA para pruebas (docs/arquitectura.md §11). Sin dependencias:
//   node tools/mock-llm.mjs            (PORT=8787 por defecto; desde el emulador, http://10.0.2.2:8787)
// Imita POST /v1/messages (Anthropic) y POST /chat/completions o /v1/chat/completions (compatibles con OpenAI).
// Sabe qué responder por la etiqueta con la que empieza el prompt de sistema («mysone:procesar v1», …) y
// construye salidas válidas según el esquema de la app a partir del mensaje. Clave: MOCK_KEY (por defecto
// test-key); otra clave da 401. En consola solo salen la ruta y los tamaños, nunca el contenido.
import http from 'node:http';

const PORT = Number(process.env.PORT) || 8787;
const KEY = process.env.MOCK_KEY || 'test-key';
const MIN_CACHE_TOKENS = 512; // por debajo, Anthropic no cachea
const seenSystems = new Set(); // para simular la caché del prompt de sistema

// ---------- Utilidades de texto y fechas ----------

const tokens = (s) => Math.ceil((s ?? '').length / 4);
const norm = (s) => s.toLowerCase().normalize('NFC').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
const cap = (s) => (s ? s[0].toUpperCase() + s.slice(1) : s);
const tag = (text, name) => text.match(new RegExp(`<${name}>([\\s\\S]*?)</${name}>`))?.[1] ?? '';
const isoIn = (text) => text.match(/\((\d{4}-\d{2}-\d{2}T\d{2}:\d{2})\)/)?.[1];

function cut(s, max) {
  if (s.length <= max) return s;
  const head = s.slice(0, max);
  return head.slice(0, Math.max(head.lastIndexOf(' '), max / 2)).trim();
}

// Fechas "yyyy-MM-ddTHH:mm" como calendario sin zona: se opera en UTC para no depender de la del PC.
const parseIso = (iso) => {
  const [y, mo, d, h, mi] = iso.match(/\d+/g).map(Number);
  return new Date(Date.UTC(y, mo - 1, d, h, mi));
};
const pad = (n) => String(n).padStart(2, '0');
const fmtDay = (dt) => `${dt.getUTCFullYear()}-${pad(dt.getUTCMonth() + 1)}-${pad(dt.getUTCDate())}`;
const fmt = (dt) => `${fmtDay(dt)}T${pad(dt.getUTCHours())}:${pad(dt.getUTCMinutes())}`;
const addDays = (dt, n) => new Date(dt.getTime() + n * 86_400_000);
const at = (dt, h, m) => new Date(Date.UTC(dt.getUTCFullYear(), dt.getUTCMonth(), dt.getUTCDate(), h, m));

const NUMBERS = { una: 1, uno: 1, dos: 2, tres: 3, cuatro: 4, cinco: 5, seis: 6, siete: 7, ocho: 8, nueve: 9, diez: 10, once: 11, doce: 12 };
const WEEKDAYS = { domingo: 0, lunes: 1, martes: 2, miercoles: 3, 'miércoles': 3, jueves: 4, viernes: 5, sabado: 6, 'sábado': 6 };
const NUM = '(\\d{1,2}|una|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)';
const HOUR_RE = new RegExp(`\\ba las? ${NUM}(?:[:.](\\d{2})| y (media|cuarto)| menos cuarto)?(?: (?:de|por) la (mañana|tarde|noche))?`, 'i');
const IN_RE = new RegExp(`\\bdentro de ${NUM}? ?(media )?(horas?|minutos?)`, 'i');
const PART_RE = /\b(?:esta|por la|de la) (mañana|tarde|noche)|a mediod[ií]a/i;
const WEEKDAY_RE = /\bel (lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)\b/i;
const toNum = (s) => (/^\d+$/.test(s) ? Number(s) : NUMBERS[s.toLowerCase()]);

/** Cuándo, a partir de expresiones como "mañana a las diez" o "el viernes por la tarde". null si no se dice. */
function when(text, ref) {
  const t = text.toLowerCase();
  const inMatch = t.match(IN_RE);
  if (inMatch) {
    const n = inMatch[2] ? 0.5 : toNum(inMatch[1] ?? 'una') ?? 1;
    return fmt(new Date(ref.getTime() + n * (inMatch[3].startsWith('hora') ? 3_600_000 : 60_000)));
  }
  const withoutPart = t.replace(PART_RE, ' ');
  let offset = null;
  if (/pasado mañana/.test(withoutPart)) offset = 2;
  else if (/\bmañana\b/.test(withoutPart)) offset = 1;
  else if (/\bhoy\b/.test(t)) offset = 0;
  const wd = t.match(WEEKDAY_RE);
  if (offset === null && wd) {
    const target = WEEKDAYS[wd[1]];
    offset = ((target - ref.getUTCDay() + 7) % 7) || 7;
  }
  const part = t.match(PART_RE);
  const partName = part ? (part[1] ?? 'mediodía') : null;
  const hm = t.match(HOUR_RE);
  if (!hm && offset === null && !partName) return null;

  let h; let m = 0;
  if (hm) {
    h = toNum(hm[1]);
    if (hm[2]) m = Number(hm[2]);
    if (hm[3] === 'media') m = 30;
    if (hm[3] === 'cuarto') m = 15;
    if (/menos cuarto/.test(hm[0])) { h -= 1; m = 45; }
    const p = hm[4] ?? partName;
    if ((p === 'tarde' || p === 'noche') && h < 12) h += 12;
  } else {
    h = { mañana: 9, tarde: 17, noche: 21, mediodía: 14 }[partName] ?? 9;
  }
  let dt = at(addDays(ref, offset ?? 0), h, m);
  // "a las 6" dicho a las 16:00 son las 18:00; si ya ha pasado, al día siguiente.
  if (offset === null && hm && !hm[4] && !partName && dt <= ref && h < 12) dt = at(ref, h + 12, m);
  if (offset === null && dt <= ref) dt = addDays(dt, 1);
  return fmt(dt);
}

const TIME_WORDS = new RegExp(
  [
    'pasado mañana', '\\bmañana\\b', '\\bhoy\\b', PART_RE.source, WEEKDAY_RE.source,
    `\\ba las? ${NUM}(?:[:.]\\d{2}| y (?:media|cuarto)| menos cuarto)?`, `\\bdentro de ${NUM}? ?(?:media )?(?:horas?|minutos?)`,
  ].join('|'),
  'gi',
);

/** La acción sin las expresiones de tiempo: "mañana a las diez llamar al taller." → "Llamar al taller". */
function action(text) {
  const t = text.replace(TIME_WORDS, ' ').replace(/\s+/g, ' ').replace(/^[\s,:;]*(?:(?:que|de)\s+)?/i, '').replace(/[\s.,;:!?]+$/, '');
  return cap(cut(t, 120));
}

const TRIGGER = /\b(recu[eé]rd(a|ame|amelo|ale)|av[ií]s(ame|ale)|no ((se|me|te|nos) ){1,2}(olvides|olvide|dejes olvidar)|ap[uú]nta(me|lo))\b/i;
const DUTY = /\b(tengo que|tenemos que|tienes que|hay que)\s+(.+)/i;

/** Líneas "[HH:mm:ss] texto" de la transcripción. */
const transcript = (user) =>
  [...tag(user, 'transcripcion').matchAll(/^\[(\d{2}):(\d{2}):(\d{2})\] (.*)$/gm)].map((m) => m[4].trim()).filter(Boolean);

const registered = (user) =>
  new Set(tag(user, 'tareas_registradas').split('\n').filter((l) => l.startsWith('- ')).map((l) => norm(l.slice(2))));

function tasksFrom(lines, ref, already, onlyReminders) {
  const tasks = [];
  for (const line of lines) {
    const trigger = line.match(TRIGGER);
    const duty = line.match(DUTY);
    if (!trigger && (onlyReminders || !duty)) continue;
    const rest = trigger ? line.slice(trigger.index + trigger[0].length) : duty[2];
    const text = action(rest) || cap(cut(line, 120));
    if (already.has(norm(text))) continue;
    already.add(norm(text));
    const date = when(rest, ref);
    tasks.push(trigger
      ? { text, explicitReminder: true, dueAt: null, remindAt: date, quote: line }
      : { text, explicitReminder: false, dueAt: date, remindAt: null, quote: line });
  }
  return tasks;
}

// ---------- Respuestas por ruta ----------

const DAY_WORDS = {
  uno: 1, primero: 1, dos: 2, tres: 3, cuatro: 4, cinco: 5, seis: 6, siete: 7, ocho: 8, nueve: 9, diez: 10, once: 11,
  doce: 12, trece: 13, catorce: 14, quince: 15, 'dieciséis': 16, diecisiete: 17, dieciocho: 18, diecinueve: 19, veinte: 20,
  veintiuno: 21, 'veintiún': 21, 'veintidós': 22, 'veintitrés': 23, veinticuatro: 24, veinticinco: 25, 'veintiséis': 26,
  veintisiete: 27, veintiocho: 28, veintinueve: 29, treinta: 30, 'treinta y uno': 31,
};
const MONTHS = 'enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre';
// "Lucía cumple años el 20 de noviembre" y lo que da el ASR: "mi hermana lucía cumpleaños el veinte de noviembre".
const BIRTHDAY_RE = new RegExp(`(\\p{L}+) cumple(?:años| años)? el (?:día )?(\\d{1,2}|treinta y uno|\\p{L}+) de (${MONTHS})`, 'giu');
const NOT_NAMES = new Set(['que', 'quien', 'quién', 'él', 'ella', 'se', 'me', 'te', 'le', 'hoy', 'mañana', 'también', 'ya']);

function birthdays(text) {
  const out = [];
  for (const m of text.matchAll(BIRTHDAY_RE)) {
    const day = /^\d+$/.test(m[2]) ? Number(m[2]) : DAY_WORDS[m[2].toLowerCase()];
    if (!day || day > 31 || NOT_NAMES.has(m[1].toLowerCase())) continue;
    const text = `${cap(m[1].toLowerCase())} cumple años el ${day} de ${m[3].toLowerCase()}`;
    if (!out.some((f) => f.text === text)) out.push({ text });
  }
  return out.slice(0, 3);
}

function processConversation(user) {
  const ref = parseIso(isoIn(tag(user, 'conversacion')) ?? '2026-01-01T09:00');
  const lines = transcript(user);
  if (lines.length === 0) {
    return { title: 'Sin contenido', summary: 'No se oye nada útil en esta conversación.', keyPoints: [], isNoise: true, tasks: [], facts: [] };
  }
  // Fuera los "Sí." y "Vale." sueltos, salvo que no haya otra cosa.
  const all = lines.join(' ').split(/(?<=[.!?])\s+/).map((s) => s.trim()).filter(Boolean);
  const long = all.filter((s) => s.split(' ').length >= 3);
  const sentences = long.length ? long : all;
  const title = cap(cut(sentences[0].replace(/^[¿¡\s]+/, '').replace(/[\s.,;:!?]+$/, ''), 60));
  const facts = birthdays(lines.join(' '));
  return {
    title,
    summary: cut(sentences.slice(0, 2).join(' '), 300),
    keyPoints: sentences.slice(1, 4).map((s) => cut(s, 100)),
    isNoise: false,
    tasks: tasksFrom(lines, ref, registered(user), false),
    facts,
  };
}

function quickReminder(user) {
  const ref = parseIso(isoIn(tag(user, 'momento')) ?? '2026-01-01T09:00');
  return { tasks: tasksFrom(transcript(user), ref, registered(user), true) };
}

const STOP = new Set(['que', 'qué', 'del', 'los', 'las', 'una', 'con', 'por', 'para', 'como', 'cómo', 'cuando', 'cuándo', 'donde', 'dónde', 'dijo', 'dije', 'hablé', 'tengo', 'ayer', 'hoy', 'mañana', 'semana', 'pasada', 'esta', 'este', 'sobre']);

function plan(user) {
  const now = parseIso(isoIn(tag(user, 'ahora')) ?? '2026-01-01T09:00');
  const q = tag(user, 'pregunta').toLowerCase();
  const words = [...new Set((q.match(/[\p{L}\p{N}]+/gu) ?? []).filter((w) => w.length >= 3 && !STOP.has(w)))];
  // Variantes simples de singular y plural.
  const keywords = [...new Set(words.flatMap((w) => [w, w.endsWith('s') ? w.slice(0, -1) : `${w}s`]))].slice(0, 12);
  let from = null; let to = null;
  const day = (n) => fmtDay(addDays(now, n));
  if (/\banteayer\b/.test(q)) from = to = day(-2);
  else if (/\bayer\b/.test(q)) from = to = day(-1);
  else if (/\bhoy\b/.test(q)) from = to = day(0);
  else if (/semana pasada/.test(q)) {
    const monday = -(((now.getUTCDay() + 6) % 7) + 7);
    from = day(monday); to = day(monday + 6);
  } else if (/esta semana/.test(q)) {
    from = day(-((now.getUTCDay() + 6) % 7)); to = day(0);
  }
  return { keywords: keywords.length ? keywords : ['conversación'], from, to };
}

function answer(user) {
  const q = tag(user, 'pregunta').toLowerCase();
  const pending = tag(user, 'tareas_pendientes').split('\n').filter((l) => l.startsWith('- '));
  if (/pendiente|tareas|que hacer|qué hacer/.test(q) && pending.length) {
    return `Tienes ${pending.length} ${pending.length === 1 ? 'tarea pendiente' : 'tareas pendientes'}:\n${pending.slice(0, 5).join('\n')}`;
  }
  // [c:12] jueves 15 de octubre de 2026, 18:42–18:50 · Título — Resumen
  const convs = [...tag(user, 'conversaciones').matchAll(/^\[c:(\d+)\] ([^·\n]*)· ([^\n]*?) — ([^\n]*)$/gm)]
    .map(([, id, when_, title, summary]) => ({ id, when: when_.trim(), title: title.trim(), summary: summary.trim(), lines: [] }));
  // <fragmentos>: "[c:12] jueves 15 de octubre de 2026" y debajo "18:42:10 texto".
  let current = null;
  for (const line of tag(user, 'fragmentos').split('\n')) {
    const head = line.match(/^\[c:(\d+)\] (.*)$/);
    if (head) {
      current = convs.find((c) => c.id === head[1]);
      if (!current) convs.push(current = { id: head[1], when: head[2].trim(), title: '', summary: '', lines: [] });
    } else if (current && /^\d{2}:\d{2}:\d{2} /.test(line)) {
      current.lines.push(line.slice(9).trim());
    }
  }
  if (convs.length === 0) return 'No lo encuentro en lo que has hablado. Prueba a buscarlo con otras palabras.';
  // La que más palabras de la pregunta contiene (título, resumen y fragmentos); a igualdad, la más reciente.
  const plain = (s) => norm(s).normalize('NFD').replace(/\p{M}/gu, '');
  const words = [...new Set(plain(q).split(' ').filter((w) => w.length >= 4 && !STOP.has(w)))];
  const score = (text) => { const t = ` ${plain(text)} `; return words.filter((w) => t.includes(` ${w}`)).length; };
  let best = convs[0]; let bestScore = -1;
  for (const c of convs) {
    const s = score([c.title, c.summary, ...c.lines].join(' '));
    if (s > bestScore) { best = c; bestScore = s; }
  }
  const quote = best.lines.map((l) => [score(l), l]).sort((a, b) => b[0] - a[0])[0];
  const said = quote && quote[0] > 0 ? `«${quote[1]}»` : (best.summary || `hablaste de «${best.title}»`);
  return `${bestScore > 0 ? 'Lo hablaste' : 'Lo más reciente que encuentro es'} el ${best.when}: ${said} [c:${best.id}]`;
}

function digest(user) {
  const items = [...tag(user, 'conversaciones').matchAll(/^\[(\d{2}:\d{2})\] ([^\n]*?) — ([^\n]*)$/gm)].map((m) => ({ hour: m[1], title: m[2].trim() }));
  if (items.length === 0) return { summary: 'Un día tranquilo, sin conversaciones destacables.', highlights: [] };
  const titles = items.map((i) => i.title);
  const list = titles.length === 1 ? titles[0] : `${titles.slice(0, -1).join(', ')} y ${titles.at(-1)}`;
  return {
    summary: `Tuviste ${items.length} ${items.length === 1 ? 'conversación' : 'conversaciones'}: ${list}.`,
    highlights: items.slice(0, 5).map((i) => `${i.hour} · ${i.title}`),
  };
}

const ROUTES = {
  procesar: processConversation,
  recordatorio: quickReminder,
  planificar: plan,
  responder: answer,
  'resumen-diario': digest,
  prueba: () => 'OK',
};

// ---------- Validación contra el esquema que manda la app ----------

/** null si [v] cumple [schema] (subconjunto que usa la app); si no, la ruta del fallo (sin contenido). */
function check(schema, v, path = '$') {
  const types = [].concat(schema.type);
  const t = v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v;
  if (!types.includes(t)) return `${path}: ${t}`;
  if (t === 'array') {
    for (const [i, x] of v.entries()) { const e = check(schema.items, x, `${path}[${i}]`); if (e) return e; }
  }
  if (t === 'object') {
    for (const k of schema.required ?? []) if (!(k in v)) return `${path}.${k}: falta`;
    for (const k of Object.keys(v)) {
      if (!schema.properties?.[k]) { if (schema.additionalProperties === false) return `${path}.${k}: sobra`; continue; }
      const e = check(schema.properties[k], v[k], `${path}.${k}`); if (e) return e;
    }
  }
  return null;
}

// ---------- HTTP ----------

function send(res, status, body) {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  res.writeHead(status, { 'content-type': typeof body === 'string' ? 'text/plain; charset=utf-8' : 'application/json' });
  res.end(text);
}

const anthropicError = (res, status, type, message) => send(res, status, { type: 'error', error: { type, message } });
const openAiError = (res, status, type, message) => send(res, status, { error: { type, message } });

const textOf = (content) =>
  typeof content === 'string' ? content : (content ?? []).filter((b) => b?.type === 'text').map((b) => b.text).join('\n');

/** Ruta, salida y tamaños. Lanza si la etiqueta es desconocida. */
function run(system, user, schema, maxTokens) {
  const route = system.match(/^mysone:([a-z-]+) v\d+/)?.[1];
  const fn = ROUTES[route];
  if (!fn) throw new Error('etiqueta de sistema desconocida');
  const out = fn(user);
  const problem = schema && typeof out !== 'string' ? check(schema, out) : null;
  if (problem) console.warn(`[mock-llm] AVISO: la salida de ${route} no cumple el esquema (${problem})`);
  let text = typeof out === 'string' ? out : JSON.stringify(out);
  const truncated = maxTokens && tokens(text) > maxTokens;
  if (truncated) text = text.slice(0, maxTokens * 4);
  return { route, text, truncated };
}

function log(api, route, model, system, user, text, status) {
  console.log(`[mock-llm] ${new Date().toISOString()} ${api} ${route ?? '-'} model=${model ?? '-'} sistema=${system.length} usuario=${user.length} salida=${text.length} car. → ${status}`);
}

function anthropic(req, res, body) {
  if (!req.headers['x-api-key']) return anthropicError(res, 401, 'authentication_error', 'x-api-key header is required');
  if (req.headers['anthropic-version'] !== '2023-06-01') return anthropicError(res, 400, 'invalid_request_error', 'anthropic-version header is required');
  if (req.headers['x-api-key'] !== KEY) return anthropicError(res, 401, 'authentication_error', 'invalid x-api-key');
  for (const k of ['temperature', 'top_p', 'top_k']) {
    if (k in body) return anthropicError(res, 400, 'invalid_request_error', `${k}: not supported on this model`);
  }
  if ('fallbacks' in body && !String(req.headers['anthropic-beta'] ?? '').includes('server-side-fallback')) {
    return anthropicError(res, 400, 'invalid_request_error', 'fallbacks: requires the server-side-fallback beta');
  }
  const blocks = Array.isArray(body.system) ? body.system : [{ type: 'text', text: body.system ?? '' }];
  const system = textOf(blocks);
  const messages = body.messages ?? [];
  const user = textOf(messages.filter((m) => m.role === 'user').at(-1)?.content);
  const history = messages.map((m) => textOf(m.content)).join('\n');
  let result;
  try {
    result = run(system, user, body.output_config?.format?.schema, body.max_tokens);
  } catch (e) {
    log('anthropic', null, body.model, system, user, '', 400);
    return anthropicError(res, 400, 'invalid_request_error', e.message);
  }
  const cacheable = blocks.some((b) => b.cache_control) && tokens(system) >= MIN_CACHE_TOKENS;
  const cacheKey = `${body.model}\n${system}`;
  const hit = cacheable && seenSystems.has(cacheKey);
  if (cacheable) seenSystems.add(cacheKey);
  log('anthropic', result.route, body.model, system, user, result.text, 200);
  send(res, 200, {
    id: `msg_mock_${Date.now()}`,
    type: 'message',
    role: 'assistant',
    model: body.model,
    content: [{ type: 'thinking', thinking: '', signature: 'mock' }, { type: 'text', text: result.text }],
    stop_reason: result.truncated ? 'max_tokens' : 'end_turn',
    stop_details: null,
    usage: {
      input_tokens: tokens(history) + (cacheable ? 0 : tokens(system)),
      cache_creation_input_tokens: cacheable && !hit ? tokens(system) : 0,
      cache_read_input_tokens: hit ? tokens(system) : 0,
      output_tokens: tokens(result.text),
    },
  });
}

function openai(req, res, body) {
  if (req.headers.authorization !== `Bearer ${KEY}`) return openAiError(res, 401, 'invalid_api_key', 'Incorrect API key provided');
  const messages = body.messages ?? [];
  const system = textOf(messages.find((m) => m.role === 'system')?.content);
  const user = textOf(messages.filter((m) => m.role === 'user').at(-1)?.content);
  const format = body.response_format;
  if (format?.type === 'json_object' && !/json/i.test(messages.map((m) => textOf(m.content)).join('\n'))) {
    return openAiError(res, 400, 'invalid_request_error', "'messages' must contain the word 'json' to use json_object");
  }
  let result;
  try {
    result = run(system, user, format?.json_schema?.schema, body.max_tokens);
  } catch (e) {
    log('openai', null, body.model, system, user, '', 400);
    return openAiError(res, 400, 'invalid_request_error', e.message);
  }
  const cacheKey = `${body.model}\n${system}`;
  const hit = seenSystems.has(cacheKey);
  seenSystems.add(cacheKey);
  const prompt = tokens(messages.map((m) => textOf(m.content)).join('\n'));
  log('openai', result.route, body.model, system, user, result.text, 200);
  send(res, 200, {
    id: `chatcmpl-mock-${Date.now()}`,
    object: 'chat.completion',
    model: body.model,
    choices: [{ index: 0, finish_reason: result.truncated ? 'length' : 'stop', message: { role: 'assistant', content: result.text } }],
    usage: {
      prompt_tokens: prompt,
      completion_tokens: tokens(result.text),
      total_tokens: prompt + tokens(result.text),
      prompt_tokens_details: { cached_tokens: hit ? tokens(system) : 0 },
    },
  });
}

const server = http.createServer((req, res) => {
  const path = req.url.split('?')[0];
  if (req.method === 'GET' && path === '/health') return send(res, 200, 'ok');
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    if (req.method !== 'POST') return send(res, 404, { error: { type: 'not_found_error', message: 'not found' } });
    let body;
    try {
      body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
      return send(res, 400, { error: { type: 'invalid_request_error', message: 'body is not valid JSON' } });
    }
    if (path === '/v1/messages') return anthropic(req, res, body);
    if (path === '/chat/completions' || path === '/v1/chat/completions') return openai(req, res, body);
    return send(res, 404, { error: { type: 'not_found_error', message: 'not found' } });
  });
});

server.listen(PORT, () => console.log(`[mock-llm] escuchando en http://localhost:${PORT} (clave: MOCK_KEY o test-key)`));
