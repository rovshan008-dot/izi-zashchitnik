package main

// Связь с мозгом Изи на izi20.tech — те же эндпоинты, что в Android- и веб-версии
// (см. assistant/.../IziApi.kt в этом же репозитории).

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const apiBase = "https://izi20.tech"

type Reply struct {
	Text        string `json:"text,omitempty"`
	ImageURL    string `json:"image_url,omitempty"`
	Recognized  string `json:"recognized,omitempty"`
	AuthExpired bool   `json:"auth_expired,omitempty"`
	Silent      bool   `json:"silent,omitempty"`
	Error       string `json:"error,omitempty"`
}

func postJSON(path string, body map[string]any, timeout time.Duration) (int, []byte, error) {
	b, err := json.Marshal(body)
	if err != nil {
		return 0, nil, err
	}
	req, err := http.NewRequest(http.MethodPost, apiBase+path, bytes.NewReader(b))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("User-Agent", "IziWindows/1")
	c := &http.Client{Timeout: timeout}
	resp, err := c.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	return resp.StatusCode, data, err
}

func parseReply(code int, data []byte, err error) Reply {
	if err != nil {
		return Reply{Error: "Нет связи с сервером"}
	}
	if code == 401 {
		return Reply{AuthExpired: true}
	}
	var j map[string]any
	if jsonErr := json.Unmarshal(data, &j); jsonErr != nil {
		return Reply{Error: fmt.Sprintf("Сервер ответил непонятно (код %d)", code)}
	}
	if s, _ := j["auth"].(string); s == "expired" {
		return Reply{AuthExpired: true}
	}
	r := Reply{}
	if s, ok := j["reply"].(string); ok {
		r.Text = s
	}
	if s, ok := j["image_url"].(string); ok {
		r.ImageURL = s
	}
	if s, ok := j["recognized"].(string); ok {
		r.Recognized = s
	}
	if b, ok := j["silent"].(bool); ok {
		r.Silent = b
	}
	if b, ok := j["wake"].(bool); ok {
		r.Silent = r.Silent || b
	}
	return r
}

// Link — код из Telegram (/web у @IZI20bot) → постоянный ключ входа.
func Link(code string) (string, error) {
	code = strings.TrimSpace(code)
	code = strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, code)
	_, data, err := postJSON("/chat/izi_link", map[string]any{"code": code}, 30*time.Second)
	if err != nil {
		return "", fmt.Errorf("нет связи с сервером")
	}
	var j map[string]any
	if jsonErr := json.Unmarshal(data, &j); jsonErr != nil {
		return "", fmt.Errorf("сервер ответил непонятно")
	}
	if ok, _ := j["ok"].(bool); ok {
		if token, _ := j["token"].(string); token != "" {
			return token, nil
		}
	}
	msg, _ := j["error"].(string)
	if msg == "" {
		msg = "не получилось войти"
	}
	return "", fmt.Errorf("%s", msg)
}

// Chat — обычное текстовое сообщение.
func Chat(token, text string) Reply {
	code, data, err := postJSON("/chat/izi", map[string]any{"token": token, "text": text}, 125*time.Second)
	return parseReply(code, data, err)
}

// Voice — запись голоса, сервер сам распознаёт и отвечает (как «запасной путь» у Android,
// только у Windows это основной способ голосового ввода — нет общего распознавания речи ОС).
func Voice(token string, audio []byte, filename, contentType string) Reply {
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	_ = w.WriteField("token", token)
	part, err := w.CreatePart(map[string][]string{
		"Content-Disposition": {fmt.Sprintf(`form-data; name="voice"; filename="%s"`, filename)},
		"Content-Type":        {contentType},
	})
	if err != nil {
		return Reply{Error: "Не получилось подготовить запись"}
	}
	if _, err := part.Write(audio); err != nil {
		return Reply{Error: "Не получилось подготовить запись"}
	}
	_ = w.Close()

	req, err := http.NewRequest(http.MethodPost, apiBase+"/chat/izi_voice", &buf)
	if err != nil {
		return Reply{Error: "Нет связи с сервером"}
	}
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.Header.Set("User-Agent", "IziWindows/1")
	c := &http.Client{Timeout: 125 * time.Second}
	resp, err := c.Do(req)
	if err != nil {
		return Reply{Error: "Нет связи с сервером"}
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return parseReply(resp.StatusCode, data, nil)
}

// See — вопрос с прикреплённым фото (izi_see), как «Спросить Изи про это фото».
func See(token, text, imageDataURL string) Reply {
	code, data, err := postJSON("/chat/izi_see", map[string]any{
		"token": token, "text": text, "image": imageDataURL,
	}, 125*time.Second)
	return parseReply(code, data, err)
}

// ---------- «Перенос»: телефон ↔ компьютер (см. Drop.kt в assistant/) ----------

type DropItem struct {
	ID   string  `json:"id"`
	TS   float64 `json:"ts"`
	Kind string  `json:"kind"` // text | link | file
	Text string  `json:"text,omitempty"`
	Name string  `json:"name,omitempty"`
	Mime string  `json:"mime,omitempty"`
	Size int64   `json:"size,omitempty"`
}

type DropPoll struct {
	Items       []DropItem `json:"items"`
	Now         float64    `json:"now"`
	OtherOnline bool       `json:"other_online"`
	AuthExpired bool       `json:"auth_expired,omitempty"`
}

// DropSendText — отправить текст или ссылку на телефон.
// ---------- прогресс передачи ----------

// progressFn вызывается по мере передачи байтов; total == -1, если размер неизвестен.
type progressFn func(done, total int64)

// countingReader считает прочитанные байты и сообщает наружу не чаще раза в 100 мс,
// чтобы не заваливать окно событиями.
type countingReader struct {
	r     io.Reader
	cb    progressFn
	done  int64
	total int64
	last  time.Time
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.done += int64(n)
	if c.cb != nil && (err != nil || time.Since(c.last) >= 100*time.Millisecond) {
		c.last = time.Now()
		c.cb(c.done, c.total)
	}
	return n, err
}

func DropSendText(token, text string, link bool) bool {
	kind := "text"
	if link {
		kind = "link"
	}
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	_ = w.WriteField("token", token)
	_ = w.WriteField("to", "phone")
	_ = w.WriteField("kind", kind)
	_ = w.WriteField("text", text)
	_ = w.Close()
	return dropSendRaw(&buf, int64(buf.Len()), w.FormDataContentType(), nil)
}

// DropSendFile — отправить файл с диска на телефон. cb получает ход передачи (можно nil).
func DropSendFile(token, path string, cb progressFn) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	_ = w.WriteField("token", token)
	_ = w.WriteField("to", "phone")
	_ = w.WriteField("kind", "file")
	name := filepath.Base(path)
	part, err := w.CreateFormFile("file", name)
	if err != nil {
		return err
	}
	if _, err := io.Copy(part, f); err != nil {
		return err
	}
	_ = w.Close()
	// прогресс считаем по телу запроса: именно его читает сетевой слой, когда отдаёт файл серверу
	if !dropSendRaw(&buf, int64(buf.Len()), w.FormDataContentType(), cb) {
		return fmt.Errorf("сервер не принял файл")
	}
	return nil
}

