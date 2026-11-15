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
