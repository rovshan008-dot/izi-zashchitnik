package main

// Запуск программ и сайтов: «открой телеграм», «запусти метатрейдер», «открой бинанс».
//
// Индекс строится один раз из меню «Пуск» (общего и личного) — там лежат ярлыки почти всех
// установленных программ, включая торговые терминалы. Плюс небольшой список системных
// (проводник, блокнот, калькулятор) и русские псевдонимы, потому что «хром» транслитом
// даёт «hrom», а не «chrome».

import (
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"syscall"
)

type AppEntry struct {
	Name string `json:"name"` // как показать человеку: «Telegram Desktop»
	Path string `json:"path"` // полный путь к ярлыку или exe
	key  string // имя в нижнем регистре без лишних знаков — для поиска
}

var (
	appsOnce  sync.Once
	appsMu    sync.RWMutex
	appsIndex []AppEntry
)

// systemApps — то, чего нет ярлыком в «Пуске», но что просят чаще всего.
var systemApps = []AppEntry{
	{Name: "Проводник", Path: "explorer.exe"},
	{Name: "Блокнот", Path: "notepad.exe"},
	{Name: "Калькулятор", Path: "calc.exe"},
	{Name: "Paint", Path: "mspaint.exe"},
	{Name: "Диспетчер задач", Path: "taskmgr.exe"},
	{Name: "Командная строка", Path: "cmd.exe"},
	{Name: "Параметры Windows", Path: "ms-settings:"},
	{Name: "Ножницы", Path: "snippingtool.exe"},
}

// appAliases — как человек это называет вслух → что искать в индексе.
// Ключ слева уже нормализован (нижний регистр, без пробелов и знаков).
var appAliases = map[string]string{
	"хром":          "chrome",
	"гуглхром":      "chrome",
	"браузер":       "chrome",
	"телеграм":      "telegram",
	"телега":        "telegram",
	"тг":            "telegram",
	"вотсап":        "whatsapp",
	"ватсап":        "whatsapp",
	"вацап":         "whatsapp",
	"вайбер":        "viber",
	"дискорд":       "discord",
	"ворд":          "word",
	"эксель":        "excel",
	"повербоинт":    "powerpoint",
	"пауэрпоинт":    "powerpoint",
	"аутлук":        "outlook",
	"скайп":         "skype",
	"зум":           "zoom",
	"стим":          "steam",
	"спотифай":      "spotify",
	"яндексбраузер": "yandex",
	"яндекс":        "yandex",
	"опера":         "opera",
	"фаерфокс":      "firefox",
	"файрфокс":      "firefox",
	"мозила":        "firefox",
	"фотошоп":       "photoshop",
	"вскод":         "code",
	"вижуалстудио":  "visual studio",
	"метатрейдер":   "metatrader",
	"мт5":           "metatrader 5",
	"мт4":           "metatrader 4",
	"терминал":      "metatrader",
	"тинькофф":      "тинькофф",
	"квик":          "quik",
	"бинанс":        "binance",
	"бибит":         "bybit",
	"байбит":        "bybit",
	"трейдингвью":   "tradingview",
	"обс":           "obs",
	"винрар":        "winrar",
	"седьмойзип":    "7-zip",
}

// siteAliases — «открой бинанс» должно открывать сайт, если программы нет.
var siteAliases = map[string]string{
	"бинанс":      "https://www.binance.com",
	"байбит":      "https://www.bybit.com",
	"бибит":       "https://www.bybit.com",
	"трейдингвью": "https://ru.tradingview.com",
	"тредингвью":  "https://ru.tradingview.com",
	"скринер":     "https://izi20.tech/screener.html",
	"изискринер":  "https://izi20.tech/screener.html",
	"ютуб":        "https://www.youtube.com",
	"гугл":        "https://www.google.com",
	"яндекс":      "https://ya.ru",
	"почта":       "https://mail.yandex.ru",
	"гуглпочта":   "https://mail.google.com",
	"гитхаб":      "https://github.com",
	"изи":         "https://izi20.tech",
	"изипорт":     "https://izi20.tech/port",
	"шахматы":     "https://izi20.tech/chess",
	"авито":       "https://www.avito.ru",
	"озон":        "https://www.ozon.ru",
	"вайлдберриз": "https://www.wildberries.ru",
	"вб":          "https://www.wildberries.ru",
	"вк":          "https://vk.com",
	"курс":        "https://www.coingecko.com",
	"коингеко":    "https://www.coingecko.com",
	"погода":      "https://yandex.ru/pogoda",
	"карты":       "https://yandex.ru/maps",
	"переводчик":  "https://translate.yandex.ru",
	"чатгпт":      "https://chat.openai.com",
	"клод":        "https://claude.ai",
	"телеграмвеб": "https://web.telegram.org",
	"вотсапвеб":   "https://web.whatsapp.com",
}

