package main

// Сценарии: свои команды, которые человек задаёт сам.
//
// Пример: название «Утро», фразы «доброе утро», «рабочий режим», шаги —
// открыть Chrome, открыть izi20.tech/screener, открыть Telegram, сказать «Вот что на рынке»,
// вставить срез по BTC. Сказал фразу — Изи прошёл шаги по очереди.
//
// Границы, нарочно: сценарии приходят только из вкладки «Сценарии», то есть от человека за
// клавиатурой. Ни ответ модели, ни текст с сайта, ни сообщение из бота не могут создать
// сценарий, подменить шаг или запустить его — иначе любой прочитанный Изи текст стал бы
// командой на этом компьютере. Запуск — только по совпадению с фразой, которую человек
// сам сказал или напечатал в своём окне Изи.

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Step — один шаг сценария.
type Step struct {
	Kind  string `json:"kind"`  // app | site | folder | say | coin | screener | wait | cmd
	Value string `json:"value"` // что открыть / что сказать / сколько секунд ждать
}

type Scenario struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Phrases []string `json:"phrases"`
	Steps   []Step   `json:"steps"`
}

// stepKinds — что вообще бывает шагом. Остальное во вкладке не предлагается и не сохраняется.
var stepKinds = map[string]bool{
	"app": true, "site": true, "folder": true, "say": true,
	"coin": true, "screener": true, "wait": true, "cmd": true,
}

type scenarioBook struct {
	mu   sync.Mutex
	path string
	list []Scenario
}

var book *scenarioBook

func initScenarios(dataDir string) {
	b := &scenarioBook{path: filepath.Join(dataDir, "scenarios.json")}
	if raw, err := os.ReadFile(b.path); err == nil {
		_ = json.Unmarshal(raw, &b.list)
	}
	book = b
}

func (b *scenarioBook) all() []Scenario {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make([]Scenario, len(b.list))
	copy(out, b.list)
	return out
}

func (b *scenarioBook) saveToDisk() {
	raw, err := json.MarshalIndent(b.list, "", "  ")
	if err != nil {
		return
	}
	tmp := b.path + ".tmp"
	if os.WriteFile(tmp, raw, 0o600) == nil {
		_ = os.Rename(tmp, b.path)
	}
}

// ---------- вызывается с экрана ----------

// Scenarios — список сценариев для вкладки «Сценарии».
func (a *App) Scenarios() []Scenario {
	if book == nil {
		return nil
	}
	return book.all()
}

// SaveScenario — создать или перезаписать сценарий. Возвращает его id.
func (a *App) SaveScenario(s Scenario) string {
	if book == nil {
		return ""
	}
	s.Name = strings.TrimSpace(s.Name)
	if s.Name == "" {
		s.Name = "Без названия"
	}
	// чистим фразы: нижний регистр, без пустых и без повторов
	seen := map[string]bool{}
	var phrases []string
	for _, p := range s.Phrases {
		p = cleanPhrase(p)
		if p == "" || seen[p] {
			continue
		}
		seen[p] = true
		phrases = append(phrases, p)
	}
	s.Phrases = phrases

	var steps []Step
	for _, st := range s.Steps {
		st.Kind = strings.TrimSpace(st.Kind)
		st.Value = strings.TrimSpace(st.Value)
		if !stepKinds[st.Kind] {
			continue
		}
		if st.Value == "" && st.Kind != "screener" {
			continue
		}
		steps = append(steps, st)
	}
	s.Steps = steps

	book.mu.Lock()
	defer book.mu.Unlock()
	if s.ID == "" {
		s.ID = fmt.Sprintf("sc-%d", time.Now().UnixNano())
		book.list = append(book.list, s)
	} else {
		found := false
		for i := range book.list {
			if book.list[i].ID == s.ID {
				book.list[i] = s
				found = true
				break
			}
		}
		if !found {
			book.list = append(book.list, s)
		}
	}
	book.saveToDisk()
	return s.ID
}

// DeleteScenario — удалить сценарий.
func (a *App) DeleteScenario(id string) bool {
	if book == nil || id == "" {
		return false
	}
	book.mu.Lock()
	defer book.mu.Unlock()
	for i := range book.list {
		if book.list[i].ID == id {
			book.list = append(book.list[:i], book.list[i+1:]...)
			book.saveToDisk()
			return true
		}
	}
	return false
}

