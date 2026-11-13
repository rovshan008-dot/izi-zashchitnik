package main

// Изи Защитник для Windows: подключения, загрузка подписки и работа ядра sing-box.

import (
	"context"
	stdjson "encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
)

const (
	linkScheme         = "izi-zashchitnik"
	singBoxSuffix      = ".sb"
	maxConfigSize      = 5 << 20
	downloadTimeout    = 30 * time.Second
	statusStopped      = "stopped"
	statusStarting     = "starting"
	statusStarted      = "started"
	statusStopping     = "stopping"
	preferredGroupName = "proxy"
)

var linkPattern = regexp.MustCompile(`(?i)https?://[^\s"'<>]+`)

// Profile — одно подключение (подписка).
type Profile struct {
	ID      string    `json:"id"`
	Name    string    `json:"name"`
	URL     string    `json:"url"`
	Updated time.Time `json:"updated"`
	// Server — сервер, выбранный вручную для этого подключения ("" — как в подписке).
	Server string `json:"server,omitempty"`
}

type storedState struct {
	Profiles []*Profile `json:"profiles"`
	Selected string     `json:"selected"`
	NextID   int        `json:"nextId"`
}

// Link — ссылка на подписку, найденная в тексте.
type Link struct {
	URL  string `json:"url"`
	Name string `json:"name"`
	Host string `json:"host"`
}

type ServerView struct {
	Tag   string `json:"tag"`
	Delay int    `json:"delay"`
}

type ProfileView struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Updated int64  `json:"updated"`
}

type StateView struct {
	Status     string        `json:"status"`
	StartedAt  int64         `json:"startedAt"`
	Profiles   []ProfileView `json:"profiles"`
	Selected   string        `json:"selected"`
	Servers    []ServerView  `json:"servers"`
	Server     string        `json:"server"`
	AutoServer string        `json:"autoServer"`
	Error      string        `json:"error"`
	ErrorSeq   int           `json:"errorSeq"`
	Busy       string        `json:"busy"`
}

type AddResult struct {
	Name      string `json:"name"`
	Refreshed bool   `json:"refreshed"`
}

// Manager хранит подключения и управляет ядром. Все поля защищены mu.
type Manager struct {
	mu        sync.Mutex
	dir       string
	state     storedState
	status    string
	instance  *box.Box
	cancel    context.CancelFunc
	history   *urltest.HistoryStorage
	groupTag  string
	startedAt time.Time
	lastError string
	errorSeq  int
	busy      string
}

