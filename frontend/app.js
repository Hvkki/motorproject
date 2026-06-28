/* ============================================================
   Мамина Студія — логіка інтерфейсу
   ============================================================ */
"use strict";

const $ = (id) => document.getElementById(id);
const API = ""; // той самий origin (FastAPI віддає і фронт, і API)

const state = {
  mode: "create",          // "create" | "edit"
  count: 4,
  width: 1024,
  height: 1024,
  steps: 28,               // "якість" (кроки)
  negative: "",
  style: "",               // обраний стиль-пресет
  photo: null,             // data URL для «з мого фото» (img2img)
  strength: 0.6,           // наскільки сильно змінити фото
  editing: null,           // { imgB64, canvas, ctx, strokes } для режиму «Підправити»
  brush: 55,
  history: loadHistory(),
  last: null,              // { images, prompt, bubble } — для інструментів агента
  agent: {
    available: false,
    on: true,
    msgs: [],
    key: localStorage.getItem("mama_ollama_key") || "",
    model: localStorage.getItem("mama_ollama_model") || "gemma4:31b",
  },
};

/** Додає обраний стиль до тексту промпта. */
function composePrompt(text) {
  return state.style ? `${text}, ${state.style}` : text;
}

/* ---------------------- Допоміжне ---------------------- */
function toast(text, ms = 2600) {
  const t = $("toast");
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => (t.hidden = true), ms);
}

function el(tag, cls, html) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (html != null) e.innerHTML = html;
  return e;
}

function scrollChat() {
  const c = $("chat");
  c.scrollTop = c.scrollHeight;
}

function greeting() {
  const h = new Date().getHours();
  if (h < 5) return "Доброї ночі! 🌙";
  if (h < 12) return "Доброго ранку! ☀️";
  if (h < 18) return "Доброго дня! 🌼";
  return "Доброго вечора! 🌇";
}

/* ---------------------- Перевірка студії ---------------------- */
async function checkHealth() {
  const dot = $("statusDot"), txt = $("statusText");
  try {
    const r = await fetch(`${API}/api/health`);
    const d = await r.json();
    if (d.mock) {
      dot.className = "dot mock";
      txt.textContent = "Демо-режим (без GPU)";
    } else {
      dot.className = "dot ok";
      const dual = d.device && d.device.mode === "cuda-dual";
      txt.textContent = dual ? "Готово • 2 відеокарти 🚀" : "Готово до роботи ✓";
    }
    if (d.defaults && d.defaults.max_batch) {
      document.querySelectorAll("#countPills button").forEach((b) => {
        if (+b.dataset.count > d.defaults.max_batch) b.style.display = "none";
      });
    }
    state.agent.available = !!(d.agent && d.agent.available) || !!state.agent.key;
    updateHelperToggle();
  } catch (e) {
    dot.className = "dot err";
    txt.textContent = "Студія ще спить… 😴";
  }
}

/* ---------------------- Налаштування агентів (Ollama токен у UI) ---------------------- */
function openAgentSettings() {
  $("asKey").value = state.agent.key || "";
  $("asModel").value = state.agent.model || "gemma4:31b";
  $("asStatus").textContent = state.agent.key ? "Ключ збережено ✓" : "Ключ ще не введено";
  $("asStatus").className = "as-status " + (state.agent.key ? "ok" : "");
  $("agentSettings").hidden = false;
}

async function testAgentKey() {
  const key = $("asKey").value.trim();
  const model = $("asModel").value.trim() || "gemma4:31b";
  if (!key) { $("asStatus").textContent = "Спочатку вставте ключ"; return; }
  $("asStatus").textContent = "Перевіряю…";
  $("asStatus").className = "as-status";
  try {
    const r = await fetch(`${API}/api/agent/test`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ key, model }),
    });
    const d = await r.json();
    if (d.available && d.model_found) {
      $("asStatus").textContent = `Працює! Модель ${model} знайдено ✓`;
      $("asStatus").className = "as-status ok";
    } else if (d.available) {
      $("asStatus").textContent = `Ключ дійсний, але моделі «${model}» немає. Спробуйте gemma4:31b.`;
      $("asStatus").className = "as-status warn";
    } else {
      $("asStatus").textContent = "Ключ не підійшов 😔 Перевірте його.";
      $("asStatus").className = "as-status err";
    }
  } catch (e) {
    $("asStatus").textContent = "Не вдалося перевірити. Студія ще запускається?";
    $("asStatus").className = "as-status err";
  }
}