func dropSendRaw(body *bytes.Buffer, length int64, contentType string, cb progressFn) bool {
	var src io.Reader = body
	if cb != nil {
		src = &countingReader{r: body, cb: cb, total: length}
	}
	req, err := http.NewRequest(http.MethodPost, apiBase+"/chat/drop/send", src)
	if err != nil {
		return false
	}
	req.ContentLength = length
	req.Header.Set("Content-Type", contentType)
	req.Header.Set("User-Agent", "IziWindows/1")
	c := &http.Client{Timeout: 120 * time.Second}
	resp, err := c.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return false
	}
	var j map[string]any
	if err := json.Unmarshal(data, &j); err != nil {
		return false
	}
	ok, _ := j["ok"].(bool)
	return ok
}

// DropFetch — проверить, что пришло с телефона. since = время последнего ответа сервера.
func DropFetch(token string, since float64, wait bool) DropPoll {
	timeout := 12 * time.Second
	if wait {
		timeout = 35 * time.Second
	}
	code, data, err := postJSON("/chat/drop/poll", map[string]any{
		"token": token, "me": "pc", "since": since, "wait": wait,
	}, timeout)
	if err != nil {
		return DropPoll{Now: since}
	}
	if code == 401 {
		return DropPoll{Now: since, AuthExpired: true}
	}
	var j struct {
		Items       []DropItem `json:"items"`
		Now         float64    `json:"now"`
		OtherOnline bool       `json:"other_online"`
	}
	if err := json.Unmarshal(data, &j); err != nil {
		return DropPoll{Now: since}
	}
	if j.Now == 0 {
		j.Now = since
	}
	return DropPoll{Items: j.Items, Now: j.Now, OtherOnline: j.OtherOnline}
}

// DropDownload — скачать присланный файл по id, сохранить по указанному пути.
// cb получает ход скачивания (можно nil).
func DropDownload(token, id, destPath string, cb progressFn) error {
	b, _ := json.Marshal(map[string]any{"token": token, "id": id})
	req, err := http.NewRequest(http.MethodPost, apiBase+"/chat/drop/get", bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("User-Agent", "IziWindows/1")
	c := &http.Client{Timeout: 120 * time.Second}
	resp, err := c.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("сервер вернул код %d", resp.StatusCode)
	}
	out, err := os.Create(destPath)
	if err != nil {
		return err
	}
	defer out.Close()
	var src io.Reader = resp.Body
	if cb != nil {
		total := resp.ContentLength
		if total <= 0 {
			total = -1
		}
		src = &countingReader{r: resp.Body, cb: cb, total: total}
	}
	_, err = io.Copy(out, src)
	return err
}

// TTS — голос Изи с сервера. Возвращает nil, если сервер не смог озвучить.
func TTS(token, text string) []byte {
	body := map[string]any{"token": token, "text": text, "voice": "onyx"}
	b, _ := json.Marshal(body)
	req, err := http.NewRequest(http.MethodPost, apiBase+"/chat/izi_tts", bytes.NewReader(b))
	if err != nil {
		return nil
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("User-Agent", "IziWindows/1")
	c := &http.Client{Timeout: 60 * time.Second}
	resp, err := c.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	ct := resp.Header.Get("Content-Type")
	if resp.StatusCode < 200 || resp.StatusCode >= 300 || strings.Contains(ct, "json") || strings.Contains(ct, "text") {
		return nil
	}
	data, _ := io.ReadAll(resp.Body)
	if len(data) < 500 {
		return nil
	}
	return data
}
