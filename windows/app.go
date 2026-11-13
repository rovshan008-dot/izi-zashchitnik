package main

// Методы, которые вызывает экран (frontend/dist/app.js) через window.go.main.App.

import (
	"context"
	"errors"
	"os/exec"
	"strings"
	"sync"

	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

type App struct {
	ctx      context.Context
	manager  *Manager
	mu       sync.Mutex
	pending  *Link
	quitting bool
}

func NewApp(manager *Manager, args []string) *App {
	return &App{manager: manager, pending: linkFromArgs(args)}
}

// linkFromArgs — ссылка кнопки с сайта, с которой Windows запустила программу.
func linkFromArgs(args []string) *Link {
	for _, arg := range args {
		if strings.HasPrefix(strings.ToLower(arg), linkScheme+":") {
			return ParseLink(arg)
		}
	}
	return nil
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
}

// beforeClose: пока защита включена, крестик сворачивает окно, чтобы защита не выключилась случайно.
func (a *App) beforeClose(ctx context.Context) bool {
	a.mu.Lock()
	quitting := a.quitting
	a.mu.Unlock()
	if quitting || !a.manager.IsRunning() {
		return false
	}
	runtime.WindowMinimise(ctx)
	return true
}

func (a *App) shutdown(ctx context.Context) {
	a.manager.Stop()
}

// secondInstance: программу запустили ещё раз (ярлыком или кнопкой с сайта) — показываем окно.
func (a *App) secondInstance(data options.SecondInstanceData) {
	if a.ctx == nil {
		return
	}
	runtime.WindowUnminimise(a.ctx)
	runtime.WindowShow(a.ctx)
	if link := linkFromArgs(data.Args); link != nil {
		runtime.EventsEmit(a.ctx, "link", link)
	}
}

// ---- вызываются с экрана ----

func (a *App) Version() string { return appVersion }

func (a *App) State() StateView { return a.manager.State() }

func (a *App) Toggle() { a.manager.Toggle() }

// TakePendingLink — ссылка с сайта, пришедшая при запуске; отдаётся экрану один раз.
func (a *App) TakePendingLink() *Link {
	a.mu.Lock()
	defer a.mu.Unlock()
	link := a.pending
	a.pending = nil
	return link
}

// LinkFromClipboard — ссылка из буфера обмена или nil, если в буфере её нет.
func (a *App) LinkFromClipboard() *Link {
	text, err := runtime.ClipboardGetText(a.ctx)
	if err != nil {
		return nil
	}
	return ParseLink(text)
}

func (a *App) ParseLink(text string) *Link { return ParseLink(text) }

func (a *App) Add(link Link) (AddResult, error) {
	parsed := ParseLink(link.URL)
	if parsed == nil {
		return AddResult{}, errors.New("В тексте нет ссылки на подключение.")
	}
	if link.Name != "" {
		parsed.Name = link.Name
	}
	a.manager.SetBusy("add")
	defer a.manager.SetBusy("")
	return a.manager.Add(parsed)
}

func (a *App) Refresh(id string) error {
	a.manager.SetBusy("refresh:" + id)
	defer a.manager.SetBusy("")
	return a.manager.Refresh(id)
}

func (a *App) Rename(id, name string) error { return a.manager.Rename(id, name) }

func (a *App) Remove(id string) error { return a.manager.Remove(id) }

func (a *App) SelectProfile(id string) error { return a.manager.SelectProfile(id) }

func (a *App) SelectServer(tag string) error { return a.manager.SelectServer(tag) }

// ---- «Для мастера» ----

func (a *App) OpenLog() error { return exec.Command("notepad.exe", a.manager.LogPath()).Start() }

func (a *App) OpenDataFolder() error { return exec.Command("explorer.exe", a.manager.Dir()).Start() }

func (a *App) Quit() {
	a.mu.Lock()
	a.quitting = true
	a.mu.Unlock()
	runtime.Quit(a.ctx)
}
