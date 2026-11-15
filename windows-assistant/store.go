package main

// Локальное хранение: токен входа и история чата (как ChatStore.kt у Android).

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

type Msg struct {
	ID    int64  `json:"id"`
	Mine  bool   `json:"mine"`
	Text  string `json:"text"`
	Image string `json:"image,omitempty"`
}

type stateFile struct {
	Token     string  `json:"token"`
	Messages  []Msg   `json:"messages"`
	DropSince float64 `json:"dropSince"`
}

type Store struct {
	mu   sync.Mutex
	path string
	data stateFile
}

func NewStore(dataDir string) (*Store, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, err
	}
	s := &Store{path: filepath.Join(dataDir, "state.json")}
	s.load()
	return s, nil
}

func (s *Store) load() {
	b, err := os.ReadFile(s.path)
	if err != nil {
		return
	}
	_ = json.Unmarshal(b, &s.data)
}

func (s *Store) save() {
	b, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return
	}
	_ = os.WriteFile(s.path, b, 0o644)
}

func (s *Store) Token() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.data.Token
}

func (s *Store) SetToken(token string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data.Token = token
	s.save()
}

func (s *Store) Logout() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data.Token = ""
	s.data.Messages = nil
	s.save()
}

func (s *Store) History() []Msg {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Msg, len(s.data.Messages))
	copy(out, s.data.Messages)
	return out
}

func (s *Store) DropSince() float64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.data.DropSince
}

func (s *Store) SetDropSince(v float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data.DropSince = v
	s.save()
}

func (s *Store) Add(m Msg) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data.Messages = append(s.data.Messages, m)
	// история не бесконечная — как и на телефоне, держим разумный хвост
	if len(s.data.Messages) > 400 {
		s.data.Messages = s.data.Messages[len(s.data.Messages)-400:]
	}
	s.save()
}
