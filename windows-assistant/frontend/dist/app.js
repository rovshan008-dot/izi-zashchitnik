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

  function switchTab(name) {
    tabChat.hidden = name !== "chat";
    tabDrop.hidden = name !== "drop";
    tabChatBtn.classList.toggle("active", name === "chat");
    tabDropBtn.classList.toggle("active", name === "drop");
  }
  tabChatBtn.addEventListener("click", () => switchTab("chat"));
  tabDropBtn.addEventListener("click", () => switchTab("drop"));

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
    window.runtime.EventsOn("drop-item", (view) => { addDropItem(view); });
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

  init();
})();
