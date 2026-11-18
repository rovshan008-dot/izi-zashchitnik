package main

// Горячая клавиша Ctrl+Shift+Пробел: позвать Изи из любого места, даже когда окно свёрнуто.
// Это опора на случай, если «Привет, Изи» недоступен (см. wake.go).

import (
	"runtime"
	"syscall"
	"unsafe"
)

var (
	user32             = syscall.NewLazyDLL("user32.dll")
	procRegisterHotKey = user32.NewProc("RegisterHotKey")
	procGetMessageW    = user32.NewProc("GetMessageW")
)

const (
	modControl  = 0x0002
	modShift    = 0x0004
	modNoRepeat = 0x4000
	vkSpace     = 0x20
	wmHotkey    = 0x0312
	hotkeyID    = 0xA17E
)

type winMsg struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	pt      struct{ x, y int32 }
}

// startHotkey — ловит Ctrl+Shift+Пробел и зовёт onPress.
// Возвращает false, если клавиша уже занята другой программой.
func startHotkey(onPress func()) bool {
	ready := make(chan bool, 1)
	go func() {
		// RegisterHotKey и очередь сообщений должны жить на одном потоке ОС
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()

		r, _, _ := procRegisterHotKey.Call(0, hotkeyID, modControl|modShift|modNoRepeat, vkSpace)
		if r == 0 {
			ready <- false
			return
		}
		ready <- true

		var msg winMsg
		for {
			got, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
			if int32(got) <= 0 {
				return
			}
			if msg.message == wmHotkey && msg.wParam == hotkeyID {
				onPress()
			}
		}
	}()
	return <-ready
}
