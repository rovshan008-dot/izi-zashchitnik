package main

// Зрение: Изи смотрит на экран компьютера.
//
// Снимок делаем средствами самой Windows (System.Drawing через PowerShell) — без выбиралки
// окна, как в браузере, и без сторонних библиотек. Своё окно на миг прячем, иначе Изи
// увидит вместо твоего графика сам себя.

import (
	"encoding/base64"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

// shotScript — снять весь экран (все мониторы), уменьшить до 1536 по ширине и сохранить в JPEG.
const shotScript = `
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public class IziDpi{[DllImport("user32.dll")]public static extern bool SetProcessDPIAware();}'
[IziDpi]::SetProcessDPIAware() | Out-Null
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
$b = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap($b.Width, $b.Height)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($b.X, $b.Y, 0, 0, $b.Size)
$g.Dispose()
$max = 1536
if ($bmp.Width -gt $max) {
  $h = [int]($bmp.Height * $max / $bmp.Width)
  $r = New-Object System.Drawing.Bitmap($max, $h)
  $g2 = [System.Drawing.Graphics]::FromImage($r)
  $g2.InterpolationMode = 'HighQualityBicubic'
  $g2.DrawImage($bmp, 0, 0, $max, $h)
  $g2.Dispose()
  $bmp.Dispose()
  $bmp = $r
}
$enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$pars = New-Object System.Drawing.Imaging.EncoderParameters(1)
$pars.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [int64]72)
$bmp.Save('%OUT%', $enc, $pars)
$bmp.Dispose()
`

// psEncoded — PowerShell ждёт -EncodedCommand в base64 от UTF-16LE.
// Так не приходится ничего экранировать в командной строке.
func psEncoded(script string) string {
	u := utf16.Encode([]rune(script))
	buf := make([]byte, 0, len(u)*2)
	for _, c := range u {
		buf = append(buf, byte(c), byte(c>>8))
	}
	return base64.StdEncoding.EncodeToString(buf)
}

func runPowerShell(script string, timeout time.Duration) ([]byte, error) {
	cmd := exec.Command("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", psEncoded(script))
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	done := make(chan struct{})
	var out []byte
	var err error
	go func() {
		out, err = cmd.CombinedOutput()
		close(done)
	}()
	select {
	case <-done:
		return out, err
	case <-time.After(timeout):
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		return nil, fmt.Errorf("снимок экрана не успел за %s", timeout)
	}
}

// captureScreen — снимок экрана как data:URL для отправки на сервер.
func captureScreen() (string, error) {
	tmp := filepath.Join(os.TempDir(), fmt.Sprintf("izi-screen-%d.jpg", time.Now().UnixNano()))
	defer os.Remove(tmp)

	script := strings.Replace(shotScript, "%OUT%", strings.ReplaceAll(tmp, "'", "''"), 1)
	if out, err := runPowerShell(script, 25*time.Second); err != nil {
		msg := strings.TrimSpace(string(out))
		if len(msg) > 300 {
			msg = msg[:300]
		}
		if msg != "" {
			return "", fmt.Errorf("%v: %s", err, msg)
		}
		return "", err
	}
	raw, err := os.ReadFile(tmp)
	if err != nil || len(raw) == 0 {
		return "", fmt.Errorf("снимок не получился")
	}
	return "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(raw), nil
}

// LookAtScreen — «посмотри на экран». Прячем своё окно, снимаем, показываем обратно,
// отправляем картинку с вопросом на тот же /chat/izi_see, что и Android.
func (a *App) LookAtScreen(question string) Reply {
	token := a.store.Token()
	if token == "" {
		return Reply{AuthExpired: true}
	}
	if strings.TrimSpace(question) == "" {
		question = "Что сейчас на экране? Коротко и по делу."
	}

	if a.ctx != nil {
		runtime.WindowHide(a.ctx)
		time.Sleep(220 * time.Millisecond)
	}
	img, err := captureScreen()
	if a.ctx != nil {
		runtime.WindowShow(a.ctx)
	}
	if err != nil {
		return Reply{Error: "Не получилось снять экран. " + err.Error()}
	}

	a.store.Add(Msg{ID: nextID(), Mine: true, Text: question})
	r := See(token, question, img)
	if r.AuthExpired {
		a.store.Logout()
		return r
	}
	if r.Text != "" || r.ImageURL != "" {
		a.store.Add(Msg{ID: nextID(), Mine: false, Text: r.Text, Image: r.ImageURL})
	}
	return r
}
