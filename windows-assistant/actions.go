package main

// Готовые действия голосом: громкость, музыка, окна, сон и выключение, печать текста.

import (
	"strings"
	"sync"
	"time"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

type action struct {
	phrases []string // фраза целиком, как её скажут
	run     func(a *App) string
}

// repeatKey — громкость шагает по три нажатия, иначе разницы почти не слышно.
func repeatKey(vk uint16, n int) bool {
	ok := true
	for i := 0; i < n; i++ {
		if !tapKey(vk) {
			ok = false
		}
		time.Sleep(25 * time.Millisecond)
	}
	return ok
}

var actions = []action{
	{[]string{"громче", "сделай громче", "прибавь звук", "прибавь громкость", "погромче", "звук громче"},
		func(a *App) string { repeatKey(0xAF, 3); return "Сделал громче" }},
	{[]string{"тише", "сделай тише", "убавь звук", "убавь громкость", "потише", "звук тише"},
		func(a *App) string { repeatKey(0xAE, 3); return "Сделал тише" }},
	{[]string{"выключи звук", "включи звук", "без звука", "заглуши", "звук выключи", "тишина"},
		func(a *App) string { tapKey(0xAD); return "Переключил звук" }},
	{[]string{"пауза", "поставь на паузу", "останови музыку", "продолжи музыку", "включи музыку", "играй"},
		func(a *App) string { tapKey(0xB3); return "Готово" }},
	{[]string{"следующий трек", "следующая песня", "переключи трек", "дальше трек", "следующий"},
		func(a *App) string { tapKey(0xB0); return "Включил следующий" }},
	{[]string{"предыдущий трек", "предыдущая песня", "верни трек", "назад трек"},
		func(a *App) string { tapKey(0xB1); return "Вернул предыдущий" }},
	{[]string{"сверни всё", "сверни все", "сверни все окна", "покажи рабочий стол", "скрой всё", "скрой все"},
		func(a *App) string { a.PressKeys("win+d"); return "Свернул всё" }},
	{[]string{"заблокируй компьютер", "заблокируй", "запри компьютер", "блокировка"},
		func(a *App) string {
			if power("lock") {
				return "Блокирую"
			}
			return "Не вышло заблокировать"
		}},
	{[]string{"усыпи компьютер", "спящий режим", "отправь в сон", "в сон", "усни"},
		func(a *App) string {
			if power("sleep") {
				return "Ухожу в сон"
			}
			return "Не вышло усыпить"
		}},
}

// ---------- то, что просит подтверждения ----------

var confirmable = map[string]struct {
	what string
	ask  string
}{
	"выключи компьютер":     {"shutdown", "Выключить компьютер? Скажи «да, выключай»."},
	"выключай компьютер":    {"shutdown", "Выключить компьютер? Скажи «да, выключай»."},
	"выключение компьютера": {"shutdown", "Выключить компьютер? Скажи «да, выключай»."},
	"перезагрузи компьютер": {"reboot", "Перезагрузить компьютер? Скажи «да, перезагружай»."},
	"перезагрузка":          {"reboot", "Перезагрузить компьютер? Скажи «да, перезагружай»."},
}

var confirmYes = []string{"да выключай", "да выключи", "выключай", "да перезагружай", "да перезагрузи", "перезагружай"}

var pending struct {
	mu   sync.Mutex
	what string
	till time.Time
}

func armConfirm(what string) {
	pending.mu.Lock()
	pending.what, pending.till = what, time.Now().Add(45*time.Second)
	pending.mu.Unlock()
}

func takeConfirm() string {
	pending.mu.Lock()
	defer pending.mu.Unlock()
	if pending.what == "" || time.Now().After(pending.till) {
		pending.what = ""
		return ""
	}
	w := pending.what
	pending.what = ""
	return w
}

// actionFillers — вежливые слова перед командой: «сделай потише», «давай погромче».
var actionFillers = []string{"сделай", "сделать", "давай", "пожалуйста", "можешь", "ну", "а", "изи"}

// tryActions — выполнить готовое действие. Пусто — значит это не оно.
func (a *App) tryActions(s string) string {
	s = dropWords(s, actionFillers)
	if s == "" {
		return ""
	}
	// сначала «да, выключай» — но только если Изи сам только что спросил
	for _, y := range confirmYes {
		if s == y {
			what := takeConfirm()
			if what == "" {
				return ""
			}
			if power(what) {
				if what == "reboot" {
					return "Перезагружаю"
				}
				return "Выключаю"
			}
			return "Не вышло, команда не прошла"
		}
	}
	if c, ok := confirmable[s]; ok {
		armConfirm(c.what)
		return c.ask
	}
	for _, act := range actions {
		for _, p := range act.phrases {
			if s == p {
				return act.run(a)
			}
		}
	}

	// «нажми ctrl+c», «нажми alt+tab»
	for _, lead := range []string{"нажми", "нажать", "жми"} {
		if strings.HasPrefix(s, lead+" ") {
			combo := strings.TrimSpace(strings.TrimPrefix(s, lead))
			combo = strings.ReplaceAll(combo, " плюс ", "+")
			combo = strings.ReplaceAll(combo, " ", "+")
			if a.PressKeys(combo) {
				return "Нажал " + combo
			}
			return ""
		}
	}
	return ""
}

// TypeInFront — напечатать текст в то окно, которое было впереди до Изи.
func (a *App) TypeInFront(text string) string {
	if strings.TrimSpace(text) == "" {
		return ""
	}
	if a.ctx != nil {
		runtime.WindowHide(a.ctx)
		time.Sleep(250 * time.Millisecond)
	}
	ok := a.TypeText(text)
	if a.ctx != nil {
		runtime.WindowShow(a.ctx)
	}
	if !ok {
		return "Напечатал не всё — окно могло не принять ввод"
	}
	return "Напечатал: " + text
}
