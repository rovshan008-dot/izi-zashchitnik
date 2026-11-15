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
