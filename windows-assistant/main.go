package main

// Изи для Windows: окно голосового помощника (текст + голос, мозг на izi20.tech).

import (
	"embed"
	"fmt"
	"os"
	"path/filepath"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/windows"
)

//go:embed all:frontend/dist
var assets embed.FS

// appVersion подставляется при сборке: -X main.appVersion=...
var appVersion = "dev"

func main() {
	base, err := os.UserConfigDir()
	if err != nil {
		base = "."
	}
	dataDir := filepath.Join(base, "IziAssistant")
	store, err := NewStore(dataDir)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	app := NewApp(store)

	err = wails.Run(&options.App{
		Title:            "Изи",
		Width:            420,
		Height:           760,
		MinWidth:         360,
		MinHeight:        560,
		BackgroundColour: &options.RGBA{R: 7, G: 7, B: 11, A: 255},
		AssetServer:      &assetserver.Options{Assets: assets},
		OnStartup:        app.startup,
		OnBeforeClose:    app.beforeClose,
		OnShutdown:       app.shutdown,
		SingleInstanceLock: &options.SingleInstanceLock{
			UniqueId:               "tech.izi20.assistant.windows",
			OnSecondInstanceLaunch: app.secondInstance,
		},
		Bind: []interface{}{app},
		Windows: &windows.Options{
			Theme:               windows.Dark,
			WebviewUserDataPath: filepath.Join(dataDir, "webview"),
		},
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
	}
}
