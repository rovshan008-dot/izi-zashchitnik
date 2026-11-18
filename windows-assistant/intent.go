package main

// Разбор команд, которые компьютер выполняет сам: «открой телеграм», «что по битку».
//
// Разбираем на месте, до обращения к серверу: так Изи отвечает мгновенно и не тратит токены.
// Если цель не опознана (нет такой программы, нет такой монеты) — молча отдаём фразу модели,
// пусть отвечает как обычно. Поэтому ложное срабатывание человеку не мешает.

import (
	"strings"
)

type Intent struct {
	Kind   string // "open" | "folder" | "coin" | "screener" | ""
	Target string
}

var openVerbs = []string{
	"открой", "открой", "открои", "открыть", "отрой",
	"запусти", "запустить", "запускай",
	"включи", "включить", "врубай", "врубить",
	"зайди на", "зайди в", "зайди", "перейди на", "перейди в",
}

var openFillers = []string{"мне", "пожалуйста", "давай", "ка", "быстро", "сейчас"}

// openTypeWords — уточнение после глагола: «открой ПАПКУ загрузки», «открой САЙТ бинанс».
var openTypeWords = map[string]string{
	"сайт":       "site",
	"сайтик":     "site",
	"страницу":   "site",
	"страничку":  "site",
	"папку":      "folder",
	"папка":      "folder",
	"каталог":    "folder",
	"директорию": "folder",
	"приложение": "open",
	"приложуху":  "open",
	"программу":  "open",
	"программа":  "open",
	"прогу":      "open",
	"терминал":   "open",
}

var coinLeads = []string{
	"что по", "что с", "что там по", "как дела у", "как там",
	"срез по", "срез", "курс", "цена", "цену", "сколько стоит", "почем", "почём", "покажи",
}

var screenerPhrases = []string{
	"что на скринере", "что по скринеру", "открой скринер", "скринер",
	"что по рынку", "что на рынке", "как рынок", "кто растет", "кто растёт",
	"кто падает", "топ движения", "топ движений", "движения рынка",
}

// dropFolderPhrases — именно папка «Перенос», куда падает всё с телефона.
var dropFolderPhrases = []string{
	"открой папку переноса", "открой папку перенос", "папку переноса", "папка переноса",
	"открой перенос", "где файлы с телефона", "покажи файлы с телефона",
	"открой папку с телефона", "что пришло с телефона",
}

// cleanPhrase — нижний регистр, без знаков по краям, без обращения «Изи».
func cleanPhrase(text string) string {
	s := strings.ToLower(strings.TrimSpace(text))
	// распознаватель речи любит ставить запятые внутри фразы: «Открой, пожалуйста, телеграм».
	// Точку не трогаем — она нужна в адресах сайтов («открой izi20.tech»).
	for _, r := range []string{",", ";", "!", "?", "«", "»", "\"", "…"} {
		s = strings.ReplaceAll(s, r, " ")
	}
	s = strings.Join(strings.Fields(strings.Trim(s, " .:'\n\t")), " ")
	for _, p := range []string{"привет изи", "изи"} {
		if s == p {
			return ""
		}
		if strings.HasPrefix(s, p+" ") {
			s = strings.TrimSpace(strings.TrimPrefix(s, p))
		}
	}
	return s
}

// ParseIntent — что человек попросил сделать. Kind == "" значит «это вопрос, пусть отвечает модель».
func ParseIntent(text string) Intent {
	s := cleanPhrase(text)
	if s == "" {
		return Intent{}
	}

	for _, p := range dropFolderPhrases {
		if s == p {
			return Intent{Kind: "drop-folder"}
		}
	}
	for _, p := range screenerPhrases {
		if s == p {
			return Intent{Kind: "screener"}
		}
	}

	// «открой телеграм», «запусти метатрейдер», «зайди на бинанс»
	for _, v := range openVerbs {
		if !strings.HasPrefix(s, v+" ") {
			continue
		}
		rest := strings.TrimSpace(strings.TrimPrefix(s, v))
		rest = dropWords(rest, openFillers)
		kind := "open"
		for t, k := range openTypeWords {
			if rest == t {
				// «открой папку» без уточнения — значит папку «Перенос»
				if k == "folder" {
					return Intent{Kind: "drop-folder"}
				}
				return Intent{}
			}
			if strings.HasPrefix(rest, t+" ") {
				rest = strings.TrimSpace(strings.TrimPrefix(rest, t))
				kind = k
				break
			}
		}
		rest = dropWords(rest, openFillers)
		if rest == "" {
			return Intent{}
		}
		return Intent{Kind: kind, Target: rest}
	}

	// «что по битку», «курс эфира», или просто «биток»
	for _, lead := range coinLeads {
		if !strings.HasPrefix(s, lead+" ") {
			continue
		}
		rest := strings.TrimSpace(strings.TrimPrefix(s, lead))
		if w := firstCoinWord(rest); w != "" {
			return Intent{Kind: "coin", Target: w}
		}
		return Intent{}
	}
	if !strings.Contains(s, " ") {
		if _, ok := resolveCoin(s); ok {
			return Intent{Kind: "coin", Target: s}
		}
	}

	return Intent{}
}

func dropWords(s string, words []string) string {
	changed := true
	for changed {
		changed = false
		for _, w := range words {
			if strings.HasPrefix(s, w+" ") {
				s = strings.TrimSpace(strings.TrimPrefix(s, w))
				changed = true
			}
		}
	}
	return s
}

// firstCoinWord — первое слово остатка фразы, которое скринер знает как монету.
func firstCoinWord(rest string) string {
	for _, w := range strings.Fields(rest) {
		w = strings.Trim(w, ".,!?;:")
		if w == "" {
			continue
		}
		if _, ok := resolveCoin(w); ok {
			return w
		}
	}
	return ""
}

// localAnswer — выполнить команду на компьютере и вернуть ответ Изи.
// Пустая строка значит «я это не беру, пусть отвечает модель».
func (a *App) localAnswer(text string) string {
	// свои сценарии проверяем раньше всего: человек задал их сам, его слово главнее
	if sc := matchScenario(text); sc != nil {
		if ans := a.runSteps(sc); ans != "" {
			return ans
		}
		return sc.Name + ": готово"
	}

	in := ParseIntent(text)
	switch in.Kind {
	case "drop-folder":
		a.OpenReceivedFolder()
		return "Открыл папку «Перенос» — там всё, что пришло с телефона."

	case "folder":
		r := a.OpenFolder(in.Target)
		if r.OK {
			return "Открыл папку " + r.Title
		}
		return ""

	case "screener":
		if s := a.ScreenerSummary(); s != "" {
			return s
		}
		return ""

	case "coin":
		if s := a.ScreenerCoin(in.Target); s != "" {
			return s
		}
		return ""

	case "site":
		r := a.OpenSite(in.Target)
		if r.OK {
			return "Открываю " + r.Title
		}
		return ""

	case "open":
		// порядок: программа → папка → сайт
		if r := a.OpenApp(in.Target); r.OK {
			if r.Kind == "site" {
				return "Программы такой нет, открыл сайт " + r.Title
			}
			return "Открываю " + r.Title
		}
		if r := a.OpenFolder(in.Target); r.OK {
			return "Открыл папку " + r.Title
		}
		if resolveSite(in.Target) != "" {
			if r := a.OpenSite(in.Target); r.OK {
				return "Открываю " + r.Title
			}
		}
		return ""
	}
	return ""
}
