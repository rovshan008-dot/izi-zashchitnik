package main

// Изи Скринер: живые данные Binance Futures через собственный сервер (izi20.tech/screener-api/).
// Авторизация не нужна, модель не участвует — поэтому срез приходит мгновенно и бесплатно.
//
// Проверенные эндпоинты:
//   GET /screener-api/                      → {ok, updated, count, data:[{sym,pair,price,chg,vol,trades,funding,nextFunding,high,low,natr}]}
//   GET /screener-api/stats?symbol=BTCUSDT  → {ok, symbol, funding, nextFunding, oi_usd, buyPct}

import (
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

type ScreenerRow struct {
	Sym         string  `json:"sym"`
	Pair        string  `json:"pair"`
	Price       float64 `json:"price"`
	Chg         float64 `json:"chg"`
	Vol         float64 `json:"vol"`
	Trades      int64   `json:"trades"`
	Funding     float64 `json:"funding"`
	NextFunding int64   `json:"nextFunding"`
	High        float64 `json:"high"`
	Low         float64 `json:"low"`
	Natr        float64 `json:"natr"`
}

type screenerFeed struct {
	OK      bool          `json:"ok"`
	Updated float64       `json:"updated"`
	Count   int           `json:"count"`
	Data    []ScreenerRow `json:"data"`
	Error   string        `json:"error"`
}

type coinStats struct {
	OK          bool    `json:"ok"`
	Symbol      string  `json:"symbol"`
	Funding     float64 `json:"funding"`
	NextFunding int64   `json:"nextFunding"`
	OIUsd       float64 `json:"oi_usd"`
	BuyPct      float64 `json:"buyPct"`
}

var (
	feedMu   sync.Mutex
	feedData []ScreenerRow
	feedAt   time.Time
)

func screenerClient() *http.Client { return &http.Client{Timeout: 15 * time.Second} }

// feed — список монет, с кэшом на 5 секунд (сервер и сам обновляет раз в ~4 с).
func feed() ([]ScreenerRow, error) {
	feedMu.Lock()
	defer feedMu.Unlock()
	if time.Since(feedAt) < 5*time.Second && len(feedData) > 0 {
		return feedData, nil
	}
	resp, err := screenerClient().Get(apiBase + "/screener-api/")
	if err != nil {
		return feedData, err
	}
	defer resp.Body.Close()
	var f screenerFeed
	if err := json.NewDecoder(resp.Body).Decode(&f); err != nil {
		return feedData, err
	}
	if !f.OK || len(f.Data) == 0 {
		if f.Error != "" {
			return feedData, fmt.Errorf("%s", f.Error)
		}
		return feedData, fmt.Errorf("скринер не отдал данные")
	}
	feedData, feedAt = f.Data, time.Now()
	return feedData, nil
}

func fetchStats(pair string) *coinStats {
	resp, err := screenerClient().Get(apiBase + "/screener-api/stats?symbol=" + pair)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	var s coinStats
	if err := json.NewDecoder(resp.Body).Decode(&s); err != nil || !s.OK {
		return nil
	}
	return &s
}

// ---------- как человек называет монеты ----------

var coinAliases = map[string]string{
	"биток": "BTC", "битка": "BTC", "битку": "BTC", "биткоин": "BTC", "биткоина": "BTC",
	"биткоину": "BTC", "бтс": "BTC", "бит": "BTC",
	"эфир": "ETH", "эфира": "ETH", "эфиру": "ETH", "эфириум": "ETH", "итх": "ETH",
	"солана": "SOL", "соланы": "SOL", "сол": "SOL", "солане": "SOL",
	"бнб": "BNB", "банан": "BNB",
	"рипл": "XRP", "рипла": "XRP", "иксрп": "XRP",
	"дог": "DOGE", "доги": "DOGE", "догикоин": "DOGE", "додж": "DOGE",
	"тон": "TON", "тона": "TON", "тоncoin": "TON",
	"трон": "TRX", "трона": "TRX",
	"кардано": "ADA", "ада": "ADA",
	"авакс": "AVAX", "аваланч": "AVAX",
	"линк": "LINK", "чейнлинк": "LINK",
	"дот": "DOT", "полкадот": "DOT",
	"полигон": "POL", "матик": "POL",
	"лайт": "LTC", "лайткоин": "LTC",
	"шиба": "SHIB", "шибу": "SHIB",
	"пепе": "PEPE",
	"суи":  "SUI", "сюй": "SUI",
	"апт": "APT", "аптос": "APT",
	"арб": "ARB", "арбитрум": "ARB",
	"оп": "OP", "оптимизм": "OP",
	"инж":  "INJ",
	"атом": "ATOM", "космос": "ATOM",
	"нир": "NEAR", "ниар": "NEAR",
	"фил": "FIL", "файлкоин": "FIL",
	"хбар": "HBAR",
	"уни":  "UNI", "юнисвап": "UNI",
	"аве": "AAVE",
	"вив": "WIF", "виф": "WIF",
	"бонк": "BONK",
}

// resolveCoin — «что по битку» → BTC. Сначала псевдоним, потом совпадение с тикером из скринера.
func resolveCoin(word string) (ScreenerRow, bool) {
	rows, err := feed()
	if err != nil && len(rows) == 0 {
		return ScreenerRow{}, false
	}
	q := normKey(word)
	if q == "" {
		return ScreenerRow{}, false
	}
	if sym, ok := coinAliases[q]; ok {
		q = normKey(sym)
	}
	q = strings.TrimSuffix(q, "usdt")
	for _, r := range rows {
		if normKey(r.Sym) == q {
			return r, true
		}
	}
	return ScreenerRow{}, false
}

// ---------- числа по-русски ----------

func spaced(s string) string {
	neg := strings.HasPrefix(s, "-")
	s = strings.TrimPrefix(s, "-")
	var out []string
	for len(s) > 3 {
		out = append([]string{s[len(s)-3:]}, out...)
		s = s[:len(s)-3]
	}
	out = append([]string{s}, out...)
	res := strings.Join(out, " ")
	if neg {
		res = "-" + res
	}
	return res
}

// money — цена: мелкие монеты с большим числом знаков, крупные — с двумя.
func money(v float64) string {
	a := math.Abs(v)
	switch {
	case a >= 1000:
		return spaced(fmt.Sprintf("%.0f", v))
	case a >= 1:
		return strings.Replace(fmt.Sprintf("%.2f", v), ".", ",", 1)
	case a >= 0.01:
		return strings.Replace(fmt.Sprintf("%.4f", v), ".", ",", 1)
	default:
		return strings.Replace(fmt.Sprintf("%.8f", v), ".", ",", 1)
	}
}

// trimZero — «18,40» → «18,4», «18,00» → «18».
func trimZero(s string) string {
	s = strings.TrimRight(s, "0")
	s = strings.TrimSuffix(s, ".")
	return strings.Replace(s, ".", ",", 1)
}

// big — 18 400 000 000 → «18,4 млрд».
func big(v float64) string {
	a := math.Abs(v)
	switch {
	case a >= 1e12:
		return trimZero(fmt.Sprintf("%.2f", v/1e12)) + " трлн"
	case a >= 1e9:
		return trimZero(fmt.Sprintf("%.2f", v/1e9)) + " млрд"
	case a >= 1e6:
		return strings.Replace(fmt.Sprintf("%.1f", v/1e6), ".", ",", 1) + " млн"
	case a >= 1e3:
		return spaced(fmt.Sprintf("%.0f", v))
	default:
		return fmt.Sprintf("%.0f", v)
	}
}

// pct — «+1,42%»: со знаком, для роста и падения.
func pct(v float64, digits int) string {
	return strings.Replace(fmt.Sprintf("%+.*f", digits, v), ".", ",", 1) + "%"
}

// plain — «2,1%»: без знака, для волатильности и доли покупателей.
func plain(v float64, digits int) string {
	return strings.Replace(fmt.Sprintf("%.*f", digits, v), ".", ",", 1) + "%"
}

// until — «через 3 ч 12 мин» до следующей выплаты фандинга.
func until(ms int64) string {
	if ms <= 0 {
		return ""
	}
	d := time.Until(time.UnixMilli(ms))
	if d <= 0 {
		return "вот-вот"
	}
	h := int(d.Hours())
	m := int(d.Minutes()) % 60
	if h > 0 {
		return fmt.Sprintf("через %d ч %d мин", h, m)
	}
	return fmt.Sprintf("через %d мин", m)
}

// ---------- то, что уходит на экран и в чат ----------

type ScreenerView struct {
	OK      bool          `json:"ok"`
	Updated string        `json:"updated"`
	Rows    []ScreenerRow `json:"rows"`
	Error   string        `json:"error,omitempty"`
}

// ScreenerTop — для вкладки «Скринер»: kind = "gainers" | "losers" | "volume".
func (a *App) ScreenerTop(kind string, limit int) ScreenerView {
	rows, err := feed()
	if err != nil && len(rows) == 0 {
		return ScreenerView{Error: "скринер недоступен: " + err.Error()}
	}
	cp := make([]ScreenerRow, len(rows))
	copy(cp, rows)
	switch kind {
	case "losers":
		sort.Slice(cp, func(i, j int) bool { return cp[i].Chg < cp[j].Chg })
	case "volume":
		sort.Slice(cp, func(i, j int) bool { return cp[i].Vol > cp[j].Vol })
	default:
		sort.Slice(cp, func(i, j int) bool { return cp[i].Chg > cp[j].Chg })
	}
	if limit <= 0 || limit > len(cp) {
		limit = len(cp)
	}
	return ScreenerView{OK: true, Updated: time.Now().Format("15:04:05"), Rows: cp[:limit]}
}

// ScreenerCoin — срез по одной монете человеческим текстом (как в @izi_coin_bot).
func (a *App) ScreenerCoin(word string) string {
	r, ok := resolveCoin(word)
	if !ok {
		return ""
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s — %s $ (%s за сутки)\n", r.Sym, money(r.Price), pct(r.Chg, 2))
	fmt.Fprintf(&b, "Сутки: от %s до %s · волатильность %s\n", money(r.Low), money(r.High), plain(r.Natr, 1))
	fmt.Fprintf(&b, "Объём 24ч: %s $ · сделок %s\n", big(r.Vol), big(float64(r.Trades)))

	s := fetchStats(r.Pair)
	fund, next := r.Funding, r.NextFunding
	if s != nil {
		fund, next = s.Funding, s.NextFunding
	}
	line := "Фандинг: " + pct(fund, 4)
	if t := until(next); t != "" {
		line += " · выплата " + t
	}
	b.WriteString(line + "\n")
	if s != nil {
		if s.OIUsd > 0 {
			fmt.Fprintf(&b, "Открытый интерес: %s $\n", big(s.OIUsd))
		}
		if s.BuyPct > 0 {
			fmt.Fprintf(&b, "Перевес покупателей: %s от объёма\n", plain(s.BuyPct, 1))
		}
	}
	fmt.Fprintf(&b, "\nBinance Futures, данные на %s", time.Now().Format("15:04:05"))
	return b.String()
}

// ScreenerSummary — «что на скринере»: кто сильнее всех вырос и упал.
func (a *App) ScreenerSummary() string {
	rows, err := feed()
	if err != nil && len(rows) == 0 {
		return ""
	}
	up := make([]ScreenerRow, len(rows))
	copy(up, rows)
	sort.Slice(up, func(i, j int) bool { return up[i].Chg > up[j].Chg })
	n := 5
	if len(up) < 11 {
		n = len(up) / 2
	}
	if n == 0 {
		return ""
	}
	var b strings.Builder
	b.WriteString("Растут сильнее всех:\n")
	for _, r := range up[:n] {
		fmt.Fprintf(&b, "  %s  %s $  %s\n", r.Sym, money(r.Price), pct(r.Chg, 1))
	}
	b.WriteString("\nПадают сильнее всех:\n")
	for i := 0; i < n; i++ {
		r := up[len(up)-1-i]
		fmt.Fprintf(&b, "  %s  %s $  %s\n", r.Sym, money(r.Price), pct(r.Chg, 1))
	}
	fmt.Fprintf(&b, "\nBinance Futures, %d монет, данные на %s", len(rows), time.Now().Format("15:04:05"))
	return b.String()
}