function saveAgentSettings() {
  const key = $("asKey").value.trim();
  const model = $("asModel").value.trim() || "gemma4:31b";
  state.agent.key = key;
  state.agent.model = model;
  localStorage.setItem("mama_ollama_key", key);
  localStorage.setItem("mama_ollama_model", model);
  state.agent.available = state.agent.available || !!key;
  updateHelperToggle();
  $("agentSettings").hidden = true;
  toast(key ? "Збережено! Агенти готові 🤖" : "Ключ прибрано");
}

function updateHelperToggle() {
  const t = $("helperToggle");
  if (!t) return;
  if (!state.agent.available) { t.style.display = "none"; return; }
  t.style.display = "";
  t.classList.toggle("on", state.agent.on);
  const s = t.querySelector(".ht-state");
  if (s) s.textContent = state.agent.on ? "увімкнена" : "вимкнена";
}

/* ---------------------- Повідомлення в чаті ---------------------- */
function addUserMsg(text) {
  const m = el("div", "msg user");
  m.appendChild(el("div", "avatar", "🌷"));
  const b = el("div", "bubble");
  b.appendChild(el("p", null, escapeHtml(text)));
  m.appendChild(b);
  $("chat").appendChild(m);
  scrollChat();
}

function addAssistantMsg(avatar) {
  const m = el("div", "msg assistant");
  m.appendChild(el("div", "avatar", avatar || "🎨"));
  const b = el("div", "bubble");
  m.appendChild(b);
  $("chat").appendChild(m);
  scrollChat();
  return b;
}

function escapeHtml(s) {
  return s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}