// ---------- индекс ----------

func startMenuDirs() []string {
	var dirs []string
	if p := os.Getenv("ProgramData"); p != "" {
		dirs = append(dirs, filepath.Join(p, `Microsoft\Windows\Start Menu\Programs`))
	}
	if p := os.Getenv("APPDATA"); p != "" {
		dirs = append(dirs, filepath.Join(p, `Microsoft\Windows\Start Menu\Programs`))
	}
	return dirs
}

func buildAppsIndex() {
	seen := map[string]bool{}
	var list []AppEntry

	add := func(e AppEntry) {
		e.key = normKey(e.Name)
		if e.key == "" || seen[e.key] {
			return
		}
		seen[e.key] = true
		list = append(list, e)
	}

	for _, dir := range startMenuDirs() {
		_ = filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
			if err != nil || info == nil || info.IsDir() {
				return nil
			}
			ext := strings.ToLower(filepath.Ext(path))
			if ext != ".lnk" && ext != ".url" {
				return nil
			}
			name := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
			// «Удалить …», «Uninstall …», «Справка» — не программы, которые просят открыть
			low := strings.ToLower(name)
			for _, bad := range []string{"uninstall", "удалить", "справка", "readme", "документация", "website", "сайт "} {
				if strings.Contains(low, bad) {
					return nil
				}
			}
			add(AppEntry{Name: name, Path: path})
			return nil
		})
	}
	for _, e := range systemApps {
		add(e)
	}

	sort.Slice(list, func(i, j int) bool { return list[i].Name < list[j].Name })
	appsMu.Lock()
	appsIndex = list
	appsMu.Unlock()
}

func apps() []AppEntry {
	appsOnce.Do(buildAppsIndex)
	appsMu.RLock()
	defer appsMu.RUnlock()
	return appsIndex
}

// RefreshApps — перечитать меню «Пуск» (если человек только что что-то установил).
func (a *App) RefreshApps() int {
	buildAppsIndex()
	return len(apps())
}

// normKey — «Google Chrome» → «googlechrome», «Привет, Изи!» → «приветизи».
func normKey(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9', r >= 'а' && r <= 'я', r == 'ё':
			b.WriteRune(r)
		}
	}
	return b.String()
}

var translit = map[rune]string{
	'а': "a", 'б': "b", 'в': "v", 'г': "g", 'д': "d", 'е': "e", 'ё': "e", 'ж': "zh",
	'з': "z", 'и': "i", 'й': "y", 'к': "k", 'л': "l", 'м': "m", 'н': "n", 'о': "o",
	'п': "p", 'р': "r", 'с': "s", 'т': "t", 'у': "u", 'ф': "f", 'х': "h", 'ц': "c",
	'ч': "ch", 'ш': "sh", 'щ': "sch", 'ъ': "", 'ы': "y", 'ь': "", 'э': "e", 'ю': "yu", 'я': "ya",
}

