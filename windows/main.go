package main

// Изи Защитник для Windows: окно приложения.

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
	dataDir := filepath.Join(base, "IziZashchitnik")
	manager, err := NewManager(dataDir)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	app := NewApp(manager, os.Args[1:])

	err = wails.Run(&options.App{
		Title:            "Изи Защитник",
		Width:            420,
		Height:           860,
		MinWidth:         360,
		MinHeight:        560,
		BackgroundColour: &options.RGBA{R: 8, G: 12, B: 18, A: 255},
		AssetServer:      &assetserver.Options{Assets: assets},
		OnStartup:        app.startup,
		OnBeforeClose:    app.beforeClose,
		OnShutdown:       app.shutdown,
		SingleInstanceLock: &options.SingleInstanceLock{
			UniqueId:               "tech.izi20.zashchitnik",
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
