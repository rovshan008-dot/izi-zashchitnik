package main

// Методы, которые вызывает экран (frontend/dist/app.js) через window.go.main.App.

import (
	"context"
	"encoding/base64"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"

	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

type App struct {
	ctx         context.Context
	store       *Store
	receiveDir  string
	mu          sync.Mutex
	quitting    bool
	dropPolling bool
	hotkeyOK    bool
}

func NewApp(store *Store) *App {
	return &App{store: store}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	base, err := os.UserConfigDir()
	if err != nil {
		base = "."
	}
	a.receiveDir = filepath.Join(base, "IziAssistant", "Перенос")
	_ = os.MkdirAll(a.receiveDir, 0o755)
	a.maybeStartDropPolling()

	// Ctrl+Shift+Пробел — позвать Изи откуда угодно
	a.hotkeyOK = startHotkey(a.onWake)

	// «Привет, Изи» — если человек его не выключал
	if a.store.WakeOn() {
		go a.StartWake()
	}
}

// showWindow — поднять окно поверх остальных и развернуть, если было свёрнуто.
func (a *App) showWindow() {
	if a.ctx == nil {
		return
	}
	runtime.WindowShow(a.ctx)
	runtime.WindowUnminimise(a.ctx)
}

// HotkeyWorks — удалось ли занять Ctrl+Shift+Пробел (могла забрать другая программа).
func (a *App) HotkeyWorks() bool { return a.hotkeyOK }

// WakeEnabled / SetWakeEnabled — переключатель «Привет, Изи» в окне.
func (a *App) WakeEnabled() bool { return a.store.WakeOn() }

func (a *App) SetWakeEnabled(on bool) string {
	a.store.SetWakeOn(on)
	if !on {
		a.StopWake()
		return "off"
	}
	return a.StartWake()
}

// domReady: регистрируем приём файлов, которые перетащили прямо в окно (нативный drag&drop Wails).
func (a *App) domReady(ctx context.Context) {
	runtime.OnFileDrop(ctx, a.onFileDrop)
}

func (a *App) onFileDrop(x, y int, paths []string) {
	runtime.EventsEmit(a.ctx, "files-dropped", paths)
}

// beforeClose: крестик сворачивает окно, а не закрывает Изи — чтобы не терять «Привет, Изи» в фоне
// (когда он появится) и историю разговора.
func (a *App) beforeClose(ctx context.Context) bool {
	a.mu.Lock()
	quitting := a.quitting
	a.mu.Unlock()
	if quitting {
		return false
	}
	runtime.WindowHide(ctx)
	return true
}

func (a *App) shutdown(ctx context.Context) {}

// secondInstance: программу запустили ещё раз — просто показываем окно.
func (a *App) secondInstance(data options.SecondInstanceData) {
	if a.ctx == nil {
		return
	}
	runtime.WindowShow(a.ctx)
	runtime.WindowUnminimise(a.ctx)
}

func (a *App) Quit() {
	a.mu.Lock()
	a.quitting = true
	a.mu.Unlock()
	runtime.Quit(a.ctx)
}

// ---------- вызываются с экрана ----------

func (a *App) Version() string { return appVersion }

type LoginResult struct {
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
}

func (a *App) HasToken() bool { return a.store.Token() != "" }

func (a *App) History() []Msg { return a.store.History() }

func (a *App) Login(code string) LoginResult {
	token, err := Link(code)
	if err != nil {
		return LoginResult{OK: false, Error: err.Error()}
	}
	a.store.SetToken(token)
	a.maybeStartDropPolling()
	return LoginResult{OK: true}
}

func (a *App) Logout() {
	a.store.Logout()
}

var msgSeq = time.Now().UnixNano()

func nextID() int64 {
	msgSeq++
	return msgSeq
}

// SendText — отправка обычного текстового сообщения. Возвращает Reply как map (проще для JS).
func (a *App) SendText(text string) Reply {
	token := a.store.Token()
	if token == "" {
		return Reply{AuthExpired: true}
	}
	// «посмотри на экран» — снимаем экран и отправляем картинку вместе с вопросом
	if ParseIntent(text).Kind == "screen" {
		return a.LookAtScreen(text)
	}
	// «открой телеграм», «что по битку» — это компьютер умеет сам: быстрее и без расхода токенов.
	if ans := a.localAnswer(text); ans != "" {
		a.store.Add(Msg{ID: nextID(), Mine: true, Text: text})
		a.store.Add(Msg{ID: nextID(), Mine: false, Text: ans})
		return Reply{Text: ans}
	}
	a.store.Add(Msg{ID: nextID(), Mine: true, Text: text})
	r := Chat(token, text)
	if r.AuthExpired {
		a.store.Logout()
		return r
	}
	if r.Text != "" || r.ImageURL != "" {
		a.store.Add(Msg{ID: nextID(), Mine: false, Text: r.Text, Image: r.ImageURL})
	}
	return r
}

// SendVoice — голосовая запись из браузерного MediaRecorder, base64 (webm/opus).
func (a *App) SendVoice(base64Audio string) Reply {
	token := a.store.Token()
	if token == "" {
		return Reply{AuthExpired: true}
	}
	raw, err := base64.StdEncoding.DecodeString(base64Audio)
	if err != nil || len(raw) == 0 {
		return Reply{Error: "Не получилось записать голос"}
	}
	r := Voice(token, raw, "voice.webm", "audio/webm")
	if r.AuthExpired {
		a.store.Logout()
		return r
	}
	if r.Recognized != "" {
		// распознавание речи живёт на сервере, поэтому команду разбираем уже после ответа
		if ParseIntent(r.Recognized).Kind == "screen" {
			// голосом попросили посмотреть на экран — спрашиваем заново, уже с картинкой
			sr := a.LookAtScreen(r.Recognized)
			if sr.Error == "" && sr.Text != "" {
				sr.Recognized = r.Recognized
				return sr
			}
		}
		a.store.Add(Msg{ID: nextID(), Mine: true, Text: r.Recognized})
		// если это команда для самого компьютера, ответ модели заменяем на своё действие
		if ans := a.localAnswer(r.Recognized); ans != "" {
			r.Text, r.ImageURL = ans, ""
		}
	}
	if r.Text != "" || r.ImageURL != "" {
		a.store.Add(Msg{ID: nextID(), Mine: false, Text: r.Text, Image: r.ImageURL})
	}
	return r
}

// Speak — голос Изи для фразы text; "" если сервер не смог озвучить (тогда скажет браузер сам).
func (a *App) Speak(text string) string {
	token := a.store.Token()
	if token == "" {
		return ""
	}
	audio := TTS(token, text)
	if audio == nil {
		return ""
	}
	return base64.StdEncoding.EncodeToString(audio)
}

// ---------- Перенос (телефон ↔ компьютер) ----------

// ChooseFiles — нативный диалог выбора файлов (кнопка/клик по зоне "Перенос").
func (a *App) ChooseFiles() []string {
	paths, err := runtime.OpenMultipleFilesDialog(a.ctx, runtime.OpenDialogOptions{
		Title: "Выбери файлы для переноса на телефон",
	})
	if err != nil {
		return nil
	}
	return paths
}

type DropSendResult struct {
	Name  string `json:"name"`
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
}

// SendDropFiles — отправить один или несколько файлов на телефон.
func (a *App) SendDropFiles(paths []string) []DropSendResult {
	token := a.store.Token()
	out := make([]DropSendResult, 0, len(paths))
	if token == "" {
		for _, p := range paths {
			out = append(out, DropSendResult{Name: filepath.Base(p), OK: false, Error: "не выполнен вход"})
		}
		return out
	}
	for _, p := range paths {
		name := filepath.Base(p)
		id := fmt.Sprintf("up-%d", nextID())
		var size int64
		if st, err := os.Stat(p); err == nil {
			size = st.Size()
		}
		a.emit("transfer-start", TransferEvent{ID: id, Dir: "up", Name: name, Total: size})
		err := DropSendFile(token, p, func(done, total int64) {
			a.emit("transfer-progress", TransferEvent{ID: id, Dir: "up", Name: name, Done: done, Total: total})
		})
		if err != nil {
			a.emit("transfer-done", TransferEvent{ID: id, Dir: "up", Name: name, Total: size, Error: err.Error()})
			out = append(out, DropSendResult{Name: name, OK: false, Error: err.Error()})
			continue
		}
		a.emit("transfer-done", TransferEvent{ID: id, Dir: "up", Name: name, Total: size, Done: size, OK: true})
		out = append(out, DropSendResult{Name: name, OK: true})
	}
	return out
}

// TransferEvent — то, что показывает карточка передачи файла на экране.
type TransferEvent struct {
	ID    string `json:"id"`
	Dir   string `json:"dir"` // "up" — на телефон, "down" — с телефона
	Name  string `json:"name"`
	Done  int64  `json:"done"`
	Total int64  `json:"total"`
	OK    bool   `json:"ok,omitempty"`
	Error string `json:"error,omitempty"`
}

func (a *App) emit(name string, data any) {
	if a.ctx != nil {
		runtime.EventsEmit(a.ctx, name, data)
	}
}

// SendDropText — отправить на телефон текст или ссылку.
func (a *App) SendDropText(text string, isLink bool) bool {
	token := a.store.Token()
	if token == "" {
		return false
	}
	return DropSendText(token, text, isLink)
}

// AskAboutPhoto — "Спросить Изи про это фото": файл с компьютера уходит прямо в чат как вопрос с картинкой.
func (a *App) AskAboutPhoto(path, question string) Reply {
	token := a.store.Token()
	if token == "" {
		return Reply{AuthExpired: true}
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return Reply{Error: "Не получилось открыть файл"}
	}
	mime := "image/jpeg"
	switch filepath.Ext(path) {
	case ".png":
		mime = "image/png"
	case ".webp":
		mime = "image/webp"
	case ".gif":
		mime = "image/gif"
	}
	dataURL := "data:" + mime + ";base64," + base64.StdEncoding.EncodeToString(raw)
	if question == "" {
		question = "Что на этом фото?"
	}
	a.store.Add(Msg{ID: nextID(), Mine: true, Text: question, Image: dataURL})
	r := See(token, question, dataURL)
	if r.AuthExpired {
		a.store.Logout()
		return r
	}
	if r.Text != "" || r.ImageURL != "" {
		a.store.Add(Msg{ID: nextID(), Mine: false, Text: r.Text, Image: r.ImageURL})
	}
	return r
}

// maybeStartDropPolling — фоновый long-poll "что пришло с телефона", запускается один раз после входа.
func (a *App) maybeStartDropPolling() {
	a.mu.Lock()
	if a.dropPolling || a.ctx == nil || a.store.Token() == "" {
		a.mu.Unlock()
		return
	}
	a.dropPolling = true
	a.mu.Unlock()
	go a.dropPollLoop()
}

func (a *App) dropPollLoop() {
	for {
		token := a.store.Token()
		if token == "" {
			a.mu.Lock()
			a.dropPolling = false
			a.mu.Unlock()
			return
		}
		since := a.store.DropSince()
		poll := DropFetch(token, since, true)
		if poll.AuthExpired {
			a.store.Logout()
			a.mu.Lock()
			a.dropPolling = false
			a.mu.Unlock()
			return
		}
		if poll.Now > 0 {
			a.store.SetDropSince(poll.Now)
		}
		for _, item := range poll.Items {
			a.handleDropItem(token, item)
		}
		if len(poll.Items) == 0 {
			time.Sleep(1500 * time.Millisecond)
		}
	}
}

// DropItemView — то, что уходит на экран (для файлов — уже с локальным путём, скачано).
type DropItemView struct {
	ID        string `json:"id"`
	Kind      string `json:"kind"`
	Text      string `json:"text,omitempty"`
	Name      string `json:"name,omitempty"`
	Mime      string `json:"mime,omitempty"`
	Size      int64  `json:"size,omitempty"`
	LocalPath string `json:"localPath,omitempty"`
	Error     string `json:"error,omitempty"`
}

func (a *App) handleDropItem(token string, item DropItem) {
	view := DropItemView{ID: item.ID, Kind: item.Kind, Text: item.Text, Name: item.Name, Mime: item.Mime, Size: item.Size}
	if item.Kind == "file" {
		dest := filepath.Join(a.receiveDir, uniqueName(a.receiveDir, item.Name))
		name := item.Name
		if name == "" {
			name = "файл"
		}
		a.emit("transfer-start", TransferEvent{ID: item.ID, Dir: "down", Name: name, Total: item.Size})
		err := DropDownload(token, item.ID, dest, func(done, total int64) {
			if total <= 0 {
				total = item.Size
			}
			a.emit("transfer-progress", TransferEvent{ID: item.ID, Dir: "down", Name: name, Done: done, Total: total})
		})
		if err != nil {
			view.Error = "Не скачалось: " + err.Error()
			a.emit("transfer-done", TransferEvent{ID: item.ID, Dir: "down", Name: name, Total: item.Size, Error: view.Error})
		} else {
			view.LocalPath = dest
			a.emit("transfer-done", TransferEvent{ID: item.ID, Dir: "down", Name: name, Total: item.Size, Done: item.Size, OK: true})
		}
	}
	a.emit("drop-item", view)
}

func uniqueName(dir, name string) string {
	if name == "" {
		name = "файл"
	}
	candidate := name
	ext := filepath.Ext(name)
	base := name[:len(name)-len(ext)]
	for i := 1; ; i++ {
		if _, err := os.Stat(filepath.Join(dir, candidate)); os.IsNotExist(err) {
			return candidate
		}
		candidate = fmt.Sprintf("%s (%d)%s", base, i, ext)
	}
}

// OpenReceivedFolder — открыть папку "Перенос" с скачанными файлами в проводнике Windows.
func (a *App) OpenReceivedFolder() {
	_ = exec.Command("explorer", a.receiveDir).Start()
}

// OpenFile — открыть скачанный файл тем, чем его открывает Windows (кнопка «Открыть» в карточке).
func (a *App) OpenFile(path string) bool {
	if path == "" {
		return false
	}
	if _, err := os.Stat(path); err != nil {
		return false
	}
	return launch(path) == nil
}

// ShowInFolder — открыть проводник и выделить в нём файл (кнопка «Папка»).
func (a *App) ShowInFolder(path string) bool {
	if path == "" {
		return false
	}
	if _, err := os.Stat(path); err != nil {
		return false
	}
	return exec.Command("explorer", "/select,"+path).Start() == nil
}