func toLatin(s string) string {
	var b strings.Builder
	for _, r := range s {
		if v, ok := translit[r]; ok {
			b.WriteString(v)
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}

// score — насколько запись подходит запросу. 0 — не подходит.
func score(entryKey, q string) int {
	if entryKey == "" || q == "" {
		return 0
	}
	switch {
	case entryKey == q:
		return 100
	case strings.HasPrefix(entryKey, q):
		return 80 - len(entryKey) + len(q)
	case strings.Contains(entryKey, q):
		return 55 - len(entryKey) + len(q)
	}
	return 0
}

// FindApp — найти программу по тому, как её назвали вслух.
func FindApp(query string) *AppEntry {
	q := normKey(query)
	if q == "" {
		return nil
	}
	// варианты запроса: как сказано, псевдоним, транслит
	variants := []string{q}
	if alias, ok := appAliases[q]; ok {
		variants = append(variants, normKey(alias))
	}
	if lat := normKey(toLatin(q)); lat != q {
		variants = append(variants, lat)
	}

	list := apps()
	best := -1
	bestScore := 0
	for _, v := range variants {
		for i := range list {
			if s := score(list[i].key, v); s > bestScore {
				bestScore, best = s, i
			}
		}
		if bestScore >= 80 {
			break // точное попадание — дальше не ищем
		}
	}
	if best < 0 || bestScore < 40 {
		return nil
	}
	e := list[best]
	return &e
}

// ---------- запуск ----------

type OpenResult struct {
	OK    bool   `json:"ok"`
	Kind  string `json:"kind"`  // "app" | "site"
	Title string `json:"title"` // что открыли, человеческим языком
	Error string `json:"error,omitempty"`
}

// launch — запустить файл/ярлык/протокол так, как это сделал бы двойной клик.
func launch(path string) error {
	// ShellExecute через explorer корректно открывает .lnk, .url, ms-settings: и exe
	cmd := exec.Command("cmd", "/c", "start", "", path)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return cmd.Start()
}

func openURL(url string) error {
	cmd := exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return cmd.Start()
}

// OpenApp — «открой телеграм». Если программы нет, но есть известный сайт — открываем сайт.
func (a *App) OpenApp(query string) OpenResult {
	if e := FindApp(query); e != nil {
		if err := launch(e.Path); err != nil {
			return OpenResult{Kind: "app", Title: e.Name, Error: err.Error()}
		}
		return OpenResult{OK: true, Kind: "app", Title: e.Name}
	}
	if url, ok := siteAliases[normKey(query)]; ok {
		if err := openURL(url); err != nil {
			return OpenResult{Kind: "site", Title: url, Error: err.Error()}
		}
		return OpenResult{OK: true, Kind: "site", Title: siteTitle(url)}
	}
	return OpenResult{Kind: "app", Title: query, Error: "не нашёл такую программу"}
}

// OpenSite — «открой сайт бинанс» или «открой ya.ru».
func (a *App) OpenSite(query string) OpenResult {
	url := resolveSite(query)
	if url == "" {
		return OpenResult{Kind: "site", Title: query, Error: "не понял, какой это сайт"}
	}
	if err := openURL(url); err != nil {
		return OpenResult{Kind: "site", Title: url, Error: err.Error()}
	}
	return OpenResult{OK: true, Kind: "site", Title: siteTitle(url)}
}

func resolveSite(query string) string {
	q := strings.TrimSpace(query)
	if q == "" {
		return ""
	}
	if url, ok := siteAliases[normKey(q)]; ok {
		return url
	}
	low := strings.ToLower(q)
	if strings.HasPrefix(low, "http://") || strings.HasPrefix(low, "https://") {
		return q
	}
	// «ya.ru», «izi20.tech/port» — похоже на адрес
	host := strings.Split(low, "/")[0]
	host = strings.TrimSuffix(host, ".")
	if strings.Contains(host, ".") && !strings.Contains(host, " ") && len(host) > 3 {
		return "https://" + strings.ReplaceAll(low, " ", "")
	}
	return ""
}

func siteTitle(url string) string {
	s := strings.TrimPrefix(strings.TrimPrefix(url, "https://"), "http://")
	s = strings.TrimPrefix(s, "www.")
	return strings.TrimSuffix(s, "/")
}

// ListApps — что Изи вообще умеет открыть (для экрана «Программы»).
func (a *App) ListApps() []AppEntry { return apps() }
