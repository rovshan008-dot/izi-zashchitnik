package main

// «Привет, Изи» на Windows.
//
// Слушаем встроенным распознавателем Windows (System.Speech) — офлайн, бесплатно и
// только заданные фразы, поэтому разговор в комнате никуда не уходит и ничего не стоит.
// Нужен русский речевой пакет Windows; если его нет, честно говорим об этом в окне,
// а звать Изи можно горячей клавишей.

import (
	"bufio"
	"os/exec"
	"strings"
	"sync"
	"syscall"
)

const wakeScript = `
$ErrorActionPreference = 'Stop'
try { Add-Type -AssemblyName System.Speech } catch { Write-Output 'IZI-NO-SPEECH'; exit 1 }
try {
  $culture = New-Object System.Globalization.CultureInfo('ru-RU')
  $rec = New-Object System.Speech.Recognition.SpeechRecognitionEngine($culture)
} catch {
  Write-Output 'IZI-NO-RU'
  exit 1
}
try {
  $choices = New-Object System.Speech.Recognition.Choices
  $choices.Add('привет изи')
  $choices.Add('привет иззи')
  $choices.Add('привет изя')
  $choices.Add('эй изи')
  $choices.Add('слушай изи')
  $choices.Add('изи проснись')
  $gb = New-Object System.Speech.Recognition.GrammarBuilder
  $gb.Culture = $rec.RecognizerInfo.Culture
  $gb.Append($choices)
  $rec.LoadGrammar((New-Object System.Speech.Recognition.Grammar($gb)))
  $rec.SetInputToDefaultAudioDevice()
} catch {
  Write-Output 'IZI-NO-MIC'
  exit 1
}
Write-Output 'IZI-READY'
[Console]::Out.Flush()
while ($true) {
  try { $res = $rec.Recognize() } catch { Start-Sleep -Milliseconds 400; continue }
  if ($res -ne $null -and $res.Confidence -ge 0.55) {
    Write-Output 'IZI-WAKE'
    [Console]::Out.Flush()
  }
}
`

type wakeState struct {
	mu      sync.Mutex
	cmd     *exec.Cmd
	running bool
	status  string // ready | no-ru | no-speech | no-mic | off | error
}

var wake = &wakeState{status: "off"}

// WakeStatus — что показать в окне: слушает ли Изи фразу «Привет, Изи».
func (a *App) WakeStatus() string {
	wake.mu.Lock()
	defer wake.mu.Unlock()
	return wake.status
}

func (a *App) setWakeStatus(s string) {
	wake.mu.Lock()
	wake.status = s
	wake.mu.Unlock()
	a.emit("wake-status", s)
}

// StartWake — включить ожидание фразы. Можно звать повторно, второй раз ничего не делает.
func (a *App) StartWake() string {
	wake.mu.Lock()
	if wake.running {
		st := wake.status
		wake.mu.Unlock()
		return st
	}
	cmd := exec.Command("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", psEncoded(wakeScript))
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	out, err := cmd.StdoutPipe()
	if err != nil {
		wake.mu.Unlock()
		a.setWakeStatus("error")
		return "error"
	}
	if err := cmd.Start(); err != nil {
		wake.mu.Unlock()
		a.setWakeStatus("error")
		return "error"
	}
	wake.cmd = cmd
	wake.running = true
	wake.status = "starting"
	wake.mu.Unlock()

	go func() {
		sc := bufio.NewScanner(out)
		for sc.Scan() {
			switch strings.TrimSpace(sc.Text()) {
			case "IZI-READY":
				a.setWakeStatus("ready")
			case "IZI-WAKE":
				a.onWake()
			case "IZI-NO-RU":
				a.setWakeStatus("no-ru")
			case "IZI-NO-SPEECH":
				a.setWakeStatus("no-speech")
			case "IZI-NO-MIC":
				a.setWakeStatus("no-mic")
			}
		}
		_ = cmd.Wait()
		wake.mu.Lock()
		wake.running = false
		stillReady := wake.status == "ready" || wake.status == "starting"
		wake.mu.Unlock()
		if stillReady {
			a.setWakeStatus("off")
		}
	}()
	return "starting"
}

// StopWake — перестать слушать фразу.
func (a *App) StopWake() {
	wake.mu.Lock()
	cmd := wake.cmd
	wake.running = false
	wake.cmd = nil
	wake.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
	a.setWakeStatus("off")
}

// onWake — услышали «Привет, Изи» (или нажали горячую клавишу): показываем окно и слушаем вопрос.
func (a *App) onWake() {
	a.showWindow()
	a.emit("wake", true)
}