// matchScenario — какой сценарий человек позвал этой фразой.
// Сначала точное совпадение, потом фраза целиком внутри сказанного.
func matchScenario(text string) *Scenario {
	if book == nil {
		return nil
	}
	s := cleanPhrase(text)
	if s == "" {
		return nil
	}
	list := book.all()
	for i := range list {
		for _, p := range list[i].Phrases {
			if s == p {
				return &list[i]
			}
		}
	}
	best := -1
	bestLen := 0
	for i := range list {
		for _, p := range list[i].Phrases {
			// короткие фразы внутри длинного вопроса не ловим — слишком легко попасть случайно
			if len(p) >= 8 && strings.Contains(s, p) && len(p) > bestLen {
				best, bestLen = i, len(p)
			}
		}
	}
	if best < 0 {
		return nil
	}
	return &list[best]
}

// RunScenario — пройти шаги по очереди. Возвращает то, что Изи скажет в ответ.
func (a *App) RunScenario(id string) string {
	if book == nil {
		return ""
	}
	var sc *Scenario
	for _, s := range book.all() {
		if s.ID == id {
			c := s
			sc = &c
			break
		}
	}
	if sc == nil {
		return ""
	}
	return a.runSteps(sc)
}

func (a *App) runSteps(sc *Scenario) string {
	var said []string   // фразы, которые надо сказать человеку
	var done []string   // что открылось
	var ran []string    // какие команды выполнились
	var failed []string // что не вышло

	for _, st := range sc.Steps {
		switch st.Kind {
		case "app":
			if r := a.OpenApp(st.Value); r.OK {
				done = append(done, r.Title)
			} else {
				failed = append(failed, st.Value)
			}
		case "site":
			if r := a.OpenSite(st.Value); r.OK {
				done = append(done, r.Title)
			} else {
				failed = append(failed, st.Value)
			}
		case "folder":
			if r := a.OpenFolder(st.Value); r.OK {
				done = append(done, "папку "+r.Title)
			} else {
				failed = append(failed, "папку "+st.Value)
			}
		case "say":
			said = append(said, st.Value)
		case "coin":
			if s := a.ScreenerCoin(st.Value); s != "" {
				said = append(said, s)
			} else {
				failed = append(failed, "срез по "+st.Value)
			}
		case "screener":
			if s := a.ScreenerSummary(); s != "" {
				said = append(said, s)
			} else {
				failed = append(failed, "скринер")
			}
		case "wait":
			d := parseSeconds(st.Value)
			if d > 0 {
				if d > 60*time.Second {
					d = 60 * time.Second
				}
				time.Sleep(d)
			}
		case "cmd":
			if err := runCommand(st.Value); err != nil {
				failed = append(failed, "команду «"+st.Value+"»")
			} else {
				ran = append(ran, st.Value)
			}
		}
	}

	var head []string
	if len(done) > 0 {
		head = append(head, "открыл "+strings.Join(done, ", "))
	}
	if len(ran) > 0 {
		head = append(head, "выполнил «"+strings.Join(ran, "», «")+"»")
	}
	var out []string
	if len(head) > 0 {
		out = append(out, sc.Name+": "+strings.Join(head, "; "))
	} else if len(failed) == 0 {
		out = append(out, sc.Name+": готово")
	}
	out = append(out, said...)
	if len(failed) > 0 {
		out = append(out, "Не вышло: "+strings.Join(failed, ", "))
	}
	return strings.Join(out, "\n\n")
}

func parseSeconds(v string) time.Duration {
	v = strings.TrimSpace(strings.ToLower(v))
	v = strings.TrimSuffix(strings.TrimSuffix(strings.TrimSuffix(v, "сек"), "с"), " ")
	var n float64
	if _, err := fmt.Sscanf(v, "%f", &n); err != nil || n <= 0 {
		return 0
	}
	return time.Duration(n * float64(time.Second))
}

// runCommand — выполнить командную строку, как её написал человек в своём сценарии.
// Сюда попадает только то, что он сам напечатал во вкладке «Сценарии».
func runCommand(line string) error {
	line = strings.TrimSpace(line)
	if line == "" {
		return fmt.Errorf("пустая команда")
	}
	cmd := exec.Command("cmd")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CmdLine: "/c " + line, CreationFlags: 0x08000000}
	return cmd.Start()
}
