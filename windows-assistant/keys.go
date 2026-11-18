package main

// Руки: нажатия клавиш, громкость, музыка, окна, сон и выключение.
// Всё через SendInput — те же события, что от настоящей клавиатуры, поэтому работает
// в любой программе, которая сейчас впереди.

import (
	"os/exec"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

var procSendInput = user32.NewProc("SendInput")

type keyboardInput struct {
	wVk         uint16
	wScan       uint16
	dwFlags     uint32
	time        uint32
	dwExtraInfo uintptr
}

// input — WinAPI INPUT. На 64 бита это 40 байт: тип (4) + выравнивание (4) + объединение (32).
// KEYBDINPUT занимает 24, остальное добиваем, иначе SendInput молча ничего не сделает.
type input struct {
	typ uint32
	_   uint32
	ki  keyboardInput
	_   [8]byte
}

// Если размер вдруг не 40 — программа не соберётся, и мы узнаем об этом здесь,
// а не из «кнопки не работают» на чужом компьютере.
var (
	_ [unsafe.Sizeof(input{}) - 40]byte
	_ [40 - unsafe.Sizeof(input{})]byte
)

const (
	inputKeyboard   = 1
	keyEventKeyUp   = 0x0002
	keyEventUnicode = 0x0004
)

// коды клавиш, которые нужны помощнику
var vkNames = map[string]uint16{
	"ctrl": 0x11, "control": 0x11, "контрол": 0x11,
	"shift": 0x10, "шифт": 0x10,
	"alt": 0x12, "альт": 0x12,
	"win": 0x5B, "винда": 0x5B,
	"enter": 0x0D, "ввод": 0x0D,
	"esc": 0x1B, "escape": 0x1B, "эскейп": 0x1B,
	"tab": 0x09, "таб": 0x09,
	"space": 0x20, "пробел": 0x20,
	"backspace": 0x08, "delete": 0x2E, "del": 0x2E, "удалить": 0x2E,
	"up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27,
	"вверх": 0x26, "вниз": 0x28, "влево": 0x25, "вправо": 0x27,
	"home": 0x24, "end": 0x23, "pgup": 0x21, "pgdn": 0x22,
	"f1": 0x70, "f2": 0x71, "f3": 0x72, "f4": 0x73, "f5": 0x74, "f6": 0x75,
	"f7": 0x76, "f8": 0x77, "f9": 0x78, "f10": 0x79, "f11": 0x7A, "f12": 0x7B,
	"volup": 0xAF, "voldown": 0xAE, "mute": 0xAD,
	"play": 0xB3, "next": 0xB0, "prev": 0xB1, "stop": 0xB2,
	"printscreen": 0x2C,
}

func sendInputs(ins []input) bool {
	if len(ins) == 0 {
		return false
	}
	n, _, _ := procSendInput.Call(uintptr(len(ins)), uintptr(unsafe.Pointer(&ins[0])), unsafe.Sizeof(ins[0]))
	return int(n) == len(ins)
}

func keyDown(vk uint16) input {
	return input{typ: inputKeyboard, ki: keyboardInput{wVk: vk}}
}

func keyUp(vk uint16) input {
	return input{typ: inputKeyboard, ki: keyboardInput{wVk: vk, dwFlags: keyEventKeyUp}}
}

// tapKey — нажать и отпустить одну клавишу (громкость, пауза, следующий трек).
func tapKey(vk uint16) bool {
	return sendInputs([]input{keyDown(vk), keyUp(vk)})
}

// comboKey — сочетание: модификаторы зажимаем, основную нажимаем, отпускаем в обратном порядке.
func comboKey(mods []uint16, key uint16) bool {
	var ins []input
	for _, m := range mods {
		ins = append(ins, keyDown(m))
	}
	ins = append(ins, keyDown(key), keyUp(key))
	for i := len(mods) - 1; i >= 0; i-- {
		ins = append(ins, keyUp(mods[i]))
	}
	return sendInputs(ins)
}

// PressKeys — «ctrl+c», «alt+tab», «win+d», «f5», «volup».
func (a *App) PressKeys(combo string) bool {
	parts := strings.Split(strings.ToLower(strings.TrimSpace(combo)), "+")
	if len(parts) == 0 {
		return false
	}
	var mods []uint16
	var key uint16
	for i, p := range parts {
		p = strings.TrimSpace(p)
		vk, ok := vkNames[p]
		if !ok {
			if len(p) == 1 {
				c := p[0]
				switch {
				case c >= 'a' && c <= 'z':
					vk = uint16(c - 32) // VK_A..VK_Z совпадают с заглавными латинскими
				case c >= '0' && c <= '9':
					vk = uint16(c)
				default:
					return false
				}
			} else {
				return false
			}
		}
		if i < len(parts)-1 {
			mods = append(mods, vk)
		} else {
			key = vk
		}
	}
	if len(mods) == 0 {
		return tapKey(key)
	}
	return comboKey(mods, key)
}

// TypeText — напечатать текст в то окно, которое сейчас впереди. Шлём символы как есть,
// поэтому раскладка клавиатуры не важна: русский печатается при английской и наоборот.
func (a *App) TypeText(text string) bool {
	if text == "" {
		return false
	}
	ok := true
	for _, r := range text {
		var units []uint16
		if r > 0xFFFF {
			r -= 0x10000
			units = []uint16{uint16(0xD800 + (r >> 10)), uint16(0xDC00 + (r & 0x3FF))}
		} else {
			units = []uint16{uint16(r)}
		}
		var ins []input
		for _, u := range units {
			ins = append(ins,
				input{typ: inputKeyboard, ki: keyboardInput{wScan: u, dwFlags: keyEventUnicode}},
				input{typ: inputKeyboard, ki: keyboardInput{wScan: u, dwFlags: keyEventUnicode | keyEventKeyUp}})
		}
		if !sendInputs(ins) {
			ok = false
		}
		time.Sleep(time.Millisecond)
	}
	return ok
}

// ---------- готовые действия ----------

// power — сон, блокировка, выключение, перезагрузка.
var powerCmds = map[string][]string{
	"sleep":    {"rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"},
	"lock":     {"rundll32.exe", "user32.dll,LockWorkStation"},
	"shutdown": {"shutdown", "/s", "/t", "0"},
	"reboot":   {"shutdown", "/r", "/t", "0"},
}

func power(what string) bool {
	argv, ok := powerCmds[what]
	if !ok {
		return false
	}
	c := exec.Command(argv[0], argv[1:]...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return c.Start() == nil
}