func NewManager(dir string) (*Manager, error) {
	if err := os.MkdirAll(filepath.Join(dir, "configs"), 0o700); err != nil {
		return nil, err
	}
	m := &Manager{dir: dir, status: statusStopped}
	content, err := os.ReadFile(m.statePath())
	if err == nil {
		_ = stdjson.Unmarshal(content, &m.state)
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if m.state.NextID < 1 {
		m.state.NextID = 1
	}
	return m, nil
}

func (m *Manager) statePath() string { return filepath.Join(m.dir, "state.json") }

func (m *Manager) LogPath() string { return filepath.Join(m.dir, "core.log") }

func (m *Manager) Dir() string { return m.dir }

func (m *Manager) configPath(id string) string {
	return filepath.Join(m.dir, "configs", id+".json")
}

// saveLocked записывает список подключений. Вызывать под mu.
func (m *Manager) saveLocked() error {
	content, err := stdjson.MarshalIndent(&m.state, "", "  ")
	if err != nil {
		return err
	}
	tmp := m.statePath() + ".tmp"
	if err := os.WriteFile(tmp, content, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, m.statePath())
}

func (m *Manager) findLocked(id string) *Profile {
	for _, p := range m.state.Profiles {
		if p.ID == id {
			return p
		}
	}
	return nil
}

func (m *Manager) selectedLocked() *Profile {
	if p := m.findLocked(m.state.Selected); p != nil {
		return p
	}
	if len(m.state.Profiles) > 0 {
		m.state.Selected = m.state.Profiles[0].ID
		return m.state.Profiles[0]
	}
	return nil
}

// ---- ссылки ----

// ParseLink достаёт ссылку на подписку из произвольного текста: обычная ссылка,
// ссылка кнопки с сайта (izi-zashchitnik://add?url=...&name=...) или sing-box://import-remote-profile.
func ParseLink(text string) *Link {
	raw := strings.TrimSpace(text)
	if raw == "" {
		return nil
	}
	lower := strings.ToLower(raw)
	if strings.HasPrefix(lower, linkScheme+":") || strings.HasPrefix(lower, "sing-box://import-remote-profile") {
		return parseAppLink(raw)
	}
	found := linkPattern.FindString(raw)
	if cleaned := cleanURL(found); cleaned != "" {
		return newLink(cleaned, "")
	}
	return nil
}

func parseAppLink(raw string) *Link {
	var target, name string
	if parsed, err := url.Parse(raw); err == nil {
		query := parsed.Query()
		target = query.Get("url")
		name = query.Get("name")
		if name == "" {
			name = parsed.Fragment
		}
	}
	if target == "" {
		// Ссылка без кодирования: берём всё после "url=".
		if index := strings.Index(raw, "url="); index >= 0 {
			rest := raw[index+4:]
			if decoded, err := url.QueryUnescape(rest); err == nil {
				rest = decoded
			}
			target = rest
		}
	}
	cleaned := cleanURL(linkPattern.FindString(target))
	if cleaned == "" {
		return nil
	}
	return newLink(cleaned, strings.TrimSpace(name))
}

func cleanURL(value string) string {
	cleaned := strings.TrimRight(strings.TrimSpace(value), ".,;)")
	lower := strings.ToLower(cleaned)
	if !strings.HasPrefix(lower, "http://") && !strings.HasPrefix(lower, "https://") {
		return ""
	}
	return cleaned
}

func newLink(target, name string) *Link {
	host := target
	if parsed, err := url.Parse(target); err == nil && parsed.Host != "" {
		host = parsed.Hostname()
	}
	return &Link{URL: target, Name: name, Host: host}
}

// candidates — адреса, которые стоит попробовать: сначала формат sing-box (та же ссылка + ".sb").
func candidates(link string) []string {
	path := strings.ToLower(strings.SplitN(strings.SplitN(link, "?", 2)[0], "#", 2)[0])
	if strings.ContainsAny(link, "?#") || strings.HasSuffix(path, singBoxSuffix) || strings.HasSuffix(path, ".json") {
		return []string{link}
	}
	return []string{strings.TrimRight(link, "/") + singBoxSuffix, link}
}

// ---- загрузка и проверка конфигурации ----

func parseOptions(ctx context.Context, content []byte) (option.Options, error) {
	return json.UnmarshalExtendedContext[option.Options](ctx, content)
}

func fetch(target string) ([]byte, error) {
	client := &http.Client{Timeout: downloadTimeout}
	request, err := http.NewRequest(http.MethodGet, target, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", "IziZashchitnik/"+appVersion+" sing-box/"+C.Version)
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("сервер ответил %d", response.StatusCode)
	}
	content, err := io.ReadAll(io.LimitReader(response.Body, maxConfigSize+1))
	if err != nil {
		return nil, err
	}
	if len(content) > maxConfigSize {
		return nil, errors.New("файл подключения слишком большой")
	}
	return content, nil
}

// download скачивает подписку в формате sing-box и проверяет, что ядро её понимает.
func download(link string) (string, []byte, error) {
	ctx := include.Context(context.Background())
	var lastErr error
	for _, candidate := range candidates(link) {
		content, err := fetch(candidate)
		if err == nil {
			_, err = parseOptions(ctx, content)
		}
		if err == nil {
			return candidate, content, nil
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = errors.New("нет ответа")
	}
	return "", nil, lastErr
}

// ---- подключения ----

func (m *Manager) uniqueNameLocked(wanted string) string {
	taken := map[string]bool{}
	for _, p := range m.state.Profiles {
		taken[p.Name] = true
	}
	if wanted != "" {
		if !taken[wanted] {
			return wanted
		}
		for i := 2; ; i++ {
			if name := wanted + " " + strconv.Itoa(i); !taken[name] {
				return name
			}
		}
	}
	for i := len(m.state.Profiles) + 1; ; i++ {
		if name := "Подключение " + strconv.Itoa(i); !taken[name] {
			return name
		}
	}
}

// Add скачивает подписку и добавляет подключение (или обновляет уже добавленное с той же ссылкой).
func (m *Manager) Add(link *Link) (AddResult, error) {
	working, content, err := download(link.URL)
	if err != nil {
		return AddResult{}, fmt.Errorf("Не получилось добавить по ссылке\n%s\n\nПроверьте, что скопирована ссылка на подключение и что есть интернет.\n\nПодробности: %v", link.URL, err)
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, p := range m.state.Profiles {
		if p.URL == working {
			if err := os.WriteFile(m.configPath(p.ID), content, 0o600); err != nil {
				return AddResult{}, err
			}
			p.Updated = time.Now()
			m.state.Selected = p.ID
			return AddResult{Name: p.Name, Refreshed: true}, m.saveLocked()
		}
	}
	id := strconv.Itoa(m.state.NextID)
	m.state.NextID++
	if err := os.WriteFile(m.configPath(id), content, 0o600); err != nil {
		return AddResult{}, err
	}
	profile := &Profile{ID: id, Name: m.uniqueNameLocked(link.Name), URL: working, Updated: time.Now()}
	m.state.Profiles = append(m.state.Profiles, profile)
	previous := m.state.Selected
	m.state.Selected = id
	if err := m.saveLocked(); err != nil {
		return AddResult{}, err
	}
	// Новое подключение выбирается сразу; если защита включена на другом — переключаемся.
	if m.status == statusStarted && previous != id {
		go m.restart()
	}
	return AddResult{Name: profile.Name}, nil
}

// Refresh заново скачивает подписку подключения.
func (m *Manager) Refresh(id string) error {
	m.mu.Lock()
	profile := m.findLocked(id)
	if profile == nil {
		m.mu.Unlock()
		return errors.New("подключение не найдено")
	}
	target := profile.URL
	m.mu.Unlock()

	_, content, err := download(target)
	if err != nil {
		return fmt.Errorf("Не получилось обновить подключение.\n\nПодробности: %v", err)
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if profile = m.findLocked(id); profile == nil {
		return nil
	}
	if err := os.WriteFile(m.configPath(id), content, 0o600); err != nil {
		return err
	}
	profile.Updated = time.Now()
	if err := m.saveLocked(); err != nil {
		return err
	}
	if m.status == statusStarted && m.state.Selected == id {
		go m.restart()
	}
	return nil
}

func (m *Manager) Rename(id, name string) error {
	name = strings.TrimSpace(name)
	if name == "" {
		return errors.New("название не может быть пустым")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	profile := m.findLocked(id)
	if profile == nil {
		return errors.New("подключение не найдено")
	}
	profile.Name = name
	return m.saveLocked()
}

func (m *Manager) Remove(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	index := -1
	for i, p := range m.state.Profiles {
		if p.ID == id {
			index = i
		}
	}
	if index < 0 {
		return nil
	}
	m.state.Profiles = append(m.state.Profiles[:index], m.state.Profiles[index+1:]...)
	_ = os.Remove(m.configPath(id))
	wasSelected := m.state.Selected == id
	if wasSelected {
		m.state.Selected = ""
		if len(m.state.Profiles) > 0 {
			m.state.Selected = m.state.Profiles[0].ID
		}
	}
	if err := m.saveLocked(); err != nil {
		return err
	}
	if wasSelected && m.status == statusStarted {
		if m.state.Selected == "" {
			go m.Stop()
		} else {
			go m.restart()
		}
	}
	return nil
}

func (m *Manager) SelectProfile(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.findLocked(id) == nil {
		return errors.New("подключение не найдено")
	}
	if m.state.Selected == id {
		return nil
	}
	m.state.Selected = id
	if err := m.saveLocked(); err != nil {
		return err
	}
	if m.status == statusStarted {
		go m.restart()
	}
	return nil
}

// SelectServer запоминает сервер для выбранного подключения и, если защита включена, переключает сразу.
func (m *Manager) SelectServer(tag string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	profile := m.selectedLocked()
	if profile == nil {
		return errors.New("нет подключения")
	}
	profile.Server = tag
	if err := m.saveLocked(); err != nil {
		return err
	}
	if m.status != statusStarted || m.instance == nil {
		return nil
	}
	outbound, loaded := m.instance.Outbound().Outbound(m.groupTag)
	if !loaded {
		return nil
	}
	if selector, ok := outbound.(interface{ SelectOutbound(string) bool }); ok {
		if !selector.SelectOutbound(tag) {
			return errors.New("такого сервера нет в подключении")
		}
	}
	return nil
}

// ---- ядро ----

// pickSelector — группа выбора сервера: «proxy», иначе первая группа-переключатель.
func pickSelector(outbounds []option.Outbound) *option.Outbound {
	var first *option.Outbound
	for i := range outbounds {
		if outbounds[i].Type != C.TypeSelector {
			continue
		}
		if first == nil {
			first = &outbounds[i]
		}
		if strings.EqualFold(outbounds[i].Tag, preferredGroupName) {
			return &outbounds[i]
		}
	}
	return first
}

func (m *Manager) Toggle() {
	m.mu.Lock()
	status := m.status
	m.mu.Unlock()
	switch status {
	case statusStopped:
		go m.Start()
	case statusStarted:
		go m.Stop()
	}
}

func (m *Manager) Start() {
	m.mu.Lock()
	if m.status != statusStopped {
		m.mu.Unlock()
		return
	}
	profile := m.selectedLocked()
	if profile == nil {
		m.mu.Unlock()
		return
	}
	m.status = statusStarting
	profileID, server := profile.ID, profile.Server
	m.mu.Unlock()

	instance, cancel, history, groupTag, err := m.startCore(profileID, server)

	m.mu.Lock()
	defer m.mu.Unlock()
	if err != nil {
		m.status = statusStopped
		m.lastError = err.Error()
		m.errorSeq++
		return
	}
	m.instance, m.cancel, m.history, m.groupTag = instance, cancel, history, groupTag
	m.startedAt = time.Now()
	m.status = statusStarted
}

func (m *Manager) startCore(profileID, server string) (*box.Box, context.CancelFunc, *urltest.HistoryStorage, string, error) {
	content, err := os.ReadFile(m.configPath(profileID))
	if err != nil {
		return nil, nil, nil, "", fmt.Errorf("Не найден файл подключения. Обновите или добавьте подключение заново.\n\nПодробности: %v", err)
	}
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	history := urltest.NewHistoryStorage()
	ctx = service.ContextWithPtr(ctx, history)

	options, err := parseOptions(ctx, content)
	if err != nil {
		cancel()
		return nil, nil, nil, "", fmt.Errorf("Файл подключения повреждён. Обновите подключение.\n\nПодробности: %v", err)
	}
	// Журнал ядра — в папку программы, заново при каждом включении.
	_ = os.Remove(m.LogPath())
	options.Log = &option.LogOptions{Level: "warn", Output: m.LogPath(), Timestamp: true}

	groupTag := ""
	if group := pickSelector(options.Outbounds); group != nil {
		groupTag = group.Tag
		if selector, ok := group.Options.(*option.SelectorOutboundOptions); ok && selector != nil {
			// Старые соединения обрываются при смене сервера, чтобы всё сразу пошло через новый.
			selector.InterruptExistConnections = true
			for _, tag := range selector.Outbounds {
				if tag == server {
					selector.Default = server
				}
			}
		}
	}

	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		cancel()
		return nil, nil, nil, "", fmt.Errorf("Не удалось подготовить защиту.\n\nПодробности: %v", err)
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		cancel()
		return nil, nil, nil, "", fmt.Errorf("Не удалось включить защиту. Попробуйте ещё раз или выберите другое подключение.\n\nПодробности: %v", err)
	}
	return instance, cancel, history, groupTag, nil
}

func (m *Manager) Stop() {
	m.mu.Lock()
	if m.status != statusStarted {
		m.mu.Unlock()
		return
	}
	m.status = statusStopping
	instance, cancel := m.instance, m.cancel
	m.instance, m.cancel, m.history = nil, nil, nil
	m.mu.Unlock()

	if instance != nil {
		_ = instance.Close()
	}
	if cancel != nil {
		cancel()
	}

	m.mu.Lock()
	m.status = statusStopped
	m.mu.Unlock()
}

// restart — после смены подключения: выключить и включить заново.
func (m *Manager) restart() {
	m.Stop()
	m.Start()
}

// ---- состояние для экрана ----

// serversFromFile — список серверов из файла подключения, пока защита выключена.
func serversFromFile(path string) ([]string, string) {
	content, err := os.ReadFile(path)
	if err != nil {
		return nil, ""
	}
	var config struct {
		Outbounds []struct {
			Type      string   `json:"type"`
			Tag       string   `json:"tag"`
			Outbounds []string `json:"outbounds"`
			Default   string   `json:"default"`
		} `json:"outbounds"`
	}
	if stdjson.Unmarshal(content, &config) != nil {
		return nil, ""
	}
	index := -1
	for i, item := range config.Outbounds {
		if item.Type != C.TypeSelector {
			continue
		}
		if index < 0 {
			index = i
		}
		if strings.EqualFold(item.Tag, preferredGroupName) {
			index = i
			break
		}
	}
	if index < 0 {
		return nil, ""
	}
	group := config.Outbounds[index]
	selected := group.Default
	if selected == "" && len(group.Outbounds) > 0 {
		selected = group.Outbounds[0]
	}
	return group.Outbounds, selected
}

func (m *Manager) State() StateView {
	m.mu.Lock()
	defer m.mu.Unlock()
	view := StateView{
		Status:   m.status,
		Error:    m.lastError,
		ErrorSeq: m.errorSeq,
		Busy:     m.busy,
		Profiles: []ProfileView{},
		Servers:  []ServerView{},
	}
	for _, p := range m.state.Profiles {
		view.Profiles = append(view.Profiles, ProfileView{ID: p.ID, Name: p.Name, Updated: p.Updated.UnixMilli()})
	}
	profile := m.selectedLocked()
	if profile == nil {
		return view
	}
	view.Selected = profile.ID
	if m.status == statusStarted {
		view.StartedAt = m.startedAt.UnixMilli()
	}

	if m.status == statusStarted && m.instance != nil && m.groupTag != "" {
		if outbound, loaded := m.instance.Outbound().Outbound(m.groupTag); loaded {
			if group, ok := outbound.(adapter.OutboundGroup); ok {
				for _, tag := range group.All() {
					server := ServerView{Tag: tag}
					if m.history != nil {
						if record := m.history.LoadURLTestHistory(tag); record != nil {
							server.Delay = int(record.Delay)
						}
					}
					view.Servers = append(view.Servers, server)
				}
				if selected := group.Selected("tcp"); selected != nil {
					view.Server = selected.Tag()
					// «Авто» — вложенная группа, которая сама выбирает сервер: показываем, какой именно.
					if inner, ok := selected.(adapter.OutboundGroup); ok {
						if now := inner.Selected("tcp"); now != nil {
							view.AutoServer = now.Tag()
						}
					}
				}
				return view
			}
		}
	}

	tags, selected := serversFromFile(m.configPath(profile.ID))
	for _, tag := range tags {
		view.Servers = append(view.Servers, ServerView{Tag: tag})
		if tag == profile.Server {
			selected = tag
		}
	}
	view.Server = selected
	return view
}

// SetBusy — короткая подпись для экрана, пока идёт долгая операция (добавление, обновление).
func (m *Manager) SetBusy(text string) {
	m.mu.Lock()
	m.busy = text
	m.mu.Unlock()
}

func (m *Manager) IsRunning() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.status != statusStopped
}
