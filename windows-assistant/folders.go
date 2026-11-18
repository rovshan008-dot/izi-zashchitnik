package main

// Папки: «открой загрузки», «открой рабочий стол», «открой D:\Проекты».

import (
	"os"
	"path/filepath"
	"strings"
)

// folderAliases — как человек называет папку → подпапка профиля пользователя.
// Пустое значение означает «особая папка», её считает resolveFolder.
var folderAliases = map[string]string{
	"загрузки":     "Downloads",
	"загрузка":     "Downloads",
	"скачанное":    "Downloads",
	"даунлоадс":    "Downloads",
	"документы":    "Documents",
	"докуметы":     "Documents",
	"рабочийстол":  "Desktop",
	"столрабочий":  "Desktop",
	"десктоп":      "Desktop",
	"картинки":     "Pictures",
	"изображения":  "Pictures",
	"фото":         "Pictures",
	"фотки":        "Pictures",
	"видео":        "Videos",
	"видосы":       "Videos",
	"музыка":       "Music",
	"музыку":       "Music",
	"избранное":    "Favorites",
	"onedrive":     "OneDrive",
	"ванлрайв":     "OneDrive",
	"профиль":      ".",
	"домашняя":     ".",
	"пользователь": ".",
}

// resolveFolder — путь к папке по тому, как её назвали. Пусто, если не понял.
func (a *App) resolveFolder(query string) string {
	q := strings.TrimSpace(query)
	if q == "" {
		return ""
	}

	// папка «Перенос» — своя, её Изи знает отдельно
	k := normKey(q)
	if k == "перенос" || k == "переноса" || k == "переносы" || k == "спереноса" {
		return a.receiveDir
	}

	// готовый путь: «D:\Проекты», «C:/work», «%USERPROFILE%\Desktop», «~/Downloads»
	if looksLikePath(q) {
		p := os.ExpandEnv(q)
		if strings.HasPrefix(p, "~") {
			if home, err := os.UserHomeDir(); err == nil {
				p = filepath.Join(home, strings.TrimPrefix(p, "~"))
			}
		}
		if st, err := os.Stat(p); err == nil && st.IsDir() {
			return p
		}
		return ""
	}

	sub, ok := folderAliases[k]
	if !ok {
		return ""
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	p := home
	if sub != "." {
		p = filepath.Join(home, sub)
	}
	if st, err := os.Stat(p); err == nil && st.IsDir() {
		return p
	}
	return ""
}

// looksLikePath — «D:\Проекты», «C:/work», «\\сервер\общая», «~/Загрузки», «%APPDATA%».
func looksLikePath(s string) bool {
	if strings.HasPrefix(s, `\\`) || strings.HasPrefix(s, "~") || strings.Contains(s, "%") {
		return true
	}
	if len(s) >= 3 && s[1] == ':' && (s[2] == '\\' || s[2] == '/') {
		c := s[0] | 0x20
		return c >= 'a' && c <= 'z'
	}
	return false
}

// OpenFolder — открыть папку в проводнике. Возвращает человеческое название или пусто.
func (a *App) OpenFolder(query string) OpenResult {
	p := a.resolveFolder(query)
	if p == "" {
		return OpenResult{Kind: "folder", Title: query, Error: "не нашёл такую папку"}
	}
	if err := launch(p); err != nil {
		return OpenResult{Kind: "folder", Title: p, Error: err.Error()}
	}
	title := filepath.Base(p)
	if title == "" || title == "." {
		title = p
	}
	return OpenResult{OK: true, Kind: "folder", Title: title}
}
