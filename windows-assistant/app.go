package main

// Методы, которые вызывает экран (frontend/dist/app.js) через window.go.main.App.

import (
	"context"
	"encoding/base64"
	"sync"
	"time"

	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

type App struct {
	ctx      context.Context
	store    *Store
	mu       sync.Mutex
	quitting bool
}

func NewApp(store *Store) *App {
	return &App{store: store}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
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
		a.store.Add(Msg{ID: nextID(), Mine: true, Text: r.Recognized})
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
