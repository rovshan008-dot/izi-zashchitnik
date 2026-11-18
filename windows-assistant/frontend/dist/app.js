(() => {
  "use strict";
  const App = () => window.go.main.App;

  const screenLogin = document.getElementById("screen-login");
  const screenChat = document.getElementById("screen-chat");
  const codeInput = document.getElementById("code-input");
  const codeBoxes = Array.from(document.querySelectorAll(".code-box"));
  const loginError = document.getElementById("login-error");
  const loginSpinner = document.getElementById("login-spinner");
  const messagesEl = document.getElementById("messages");
  const textInput = document.getElementById("text-input");
  const sendBtn = document.getElementById("send-btn");
  const micBtn = document.getElementById("mic-btn");
  const recIndicator = document.getElementById("rec-indicator");
  const menuBtn = document.getElementById("menu-btn");
  const menu = document.getElementById("menu");
  const logoutBtn = document.getElementById("logout-btn");
  const headerOrb = document.getElementById("header-orb");
  const player = document.getElementById("player");

  // ---------- Перенос ----------
  const tabChatBtn = document.getElementById("tab-chat-btn");
  const tabDropBtn = document.getElementById("tab-drop-btn");
  const tabChat = document.getElementById("tab-chat");
  const tabDrop = document.getElementById("tab-drop");
  const dropZone = document.getElementById("drop-zone");
  const dropTextInput = document.getElementById("drop-text-input");
  const dropSendBtn = document.getElementById("drop-send-btn");
  const dropList = document.getElementById("drop-list");
  const dropEmpty = document.getElementById("drop-empty");
  const dropDialog = document.getElementById("drop-dialog");
  const dropDialogPhone = document.getElementById("drop-dialog-phone");
  const dropDialogAsk = document.getElementById("drop-dialog-ask");
  const dropDialogCancel = document.getElementById("drop-dialog-cancel");
  const IMAGE_EXT = /\.(jpe?g|png|webp|gif|bmp)$/i;

  const TABS = ["chat", "drop", "scr", "scen"];
  const tabBtns = {
    chat: tabChatBtn, drop: tabDropBtn,
    scr: document.getElementById("tab-scr-btn"),
    scen: document.getElementById("tab-scen-btn"),
  };
  const tabPanes = {
    chat: tabChat, drop: tabDrop,
    scr: document.getElementById("tab-scr"),
    scen: document.getElementById("tab-scen"),
  };
  let currentTab = "chat";

  function switchTab(name) {
    if (!TABS.includes(name)) name = "chat";
    currentTab = name;
    TABS.forEach((t) => {
      tabPanes[t].hidden = t !== name;
      tabBtns[t].classList.toggle("active", t === name);
    });
    if (name === "scr") startScreener();
    else stopScreener();
    if (name === "scen") renderScenarios();
  }
  TABS.forEach((t) => tabBtns[t].addEventListener("click", () => switchTab(t)));

  function fileIconSvg() {
    return '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>';
  }
  function linkIconSvg() {
    return '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.07 0l2.83-2.83a5 5 0 0 0-7.07-7.07L11.5 4.5"/><path d="M14 11a5 5 0 0 0-7.07 0L4.1 13.83a5 5 0 0 0 7.07 7.07L12.5 19.5"/></svg>';
  }

  function addDropItem(view) {
    dropEmpty.hidden = true;
    const div = document.createElement("div");
    div.className = "drop-item" + (view.error ? " error" : "");
    const icon = document.createElement("div");
    icon.className = "drop-item-icon";
    icon.innerHTML = view.kind === "file" ? fileIconSvg() : view.kind === "link" ? linkIconSvg() : "";
    const main = document.createElement("div");
    main.className = "drop-item-main";
    const name = document.createElement("div");
    name.className = "drop-item-name";
    const sub = document.createElement("div");
    sub.className = "drop-item-sub";
    if (view.kind === "link") {
      const a = document.createElement("a");
      a.href = view.text; a.textContent = view.text; a.target = "_blank"; a.rel = "noreferrer";
      name.appendChild(a);
      sub.textContent = "ссылка с телефона";
    } else if (view.kind === "text") {
      name.textContent = view.text;
      sub.textContent = "текст с телефона";
    } else {
      name.textContent = view.name || "файл";
      sub.textContent = view.error ? view.error : (view.localPath ? "скачано" : "скачивается…");
    }
    main.appendChild(name);
    main.appendChild(sub);
    div.appendChild(icon);
    div.appendChild(main);
    dropList.prepend(div);
  }

  async function sendFilesToPhone(paths) {
    if (!paths || paths.length === 0) return;
    try {
      const results = await App().SendDropFiles(paths);
      (results || []).forEach((r) => {
        if (!r.ok) addMessageEl(false, "⚠ " + r.name + ": " + (r.error || "не отправилось"));
      });
    } catch (e) {
      addMessageEl(false, "⚠ Не получилось отправить файл на телефон");
    }
  }

  let pendingDialogPath = null;
  function openDropDialog(path) {
    pendingDialogPath = path;
    dropDialog.hidden = false;
  }
  function closeDropDialog() {
    pendingDialogPath = null;
    dropDialog.hidden = true;
  }
  dropDialogCancel.addEventListener("click", closeDropDialog);
  dropDialogPhone.addEventListener("click", async () => {
    const path = pendingDialogPath;
    closeDropDialog();
    if (path) await sendFilesToPhone([path]);
  });
  dropDialogAsk.addEventListener("click", async () => {
    const path = pendingDialogPath;
    closeDropDialog();
    if (!path) return;
    switchTab("chat");
    const typing = addMessageEl(false, "смотрю фото…");
    typing.classList.add("typing");
    setOrbState("thinking");
    try {
      const r = await App().AskAboutPhoto(path, "");
      typing.remove();
      setOrbState("");
      if (handleAuthExpired(r)) return;
      if (r.error) { addMessageEl(false, "⚠ " + r.error); return; }
      if (r.text || r.image_url) {
        addMessageEl(false, r.text, r.image_url);
        if (r.text && !r.silent) speakReply(r.text);
      }
      await loadHistory();
    } catch (e) {
      typing.remove();
      setOrbState("");
      addMessageEl(false, "⚠ Нет связи с сервером");
    }
  });

  async function handleIncomingPaths(paths) {
    if (!paths || paths.length === 0) return;
    if (paths.length === 1 && IMAGE_EXT.test(paths[0])) {
      openDropDialog(paths[0]);
    } else {
      await sendFilesToPhone(paths);
    }
  }

  dropZone.addEventListener("click", async () => {
    try {
      const paths = await App().ChooseFiles();
      await handleIncomingPaths(paths);
    } catch (e) { /* отмена выбора — не ошибка */ }
  });

  // нативный drag&drop окна Wails шлёт событие именно когда файлы отпустили над окном
  if (window.runtime && window.runtime.EventsOn) {
    window.runtime.EventsOn("files-dropped", (paths) => { handleIncomingPaths(paths); });
    window.runtime.EventsOn("transfer-start", (ev) => { onTransferStart(ev); });
    window.runtime.EventsOn("transfer-progress", (ev) => { onTransferProgress(ev); });
    window.runtime.EventsOn("transfer-done", (ev) => { onTransferDone(ev); });
    window.runtime.EventsOn("drop-item", (view) => {
      addDropItem(view);
      // карточка файла ждёт локальный путь, чтобы показать «Открыть» и «Папка»
      if (view.kind === "file") addCardButtons(view.id, view.localPath);
      else if (view.text) flashNote(view.text, view.kind);
    });
  }

  dropSendBtn.addEventListener("click", async () => {
    const text = dropTextInput.value.trim();
    if (!text) return;
    const isLink = /^https?:\/\//i.test(text);
    dropSendBtn.disabled = true;
    try {
      const ok = await App().SendDropText(text, isLink);
      if (ok) dropTextInput.value = "";
      else addMessageEl(false, "⚠ Не получилось отправить на телефон");
    } catch (e) {
      addMessageEl(false, "⚠ Нет связи с сервером");
    }
    dropSendBtn.disabled = false;
  });
  dropTextInput.addEventListener("keydown", (e) => { if (e.key === "Enter") dropSendBtn.click(); });

  function renderCode() {
    const v = codeInput.value;
    codeBoxes.forEach((box, i) => {
      box.textContent = v[i] || "";
      box.classList.toggle("filled", !!v[i]);
      box.classList.toggle("active", i === v.length);
    });
  }

  function showScreen(name) {
    screenLogin.hidden = name !== "login";
    screenChat.hidden = name !== "chat";
  }

  function addMessageEl(mine, text, image) {
    const div = document.createElement("div");
    div.className = "msg " + (mine ? "mine" : "izi");
    if (text) {
      const t = document.createElement("div");
      t.textContent = text;
      div.appendChild(t);
    }
    if (image) {
      const img = document.createElement("img");
      img.src = image;
      div.appendChild(img);
    }
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return div;
  }

  function setOrbState(state) {
    headerOrb.className = "orb small" + (state ? " " + state : "");
  }

  async function loadHistory() {
    const hist = await App().History();
    messagesEl.innerHTML = "";
    (hist || []).forEach((m) => addMessageEl(m.mine, m.text, m.image));
  }

  async function init() {
    const hasToken = await App().HasToken();
    if (hasToken) {
      showScreen("chat");
      await loadHistory();
      textInput.focus();
    } else {
      showScreen("login");
      codeInput.focus();
    }
  }

  // ---------- вход ----------
  codeInput.addEventListener("input", () => {
    codeInput.value = codeInput.value.replace(/\D/g, "").slice(0, 6);
    renderCode();
    loginError.textContent = "";
    if (codeInput.value.length === 6) doLogin();
  });
  document.getElementById("code-row").addEventListener("click", () => codeInput.focus());
  renderCode();

  async function doLogin() {
    loginSpinner.hidden = false;
    loginError.textContent = "";
    try {
      const res = await App().Login(codeInput.value);
      loginSpinner.hidden = true;
      if (res.ok) {
        codeInput.value = "";
        renderCode();
        showScreen("chat");
        await loadHistory();
        textInput.focus();
      } else {
        loginError.textContent = res.error || "Не получилось";
        codeInput.value = "";
        renderCode();
        codeInput.focus();
      }
    } catch (e) {
      loginSpinner.hidden = true;
      loginError.textContent = "Нет связи с сервером";
    }
  }

  // ---------- чат ----------
  function handleAuthExpired(reply) {
    if (reply && reply.auth_expired) {
      showScreen("login");
      codeInput.focus();
      return true;
    }
    return false;
  }

  async function speakReply(text) {
    try {
      const b64 = await App().Speak(text);
      if (b64) {
        player.src = "data:audio/mpeg;base64," + b64;
        player.play().catch(() => {});
        return;
      }
    } catch (e) { /* падаем на голос браузера ниже */ }
    try {
      const u = new SpeechSynthesisUtterance(text);
      u.lang = "ru-RU";
      speechSynthesis.speak(u);
    } catch (e) { /* тишина — не критично */ }
  }

  async function sendText() {
    const text = textInput.value.trim();
    if (!text) return;
    textInput.value = "";
    sendBtn.disabled = true;
    addMessageEl(true, text);
    const typing = addMessageEl(false, "печатает…");
    typing.classList.add("typing");
    setOrbState("thinking");
    try {
      const r = await App().SendText(text);
      typing.remove();
      setOrbState("");
      sendBtn.disabled = false;
      if (handleAuthExpired(r)) return;
      if (r.error) { addMessageEl(false, "⚠ " + r.error); return; }
      if (r.text || r.image_url) {
        addMessageEl(false, r.text, r.image_url);
        if (r.text && !r.silent) speakReply(r.text);
      }
    } catch (e) {
      typing.remove();
      setOrbState("");
      sendBtn.disabled = false;
      addMessageEl(false, "⚠ Нет связи с сервером");
    }
  }

  sendBtn.addEventListener("click", sendText);
  textInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") sendText();
  });

  // ---------- голос (запись и отправка) ----------
  let mediaRecorder = null;
  let chunks = [];
  let recording = false;

  async function startRecording() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      chunks = [];
      const mime = MediaRecorder.isTypeSupported("audio/webm;codecs=opus") ? "audio/webm;codecs=opus" : "audio/webm";
      mediaRecorder = new MediaRecorder(stream, { mimeType: mime });
      mediaRecorder.ondataavailable = (e) => { if (e.data.size > 0) chunks.push(e.data); };
      mediaRecorder.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        recIndicator.hidden = true;
        micBtn.classList.remove("recording");
        if (chunks.length === 0) return;
        const blob = new Blob(chunks, { type: mime });
        const buf = await blob.arrayBuffer();
        const b64 = btoa(String.fromCharCode(...new Uint8Array(buf)));
        setOrbState("thinking");
        const typing = addMessageEl(false, "слушаю…");
        typing.classList.add("typing");
        try {
          const r = await App().SendVoice(b64);
          typing.remove();
          setOrbState("");
          if (handleAuthExpired(r)) return;
          if (r.error) { addMessageEl(false, "⚠ " + r.error); return; }
          if (r.recognized) addMessageEl(true, r.recognized);
          if (r.text || r.image_url) {
            addMessageEl(false, r.text, r.image_url);
            if (r.text && !r.silent) speakReply(r.text);
          }
        } catch (e) {
          typing.remove();
          setOrbState("");
          addMessageEl(false, "⚠ Нет связи с сервером");
        }
      };
      mediaRecorder.start();
      recording = true;
      micBtn.classList.add("recording");
      recIndicator.hidden = false;
      setOrbState("listening");
    } catch (e) {
      addMessageEl(false, "⚠ Нет доступа к микрофону");
    }
  }

  function stopRecording() {
    if (mediaRecorder && recording) {
      recording = false;
      setOrbState("");
      mediaRecorder.stop();
    }
  }

  micBtn.addEventListener("mousedown", startRecording);
  micBtn.addEventListener("mouseup", stopRecording);
  micBtn.addEventListener("mouseleave", () => { if (recording) stopRecording(); });

  // ---------- меню ----------
  menuBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    menu.hidden = !menu.hidden;
  });
  document.addEventListener("click", () => { menu.hidden = true; });
  logoutBtn.addEventListener("click", async () => {
    await App().Logout();
    messagesEl.innerHTML = "";
    menu.hidden = true;
    showScreen("login");
    codeInput.focus();
  });

  // ======================= передача файлов: карточки =======================

  const transfersEl = document.getElementById("transfers");
  const cards = new Map(); // id → {el, fill, pct, sub, startedAt, name}

  function sizeText(b) {
    if (!b || b <= 0) return "";
    const u = ["Б", "КБ", "МБ", "ГБ"];
    let i = 0, v = b;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    const s = v >= 100 || i === 0 ? v.toFixed(0) : v.toFixed(1);
    return s.replace(".", ",") + " " + u[i];
  }

  function speedText(bytes, ms) {
    if (ms < 400 || bytes <= 0) return "";
    const perSec = bytes / (ms / 1000);
    return sizeText(perSec) + "/с";
  }

  function makeCard(ev) {
    const el = document.createElement("div");
    el.className = "tr " + (ev.dir === "down" ? "down" : "up");
    el.innerHTML =
      '<div class="tr-top">' +
        '<div class="tr-arrow">' + (ev.dir === "down" ? "↓" : "↑") + "</div>" +
        '<div class="tr-name"></div><div class="tr-pct"></div>' +
      "</div>" +
      '<div class="tr-sub"></div>' +
      '<div class="tr-bar"><div class="tr-fill"></div></div>';
    el.querySelector(".tr-name").textContent = ev.name || "файл";
    transfersEl.appendChild(el);
    const card = {
      el,
      fill: el.querySelector(".tr-fill"),
      bar: el.querySelector(".tr-bar"),
      pct: el.querySelector(".tr-pct"),
      sub: el.querySelector(".tr-sub"),
      startedAt: Date.now(),
      name: ev.name || "файл",
      dir: ev.dir,
    };
    cards.set(ev.id, card);
    const total = sizeText(ev.total);
    card.sub.textContent = ev.dir === "down" ? "с телефона" + (total ? " · " + total : "") : "на телефон" + (total ? " · " + total : "");
    if (!ev.total) card.bar.classList.add("idk");
    return card;
  }

  function dropCard(id, delay) {
    const card = cards.get(id);
    if (!card) return;
    cards.delete(id);
    setTimeout(() => {
      card.el.classList.add("gone");
      setTimeout(() => card.el.remove(), 260);
    }, delay);
  }

  function onTransferStart(ev) { if (!cards.has(ev.id)) makeCard(ev); }

  function onTransferProgress(ev) {
    const card = cards.get(ev.id) || makeCard(ev);
    const elapsed = Date.now() - card.startedAt;
    if (ev.total > 0) {
      card.bar.classList.remove("idk");
      const p = Math.max(0, Math.min(100, Math.round((ev.done / ev.total) * 100)));
      card.fill.style.width = p + "%";
      card.pct.textContent = p + "%";
      const sp = speedText(ev.done, elapsed);
      card.sub.textContent = sizeText(ev.done) + " из " + sizeText(ev.total) + (sp ? " · " + sp : "");
    } else {
      card.pct.textContent = sizeText(ev.done);
    }
  }

  function onTransferDone(ev) {
    const card = cards.get(ev.id) || makeCard(ev);
    card.bar.classList.remove("idk");
    card.el.classList.remove("up", "down");
    if (ev.error) {
      card.el.classList.add("err");
      card.el.querySelector(".tr-arrow").textContent = "!";
      card.pct.textContent = "";
      card.sub.textContent = ev.error;
      dropCard(ev.id, 9000);
      return;
    }
    card.el.classList.add("ok");
    card.el.querySelector(".tr-arrow").textContent = "✓";
    card.fill.style.width = "100%";
    card.pct.textContent = "";
    const total = sizeText(ev.total);
    const took = ((Date.now() - card.startedAt) / 1000).toFixed(1).replace(".", ",");
    card.sub.textContent = (ev.dir === "down" ? "пришло с телефона" : "ушло на телефон") +
      (total ? " · " + total : "") + " · " + took + " с";
    if (ev.dir !== "down") dropCard(ev.id, 4000);
    // для принятого файла кнопки добавит drop-item, когда придёт локальный путь
  }

  // кнопки «Открыть» / «Папка» для принятого файла
  function addCardButtons(id, localPath) {
    const card = cards.get(id);
    if (!card || !localPath) { dropCard(id, 5000); return; }
    if (card.el.querySelector(".tr-btns")) return;
    const row = document.createElement("div");
    row.className = "tr-btns";
    const open = document.createElement("button");
    open.className = "tr-btn"; open.textContent = "Открыть";
    open.addEventListener("click", () => { App().OpenFile(localPath); dropCard(id, 0); });
    const fold = document.createElement("button");
    fold.className = "tr-btn"; fold.textContent = "Папка";
    fold.addEventListener("click", () => { App().ShowInFolder(localPath); dropCard(id, 0); });
    const hide = document.createElement("button");
    hide.className = "tr-btn"; hide.textContent = "Скрыть";
    hide.addEventListener("click", () => dropCard(id, 0));
    row.appendChild(open); row.appendChild(fold); row.appendChild(hide);
    card.el.appendChild(row);
    dropCard(id, 20000);
  }

  // текст и ссылки с телефона тоже показываем карточкой — иначе их легко пропустить
  function flashNote(text, kind) {
    const id = "note-" + Date.now() + Math.random();
    const card = makeCard({ id, dir: "down", name: kind === "link" ? "Ссылка с телефона" : "Текст с телефона", total: 0 });
    card.bar.remove();
    card.el.classList.remove("down");
    card.el.classList.add("ok");
    card.el.querySelector(".tr-arrow").textContent = "✓";
    card.sub.textContent = text.length > 90 ? text.slice(0, 90) + "…" : text;
    const row = document.createElement("div");
    row.className = "tr-btns";
    const copy = document.createElement("button");
    copy.className = "tr-btn"; copy.textContent = "Скопировать";
    copy.addEventListener("click", () => {
      try { navigator.clipboard.writeText(text); } catch (e) { /* буфер недоступен — не беда */ }
      dropCard(id, 0);
    });
    row.appendChild(copy);
    if (kind === "link") {
      const go = document.createElement("button");
      go.className = "tr-btn"; go.textContent = "Открыть";
      go.addEventListener("click", () => { App().OpenSite(text); dropCard(id, 0); });
      row.appendChild(go);
    }
    const hide = document.createElement("button");
    hide.className = "tr-btn"; hide.textContent = "Скрыть";
    hide.addEventListener("click", () => dropCard(id, 0));
    row.appendChild(hide);
    card.el.appendChild(row);
    dropCard(id, 20000);
  }

  // ======================= скринер =======================

  const scrList = document.getElementById("scr-list");
  const scrTime = document.getElementById("scr-time");
  const chips = Array.from(document.querySelectorAll(".chip"));
  let scrSort = "gainers";
  let scrTimer = null;

  chips.forEach((c) => c.addEventListener("click", () => {
    chips.forEach((x) => x.classList.toggle("active", x === c));
    scrSort = c.dataset.sort;
    loadScreener();
  }));

  function fmtPrice(v) {
    const a = Math.abs(v);
    if (a >= 1000) return v.toLocaleString("ru-RU", { maximumFractionDigits: 0 });
    if (a >= 1) return v.toFixed(2).replace(".", ",");
    if (a >= 0.01) return v.toFixed(4).replace(".", ",");
    return v.toFixed(8).replace(".", ",");
  }

  function fmtBig(v) {
    const a = Math.abs(v);
    if (a >= 1e9) return (v / 1e9).toFixed(1).replace(".", ",") + " млрд";
    if (a >= 1e6) return (v / 1e6).toFixed(0) + " млн";
    if (a >= 1e3) return (v / 1e3).toFixed(0) + " тыс";
    return String(Math.round(v));
  }

  async function loadScreener() {
    try {
      const view = await App().ScreenerTop(scrSort, 40);
      if (!view || !view.ok) {
        scrList.innerHTML = '<p class="scr-err">' + ((view && view.error) || "скринер недоступен") + "</p>";
        return;
      }
      scrTime.textContent = view.updated;
      scrList.innerHTML = "";
      (view.rows || []).forEach((r) => {
        const row = document.createElement("div");
        row.className = "scr-row";
        const sym = document.createElement("div");
        sym.className = "scr-sym";
        const b = document.createElement("b"); b.textContent = r.sym;
        const sp = document.createElement("span"); sp.textContent = "объём " + fmtBig(r.vol) + " $";
        sym.appendChild(b); sym.appendChild(sp);
        const price = document.createElement("div");
        price.className = "scr-price"; price.textContent = fmtPrice(r.price) + " $";
        const chg = document.createElement("div");
        chg.className = "scr-chg " + (r.chg >= 0 ? "up" : "down");
        chg.textContent = (r.chg >= 0 ? "+" : "") + r.chg.toFixed(2).replace(".", ",") + "%";
        row.appendChild(sym); row.appendChild(price); row.appendChild(chg);
        row.addEventListener("click", () => askCoin(r.sym));
        scrList.appendChild(row);
      });
    } catch (e) {
      scrList.innerHTML = '<p class="scr-err">нет связи со скринером</p>';
    }
  }

  function startScreener() {
    loadScreener();
    if (!scrTimer) scrTimer = setInterval(loadScreener, 5000);
  }
  function stopScreener() {
    if (scrTimer) { clearInterval(scrTimer); scrTimer = null; }
  }

  async function askCoin(sym) {
    switchTab("chat");
    const text = await App().ScreenerCoin(sym);
    if (!text) { addMessageEl(false, "Не нашёл " + sym + " в скринере"); return; }
    addMessageEl(true, "что по " + sym);
    addMessageEl(false, text);
  }

  // ======================= сценарии =======================

  function icoPlay() {
    return '<svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor"><path d="M7 4.5v15l12-7.5z"/></svg>';
  }
  function icoPencil() {
    return '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>';
  }
  function icoCross() {
    return '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>';
  }


  const scenList = document.getElementById("scen-list");
  const scenEditor = document.getElementById("scen-editor");
  const scenName = document.getElementById("scen-name");
  const scenPhrases = document.getElementById("scen-phrases");
  const scenStepsEl = document.getElementById("scen-steps");
  const STEP_KINDS = [
    ["app", "Открыть программу"],
    ["site", "Открыть сайт"],
    ["folder", "Открыть папку"],
    ["say", "Сказать фразу"],
    ["coin", "Срез по монете"],
    ["screener", "Сводка рынка"],
    ["wait", "Подождать, сек"],
    ["cmd", "Команда"],
  ];
  const STEP_HINT = {
    app: "телеграм", site: "скринер", folder: "загрузки", say: "С добрым утром",
    coin: "биток", screener: "— ничего вводить не надо", wait: "3", cmd: "shutdown /h",
  };
  let editingId = "";

  function kindLabel(k) {
    const f = STEP_KINDS.find((x) => x[0] === k);
    return f ? f[1] : k;
  }

  function addStepRow(step) {
    const row = document.createElement("div");
    row.className = "step-row";
    const sel = document.createElement("select");
    sel.className = "step-kind";
    STEP_KINDS.forEach(([k, label]) => {
      const o = document.createElement("option");
      o.value = k; o.textContent = label;
      sel.appendChild(o);
    });
    const inp = document.createElement("input");
    inp.className = "text-input";
    const del = document.createElement("button");
    del.className = "mini-btn danger"; del.innerHTML = icoCross(); del.title = "Убрать шаг";
    del.addEventListener("click", () => row.remove());
    function syncHint() {
      inp.placeholder = STEP_HINT[sel.value] || "";
      inp.disabled = sel.value === "screener";
      if (inp.disabled) inp.value = "";
    }
    sel.addEventListener("change", syncHint);
    sel.value = (step && step.kind) || "app";
    inp.value = (step && step.value) || "";
    syncHint();
    row.appendChild(sel); row.appendChild(inp); row.appendChild(del);
    scenStepsEl.appendChild(row);
  }

  function openEditor(sc) {
    editingId = (sc && sc.id) || "";
    scenName.value = (sc && sc.name) || "";
    scenPhrases.value = (sc && (sc.phrases || []).join(", ")) || "";
    scenStepsEl.innerHTML = "";
    const steps = (sc && sc.steps) || [];
    if (steps.length === 0) addStepRow(null);
    else steps.forEach(addStepRow);
    scenEditor.hidden = false;
    scenName.focus();
  }
  function closeEditor() {
    scenEditor.hidden = true;
    editingId = "";
  }

  document.getElementById("scen-new-btn").addEventListener("click", () => openEditor(null));
  document.getElementById("scen-add-step").addEventListener("click", () => addStepRow(null));
  document.getElementById("scen-cancel").addEventListener("click", closeEditor);
  document.getElementById("scen-save").addEventListener("click", async () => {
    const steps = Array.from(scenStepsEl.querySelectorAll(".step-row")).map((row) => ({
      kind: row.querySelector(".step-kind").value,
      value: row.querySelector(".text-input").value.trim(),
    })).filter((st) => st.value !== "" || st.kind === "screener");
    const phrases = scenPhrases.value.split(",").map((x) => x.trim()).filter(Boolean);
    if (!scenName.value.trim() || phrases.length === 0 || steps.length === 0) {
      addMessageEl(false, "Сценарию нужны название, хотя бы одна фраза и хотя бы один шаг");
      return;
    }
    await App().SaveScenario({ id: editingId, name: scenName.value.trim(), phrases, steps });
    closeEditor();
    await renderScenarios();
  });

  async function renderScenarios() {
    let list = [];
    try { list = (await App().Scenarios()) || []; } catch (e) { list = []; }
    scenList.innerHTML = "";
    list.forEach((sc) => {
      const card = document.createElement("div");
      card.className = "scen-card";
      const top = document.createElement("div");
      top.className = "scen-card-top";
      const name = document.createElement("b");
      name.textContent = sc.name;
      const run = document.createElement("button");
      run.className = "mini-btn"; run.innerHTML = icoPlay(); run.title = "Запустить";
      run.addEventListener("click", async () => {
        switchTab("chat");
        const ans = await App().RunScenario(sc.id);
        if (ans) addMessageEl(false, ans);
      });
      const edit = document.createElement("button");
      edit.className = "mini-btn"; edit.innerHTML = icoPencil(); edit.title = "Изменить";
      edit.addEventListener("click", () => openEditor(sc));
      const del = document.createElement("button");
      del.className = "mini-btn danger"; del.innerHTML = icoCross(); del.title = "Удалить";
      del.addEventListener("click", async () => {
        await App().DeleteScenario(sc.id);
        await renderScenarios();
      });
      top.appendChild(name); top.appendChild(run); top.appendChild(edit); top.appendChild(del);
      const phr = document.createElement("p");
      phr.className = "scen-phr";
      phr.textContent = "«" + (sc.phrases || []).join("», «") + "»";
      const steps = document.createElement("p");
      steps.className = "scen-steps-line";
      steps.textContent = (sc.steps || []).map((st) =>
        kindLabel(st.kind) + (st.value ? ": " + st.value : "")).join(" → ");
      card.appendChild(top); card.appendChild(phr); card.appendChild(steps);
      scenList.appendChild(card);
    });
  }

  init();
})();