/* ---------------------- Створення зображень ---------------------- */
// Спільний рендер галереї в задану «бульбашку».
async function produceImages(bubble, prompt, opts) {
  const o = Object.assign(
    { count: state.count, width: state.width, height: state.height, steps: state.steps },
    opts || {}
  );
  const ar = (o.width / o.height).toFixed(3);
  const gal = el("div", `gallery n${o.count}`);
  for (let i = 0; i < o.count; i++) {
    const tile = el("div", "tile loading");
    tile.style.setProperty("--ar", ar);
    tile.appendChild(el("div", "shimmer"));
    gal.appendChild(tile);
  }
  bubble.appendChild(gal);
  scrollChat();

  const r = await fetch(`${API}/api/generate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      prompt, negative: state.negative, count: o.count,
      width: o.width, height: o.height, steps: o.steps,
    }),
  });
  if (!r.ok) throw new Error("HTTP " + r.status);
  const d = await r.json();
  if (d.error) throw new Error(d.error);
  gal.innerHTML = "";
  gal.className = `gallery n${d.images.length}`;
  const ar2 = (d.width / d.height).toFixed(3);
  d.images.forEach((src, i) => gal.appendChild(makeTile(src, prompt, d.seeds[i], ar2)));
  scrollChat();
  return d;
}

async function generate(prompt, opts) {
  addUserMsg(prompt);
  const bubble = addAssistantMsg();
  const status = el("p", null, `Малюю для тебе… <span class="typing"><span></span><span></span><span></span></span>`);
  bubble.appendChild(status);
  scrollChat();
  $("sendBtn").disabled = true;
  try {
    const d = await produceImages(bubble, prompt, opts);
    status.innerHTML = d.mock
      ? "Готово! (демо-режим) Обирай, що подобається 💛"
      : `Готово за ${d.elapsed} с! Обирай, що подобається 💛`;
    saveToHistory(prompt, d.images[0]);
    state.last = { images: d.images, prompt, bubble };
    await maybeReview(bubble, d.images, prompt);
  } catch (e) {
    status.innerHTML = `Ой, щось пішло не так 😔 <small>${escapeHtml(String(e.message || e))}</small>. Спробуй ще раз за хвилинку.`;
  } finally {
    $("sendBtn").disabled = false;
  }
}

/* ---------------------- Агенти (Ollama) ---------------------- */
const AGENT_AVATAR = "🤖";
const TYPING = `<span class="typing"><span></span><span></span><span></span></span>`;

function agentHeaders() {
  const h = { "Content-Type": "application/json" };
  if (state.agent.key) { h["X-Ollama-Key"] = state.agent.key; h["X-Ollama-Model"] = state.agent.model; }
  return h;
}

function ratioFromFormat(f) {
  switch (f) {
    case "landscape": return { width: 1216, height: 832 };
    case "portrait": return { width: 832, height: 1216 };
    case "banner": return { width: 1536, height: 640 };
    default: return { width: 1024, height: 1024 };
  }
}

function doneText(d) {
  return d.mock ? "Готово! (демо-режим) 💛" : `Готово за ${d.elapsed} с! 💛`;
}

async function agentTurn(text) {
  addUserMsg(text);
  state.agent.msgs.push({ role: "user", content: text });
  const bubble = addAssistantMsg(AGENT_AVATAR);
  const status = el("p", null, `Агент думає… ${TYPING}`);
  bubble.appendChild(status);
  scrollChat();
  $("sendBtn").disabled = true;
  try {
    const r = await fetch(`${API}/api/chat`, {
      method: "POST",
      headers: agentHeaders(),
      body: JSON.stringify({ messages: state.agent.msgs.slice(-12), has_photo: !!state.photo }),
    });
    if (r.status === 503) { // агент недоступний — звичайний режим
      state.agent.available = false; updateHelperToggle();
      bubble.remove(); generate(composePrompt(text)); return;
    }
    if (!r.ok) throw new Error("HTTP " + r.status);
    const d = await r.json();
    state.agent.msgs.push({ role: "assistant", content: d.reply });
    status.innerHTML = escapeHtml(d.reply);

    if (d.questions && d.questions.length) {
      const ul = el("ul", "agent-questions");
      d.questions.forEach((q) => ul.appendChild(el("li", null, escapeHtml(q))));
      bubble.appendChild(ul);
    }
    if (d.actions && d.actions.length) await executeActions(d.actions);
    scrollChat();
  } catch (e) {
    status.innerHTML = "Ой, агент зараз не відповідає 😔 Спробуймо ще раз?";
  } finally {
    $("sendBtn").disabled = false;
  }
}

/* Агент виконує інструменти (tools), які сам обрав. */
async function executeActions(actions) {
  let reviewed = false;
  for (const a of actions) {
    try {
      if (a.tool === "generate") {
        const b = addAssistantMsg(AGENT_AVATAR);
        const st = el("p", null, `Малюю… ${TYPING}`);
        b.appendChild(st); scrollChat();
        const prompt = a.style ? `${a.prompt}, ${a.style}` : a.prompt;
        const d = await produceImages(b, prompt, { count: a.count || state.count, ...ratioFromFormat(a.format) });
        st.innerHTML = doneText(d);
        saveToHistory(prompt, d.images[0]);
        state.last = { images: d.images, prompt, bubble: b };
      } else if (a.tool === "img2img") {
        if (!state.photo) {
          addAssistantMsg(AGENT_AVATAR).appendChild(el("p", null, "Щоб перетворити фото, спершу додай його кнопкою 📷"));
          continue;
        }
        const b = addAssistantMsg(AGENT_AVATAR);
        const st = el("p", null, `Перетворюю фото… ${TYPING}`);
        b.appendChild(st); scrollChat();
        const d = await produceImg2img(b, a.prompt, a.strength || state.strength);
        st.innerHTML = doneText(d);
        saveToHistory(a.prompt, d.images[0]);
        state.last = { images: d.images, prompt: a.prompt, bubble: b };
        clearPhoto();
      } else if (a.tool === "variations") {
        const base = state.last && state.last.prompt;
        if (!base) continue;
        const b = addAssistantMsg(AGENT_AVATAR);
        const st = el("p", null, `Роблю схожі варіанти… ${TYPING}`);
        b.appendChild(st); scrollChat();
        const d = await produceImages(b, base, { count: state.count });
        st.innerHTML = doneText(d);
        state.last = { images: d.images, prompt: base, bubble: b };
      } else if (a.tool === "upscale") {
        if (state.last && state.last.images && state.last.images[0]) await upscale(state.last.images[0], state.last.prompt);
      } else if (a.tool === "edit") {
        if (state.last && state.last.images && state.last.images[0]) openEditor(state.last.images[0]);
      } else if (a.tool === "review") {
        if (state.last) { await maybeReview(state.last.bubble, state.last.images, state.last.prompt); reviewed = true; }
      }
    } catch (e) {
      /* пропускаємо інструмент, що не виконався */
    }
  }
  // авто-перевірка після малювання, якщо агент сам не попросив
  if (!reviewed && state.last && state.last.images && state.agent.on) {
    await maybeReview(state.last.bubble, state.last.images, state.last.prompt);
  }
}

async function produceImg2img(bubble, prompt, strength) {
  const count = Math.min(state.count, 2);
  const gal = el("div", `gallery n${count}`);
  for (let i = 0; i < count; i++) { const t = el("div", "tile loading"); t.appendChild(el("div", "shimmer")); gal.appendChild(t); }
  bubble.appendChild(gal); scrollChat();
  const r = await fetch(`${API}/api/img2img`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ image: state.photo, prompt, strength, count, steps: state.steps }),
  });
  if (!r.ok) throw new Error("HTTP " + r.status);
  const d = await r.json();
  gal.innerHTML = "";
  gal.className = `gallery n${d.images.length}`;
  const ar = (d.width / d.height).toFixed(3);
  d.images.forEach((src, i) => gal.appendChild(makeTile(src, prompt, d.seeds[i], ar)));
  scrollChat();
  return d;
}

/* Агент-критик переглядає готові картинки на дефекти (artifacts). */
async function maybeReview(bubble, images, prompt) {
  if (!state.agent.available || !state.agent.on || !images || !images.length || !bubble) return;
  const note = el("div", "review-note busy", `🔎 Агент-критик роздивляється картинки… ${TYPING}`);
  bubble.appendChild(note);
  scrollChat();
  try {
    const r = await fetch(`${API}/api/review`, {
      method: "POST",
      headers: agentHeaders(),
      body: JSON.stringify({ images, prompt }),
    });
    if (!r.ok) throw new Error("review failed");
    const d = await r.json();
    note.classList.remove("busy");
    if (d.ok && (!d.issues || !d.issues.length)) {
      note.innerHTML = `🔎 <b>Агент-критик:</b> ${escapeHtml(d.summary)}`;
    } else {
      let html = `🔎 <b>Агент-критик:</b> ${escapeHtml(d.summary)}<ul class="review-issues">`;
      d.issues.forEach((it) => {
        html += `<li>Картинка ${it.index}: ${escapeHtml(it.problem)}${it.fix ? ` — <i>${escapeHtml(it.fix)}</i>` : ""}</li>`;
      });
      html += "</ul>";
      note.innerHTML = html;
      const fixes = d.issues.map((i) => i.fix).filter(Boolean).join(", ");
      const btn = el("button", "mini-btn", "🎨 Перемалювати з порадами");
      btn.onclick = () => generate(fixes ? `${prompt}, ${fixes}` : prompt);
      note.appendChild(btn);
    }
    scrollChat();
  } catch (e) {
    note.remove();
  }
}

function makeTile(src, prompt, seed, ar) {
  const tile = el("div", "tile");
  tile.style.setProperty("--ar", ar || 1);
  const img = el("img");
  img.src = src;
  img.alt = prompt;
  img.loading = "lazy";
  tile.appendChild(img);

  const bar = el("div", "tile-bar");
  const dl = el("button", "tile-act", "⬇︎ Зберегти");
  dl.onclick = (e) => { e.stopPropagation(); download(src, prompt); };
  const ed = el("button", "tile-act", "✏️ Змінити");
  ed.onclick = (e) => { e.stopPropagation(); openEditor(src); };
  bar.append(dl, ed);
  tile.appendChild(bar);

  tile.onclick = () => openLightbox(src, prompt);
  return tile;
}

function download(src, prompt) {
  const a = document.createElement("a");
  a.href = src;
  a.download = (prompt || "малюнок").slice(0, 30).replace(/[^\p{L}\p{N}]+/gu, "_") + ".png";
  a.click();
  toast("Збережено в завантаження 📥");
}

/* ---------------------- «З мого фото» (image-to-image) ---------------------- */
async function img2img(prompt) {
  addUserMsg("🖼️ " + prompt);
  const bubble = addAssistantMsg();
  const status = el("p", null, `Перетворюю твоє фото… ${TYPING}`);
  bubble.appendChild(status);
  scrollChat();
  $("sendBtn").disabled = true;
  try {
    const d = await produceImg2img(bubble, prompt, state.strength);
    status.innerHTML = doneText(d);
    saveToHistory(prompt, d.images[0]);
    state.last = { images: d.images, prompt, bubble };
    clearPhoto();
    await maybeReview(bubble, d.images, prompt);
  } catch (e) {
    status.innerHTML = `Ой, не вдалося перетворити фото 😔 <small>${escapeHtml(String(e.message || e))}</small>`;
  } finally {
    $("sendBtn").disabled = false;
  }
}

function setPhoto(dataUrl) {
  state.photo = dataUrl;
  $("attachThumb").src = dataUrl;
  $("attachChip").hidden = false;
  $("strengthOpt").hidden = false;
  $("promptInput").placeholder = "На що перетворити твоє фото?";
  $("promptInput").focus();
}
function clearPhoto() {
  state.photo = null;
  $("attachChip").hidden = true;
  $("strengthOpt").hidden = true;
  $("photoInput").value = "";
  $("promptInput").placeholder = "Напиши, що намалювати…";
}

/* ---------------------- Збільшити якість (upscale) ---------------------- */
async function upscale(src, prompt) {
  toast("Збільшую якість… зачекай мить 🔍", 4000);
  try {
    const r = await fetch(`${API}/api/upscale`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ image: src, scale: 2 }),
    });
    if (!r.ok) throw new Error("HTTP " + r.status);
    const d = await r.json();
    const bubble = addAssistantMsg();
    bubble.innerHTML = `<p>Готово — більша й чіткіша версія (${d.width}×${d.height}) ✨</p>`;
    const gal = el("div", "gallery n1");
    const ar = (d.width / d.height).toFixed(3);
    gal.appendChild(makeTile(d.images[0], prompt || "збільшено", 0, ar));
    bubble.appendChild(gal);
    openLightbox(d.images[0], prompt);
    scrollChat();
  } catch (e) {
    toast("Не вдалося збільшити 😔");
  }
}

/* ---------------------- Лайтбокс ---------------------- */
let lbCurrent = { src: "", prompt: "" };
function openLightbox(src, prompt) {
  lbCurrent = { src, prompt };
  $("lbImg").src = src;
  $("lightbox").hidden = false;
}
$("lbClose").onclick = () => ($("lightbox").hidden = true);
$("lightbox").onclick = (e) => { if (e.target === $("lightbox")) $("lightbox").hidden = true; };
$("lbDownload").onclick = () => download(lbCurrent.src, lbCurrent.prompt);
$("lbUpscale").onclick = () => { $("lightbox").hidden = true; upscale(lbCurrent.src, lbCurrent.prompt); };
$("lbEdit").onclick = () => { $("lightbox").hidden = true; openEditor(lbCurrent.src); };
$("lbVariations").onclick = () => {
  $("lightbox").hidden = true;
  if (lbCurrent.prompt) { setMode("create"); generate(lbCurrent.prompt); }
};

/* ---------------------- Режим «Підправити» (обвести й змінити) ---------------------- */
function openEditor(src) {
  setMode("edit");
  const bubble = addAssistantMsg();
  bubble.innerHTML = `<p><b>Обведи пальцем</b> те, що хочеш змінити 👇 а потім напиши внизу, що там має зʼявитися.</p>`;

  const stage = el("div", "edit-stage");
  const img = el("img");
  img.src = src;
  const canvas = el("canvas");
  stage.append(img, canvas);
  bubble.appendChild(stage);
  scrollChat();

  img.onload = () => {
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    const ctx = canvas.getContext("2d");
    state.editing = { imgB64: src, canvas, ctx, strokes: [] };
    bindBrush(stage, canvas, ctx);
  };
  $("promptInput").placeholder = "Що має зʼявитися в обведеному місці?";
  $("promptInput").focus();
}

function bindBrush(stage, canvas, ctx) {
  let drawing = false, current = null;
  const pos = (e) => {
    const r = canvas.getBoundingClientRect();
    const p = e.touches ? e.touches[0] : e;
    return {
      x: ((p.clientX - r.left) / r.width) * canvas.width,
      y: ((p.clientY - r.top) / r.height) * canvas.height,
    };
  };
  const radius = () => (state.brush / canvas.getBoundingClientRect().width) * canvas.width;

  const start = (e) => { e.preventDefault(); drawing = true; current = { r: radius(), pts: [] }; move(e); };
  const move = (e) => {
    if (!drawing) return;
    e.preventDefault();
    const p = pos(e);
    current.pts.push(p);
    redraw(canvas, ctx, [...state.editing.strokes, current]);
  };
  const end = () => {
    if (!drawing) return;
    drawing = false;
    if (current && current.pts.length) state.editing.strokes.push(current);
  };

  canvas.addEventListener("mousedown", start);
  canvas.addEventListener("mousemove", move);
  window.addEventListener("mouseup", end);
  canvas.addEventListener("touchstart", start, { passive: false });
  canvas.addEventListener("touchmove", move, { passive: false });
  canvas.addEventListener("touchend", end);
}

function redraw(canvas, ctx, strokes) {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = "rgba(255,120,190,0.45)";
  ctx.strokeStyle = "rgba(255,120,190,0.45)";
  for (const s of strokes) {
    ctx.lineWidth = s.r * 2;
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.beginPath();
    s.pts.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)));
    ctx.stroke();
    for (const p of s.pts) { ctx.beginPath(); ctx.arc(p.x, p.y, s.r, 0, 7); ctx.fill(); }
  }
}

/** Будує чорно-білу маску (біле = змінити) для бекенду. */
function buildMask() {
  const { canvas, strokes } = state.editing;
  const m = document.createElement("canvas");
  m.width = canvas.width;
  m.height = canvas.height;
  const ctx = m.getContext("2d");
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, m.width, m.height);
  ctx.fillStyle = "#fff";
  ctx.strokeStyle = "#fff";
  for (const s of strokes) {
    ctx.lineWidth = s.r * 2; ctx.lineCap = "round"; ctx.lineJoin = "round";
    ctx.beginPath();
    s.pts.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)));
    ctx.stroke();
    for (const p of s.pts) { ctx.beginPath(); ctx.arc(p.x, p.y, s.r, 0, 7); ctx.fill(); }
  }
  return m.toDataURL("image/png");
}

async function applyInpaint(prompt) {
  if (!state.editing || !state.editing.strokes.length) {
    toast("Спочатку обведи місце, яке хочеш змінити 🖌️");
    return;
  }
  addUserMsg("✏️ " + prompt);
  const bubble = addAssistantMsg();
  bubble.innerHTML = `<p>Змінюю обведене місце… <span class="typing"><span></span><span></span><span></span></span></p>`;
  scrollChat();
  $("sendBtn").disabled = true;

  try {
    const r = await fetch(`${API}/api/inpaint`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ image: state.editing.imgB64, mask: buildMask(), prompt }),
    });
    if (!r.ok) throw new Error("HTTP " + r.status);
    const d = await r.json();
    bubble.querySelector("p").innerHTML = d.mock ? "Готово! (демо-режим) 💛" : `Готово за ${d.elapsed} с! 💛`;
    const gal = el("div", "gallery n1");
    gal.appendChild(makeTile(d.images[0], prompt, d.seeds[0], 1));
    bubble.appendChild(gal);
    setMode("create");
    scrollChat();
  } catch (e) {
    bubble.innerHTML = `<p>Ой, не вдалося змінити 😔 <small>${escapeHtml(String(e.message || e))}</small></p>`;
  } finally {
    $("sendBtn").disabled = false;
  }
}

/* ---------------------- Перемикання режимів ---------------------- */
function setMode(mode) {
  state.mode = mode;
  document.querySelectorAll("#modeSeg .seg-item").forEach((b) => b.classList.toggle("active", b.dataset.mode === mode));
  $("editTools").hidden = mode !== "edit";
  $("optionsPanel").hidden = mode === "edit" ? true : $("optionsPanel").hidden;
  if (mode === "create") {
    state.editing = null;
    $("promptInput").placeholder = "Напиши, що намалювати…";
    $("composerFoot").textContent = "Натисни ✈️ або клавішу Enter, щоб намалювати";
  } else {
    $("composerFoot").textContent = "Обведи місце на картинці й опиши зміну";
  }
}

/* ---------------------- Надсилання ---------------------- */
function send() {
  const input = $("promptInput");
  const text = input.value.trim();
  if (!text) { toast("Напиши кілька слів про те, що хочеш 🌸"); input.focus(); return; }
  input.value = "";
  autoGrow();
  hideIntro();
  if (state.photo) img2img(composePrompt(text));
  else if (state.mode === "edit") applyInpaint(text);
  else if (state.agent.available && state.agent.on) agentTurn(text);
  else generate(composePrompt(text));
}

function hideIntro() {
  const intro = $("introMsg");
  if (intro) intro.style.display = "none";
}

/* ---------------------- Голосовий ввід ---------------------- */
function setupVoice() {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  const btn = $("micBtn");
  if (!SR) { btn.style.display = "none"; return; }
  const rec = new SR();
  rec.lang = "uk-UA";
  rec.interimResults = true;
  let active = false;
  rec.onresult = (e) => {
    const t = Array.from(e.results).map((r) => r[0].transcript).join("");
    $("promptInput").value = t;
    autoGrow();
  };
  rec.onend = () => { active = false; btn.classList.remove("rec"); };
  rec.onerror = () => { active = false; btn.classList.remove("rec"); };
  btn.onclick = () => {
    if (active) { rec.stop(); return; }
    try { rec.start(); active = true; btn.classList.add("rec"); toast("Слухаю… говори 🎤"); }
    catch (_) {}
  };
}

/* ---------------------- Історія (localStorage) ---------------------- */
function loadHistory() {
  try { return JSON.parse(localStorage.getItem("mama_history") || "[]"); }
  catch { return []; }
}
/* Downscale a full image to a tiny JPEG thumbnail so history fits in
   localStorage (full base64 images are ~1-2MB each and overflow the ~5MB quota). */
function makeThumb(dataUrl, size = 128) {
  return new Promise((resolve, reject) => {
    const im = new Image();
    im.onload = () => {
      try {
        const s = Math.min(size / Math.max(im.width, im.height), 1);
        const w = Math.max(1, Math.round(im.width * s));
        const h = Math.max(1, Math.round(im.height * s));
        const c = document.createElement("canvas");
        c.width = w; c.height = h;
        c.getContext("2d").drawImage(im, 0, 0, w, h);
        resolve(c.toDataURL("image/jpeg", 0.7));
      } catch (e) { reject(e); }
    };
    im.onerror = reject;
    im.src = dataUrl;
  });
}
async function saveToHistory(prompt, img) {
  let thumb = "";
  try { thumb = await makeThumb(img, 128); } catch { thumb = ""; }
  state.history.unshift({ prompt, thumb, t: Date.now() });
  state.history = state.history.slice(0, 12);
  try {
    localStorage.setItem("mama_history", JSON.stringify(state.history));
  } catch (e) {
    // Quota exceeded — drop oldest entries until it fits; never crash generation.
    let saved = false;
    while (state.history.length > 1 && !saved) {
      state.history.pop();
      try { localStorage.setItem("mama_history", JSON.stringify(state.history)); saved = true; }
      catch { /* keep dropping */ }
    }
    if (!saved) {
      try { localStorage.setItem("mama_history", JSON.stringify(
        state.history.map((h) => ({ prompt: h.prompt, t: h.t, thumb: "" })))); } catch { /* give up quietly */ }
    }
  }
  renderHistory();
}
function renderHistory() {
  const list = $("historyList");
  list.querySelectorAll(".history-item").forEach((e) => e.remove());
  for (const h of state.history) {
    const item = el("button", "history-item");
    item.innerHTML = `<img class="thumb" src="${h.thumb}" alt=""><span>${escapeHtml(h.prompt.slice(0, 28))}</span>`;
    item.onclick = () => { hideIntro(); setMode("create"); generate(h.prompt); closeSidebar(); };
    list.appendChild(item);
  }
}

/* ---------------------- Сайдбар (мобільний) ---------------------- */
function closeSidebar() { $("sidebar").classList.remove("open"); }

/* ---------------------- Поле, що росте ---------------------- */
function autoGrow() {
  const t = $("promptInput");
  t.style.height = "auto";
  t.style.height = Math.min(t.scrollHeight, 160) + "px";
}

/* ---------------------- Підключення подій ---------------------- */
function init() {
  $("topHello").textContent = greeting();
  checkHealth();
  setInterval(checkHealth, 20000);
  renderHistory();
  setupVoice();
  if (state.agent.key) state.agent.available = true;
  updateHelperToggle();

  $("sendBtn").onclick = send;
  $("promptInput").addEventListener("input", autoGrow);
  $("promptInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
  });

  // підказки-чіпи
  document.querySelectorAll("#ideaChips .chip").forEach((c) => {
    c.onclick = () => { const txt = c.textContent.replace(/^\S+\s/, ""); hideIntro(); generate(txt); };
  });

  // режими
  document.querySelectorAll("#modeSeg .seg-item").forEach((b) => (b.onclick = () => setMode(b.dataset.mode)));

  // налаштування
  $("optBtn").onclick = () => { if (state.mode !== "edit") $("optionsPanel").hidden = !$("optionsPanel").hidden; };
  { const _oc = $("optClose"); if (_oc) _oc.onclick = () => ($("optionsPanel").hidden = true); }
  document.querySelectorAll("#countPills button").forEach((b) => {
    b.onclick = () => {
      document.querySelectorAll("#countPills button").forEach((x) => x.classList.remove("active"));
      b.classList.add("active"); state.count = +b.dataset.count;
    };
  });
  document.querySelectorAll("#ratioPills button").forEach((b) => {
    b.onclick = () => {
      document.querySelectorAll("#ratioPills button").forEach((x) => x.classList.remove("active"));
      b.classList.add("active"); state.width = +b.dataset.w; state.height = +b.dataset.h;
    };
  });
  $("negativeInput").addEventListener("input", (e) => (state.negative = e.target.value));

  // стилі-пресети
  document.querySelectorAll("#styleRow .style-chip").forEach((b) => {
    b.onclick = () => {
      document.querySelectorAll("#styleRow .style-chip").forEach((x) => x.classList.remove("active"));
      b.classList.add("active");
      state.style = b.dataset.style || "";
    };
  });

  // якість (кроки)
  const qLabels = (v) => (v <= 16 ? "швидко" : v <= 28 ? "звичайна" : v <= 40 ? "висока" : "найкраща");
  $("qualitySlider").addEventListener("input", (e) => {
    state.steps = +e.target.value;
    $("qualityVal").textContent = qLabels(state.steps);
  });

  // сила змін (для фото)
  const sLabels = (v) => (v <= 35 ? "трохи" : v <= 65 ? "помірно" : "сильно");
  $("strengthSlider").addEventListener("input", (e) => {
    state.strength = +e.target.value / 100;
    $("strengthVal").textContent = sLabels(+e.target.value);
  });

  // завантаження фото (img2img)
  $("photoBtn").onclick = () => $("photoInput").click();
  $("photoInput").addEventListener("change", (e) => {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    if (file.size > 12 * 1024 * 1024) { toast("Фото завелике (макс. 12 МБ) 🙏"); return; }
    const reader = new FileReader();
    reader.onload = () => { hideIntro(); setPhoto(reader.result); };
    reader.readAsDataURL(file);
  });
  $("attachRemove").onclick = clearPhoto;

  // пензель
  $("brushSize").addEventListener("input", (e) => (state.brush = +e.target.value));
  $("undoMask").onclick = () => {
    if (state.editing && state.editing.strokes.length) {
      state.editing.strokes.pop();
      redraw(state.editing.canvas, state.editing.ctx, state.editing.strokes);
    }
  };
  $("clearMask").onclick = () => {
    if (state.editing) { state.editing.strokes = []; redraw(state.editing.canvas, state.editing.ctx, []); }
  };

  // нова розмова
  $("newChatBtn").onclick = () => {
    document.querySelectorAll("#chat .msg:not(#introMsg)").forEach((m) => m.remove());
    const intro = $("introMsg"); if (intro) intro.style.display = "";
    state.agent.msgs = [];
    clearPhoto(); setMode("create"); closeSidebar();
  };

  // перемикач агентів
  const ht = $("helperToggle");
  if (ht) ht.onclick = () => {
    state.agent.on = !state.agent.on;
    updateHelperToggle();
    toast(state.agent.on ? "Агенти увімкнені 🤖" : "Агенти вимкнені — малюю одразу");
  };

  // налаштування агентів (Ollama токен)
  $("agentSettingsBtn").onclick = openAgentSettings;
  $("asClose").onclick = () => ($("agentSettings").hidden = true);
  $("asSkip").onclick = () => ($("agentSettings").hidden = true);
  $("agentSettings").onclick = (e) => { if (e.target === $("agentSettings")) $("agentSettings").hidden = true; };
  $("asTest").onclick = testAgentKey;
  $("asSave").onclick = saveAgentSettings;

  // Escape закриває будь-яке вікно
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      ["agentSettings", "helpModal", "lightbox"].forEach((id) => {
        const elx = $(id); if (elx) elx.hidden = true;
      });
    }
  });

  // довідка
  $("helpBtn").onclick = () => ($("helpModal").hidden = false);
  $("helpClose").onclick = $("helpOk").onclick = () => ($("helpModal").hidden = true);
  $("helpModal").onclick = (e) => { if (e.target === $("helpModal")) $("helpModal").hidden = true; };

  // меню (мобільний)
  $("menuBtn").onclick = () => $("sidebar").classList.toggle("open");

  autoGrow();
}

document.addEventListener("DOMContentLoaded", init);
