// Package mobile is the Android binding surface for the downloader core.
//
// It is built with `gomobile bind` into an AAR (see build.sh), so every
// exported symbol here must use only gomobile-supported types: strings,
// numbers, booleans and errors. Structured data crosses the boundary as JSON
// and progress is polled with Progress() rather than pushed through callbacks.
package mobile

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	wiiu "github.com/Xpl0itU/WiiUDownloader"
)

const (
	// AppVersion is shown in the Android UI.
	AppVersion = "1.2.2-android"

	userAgent = "WiiUDownloader-Android"
)

// Title is the JSON shape the app consumes for one database entry.
type Title struct {
	TitleID    string `json:"titleId"`
	TitleIDNum uint64 `json:"-"`
	Name       string `json:"name"`
	Region     int    `json:"region"`
	RegionName string `json:"regionName"`
	Kind       string `json:"kind"`
	Category   int    `json:"category"`
	Version    int    `json:"version"`
}

type progressState struct {
	mu sync.Mutex

	running   bool
	title     string
	phase     string
	message   string
	err       string
	total     int64
	completed int64
	perFile   map[string]int64
	cancelled bool

	speed      int64
	lastBytes  int64
	lastSample time.Time

	done   chan struct{}
	cancel sync.Once
}

var state = &progressState{
	phase: "idle",
	done:  make(chan struct{}),
}

var httpClient = &http.Client{
	Timeout: 0,
	Transport: &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		MaxIdleConns:        8,
		MaxIdleConnsPerHost: 8,
		IdleConnTimeout:     30 * time.Second,
	},
}

// Version reports the binding version.
func Version() string {
	return AppVersion
}

// SetTempDirectory points the core's temporary files at a directory the app can
// write to. Go resolves os.TempDir() to /data/local/tmp on Android, which apps
// cannot write, so the first certificate download otherwise fails with a
// permission error. The app passes its cache directory during startup.
func SetTempDirectory(dir string) {
	trimmed := strings.TrimSpace(dir)
	if trimmed == "" {
		return
	}
	wiiu.TempDir = trimmed
	_ = os.Setenv("TMPDIR", trimmed)
}

// ImportTitleDatabaseJSON replaces the in-memory title database. The payload is
// a JSON array of objects; title IDs may be given as "titleId" (hex string,
// with or without dashes) and regions as either a bit mask or a name.
func ImportTitleDatabaseJSON(data string) (int, error) {
	entries, err := parseTitleDatabase(data)
	if err != nil {
		return 0, err
	}
	wiiu.SetTitleDatabase(entries)
	return len(entries), nil
}

// ImportEshopCatalogJSON loads the public eShop catalogue published by the
// nus-info project, which the app downloads when it has no title list yet. The
// payload is an object keyed by region ("US", "GB", "JP", ...) whose values map
// title IDs to objects carrying "title_id" and "name". Only those fields are
// read: descriptions, screenshots and availability data are skipped while the
// stream is decoded, which keeps the ~8 MB file cheap to import.
func ImportEshopCatalogJSON(data string) (int, error) {
	entries, err := parseEshopCatalog(data)
	if err != nil {
		return 0, err
	}
	wiiu.SetTitleDatabase(entries)
	return len(entries), nil
}

// DatabaseCount reports how many titles the database holds.
func DatabaseCount() int {
	return len(wiiu.TitleDatabase)
}

// SearchTitles returns a JSON array of matching titles. category selects a
// single category (TITLE_CATEGORY_ALL for every category) and regionMask is a
// bit mask of MCP_REGION_* values; 0 means no region filter. limit <= 0 means
// no limit.
func SearchTitles(query string, category int, regionMask int, limit int) string {
	titles := make([]Title, 0, 64)
	normalizedQuery := normalizeSearchText(query)
	queryLower := strings.ToLower(strings.TrimSpace(query))

	for _, entry := range wiiu.GetTitleEntries(uint8(category)) {
		if regionMask != 0 && int(entry.Region)&regionMask == 0 {
			continue
		}
		title := describeTitle(entry)
		if normalizedQuery != "" && !titleMatches(queryLower, normalizedQuery, title) {
			continue
		}
		titles = append(titles, title)
		if limit > 0 && len(titles) >= limit {
			break
		}
	}
	return mustJSON(titles)
}

// TitleInfo returns a JSON object for one title ID, or an error when the ID is
// malformed.
func TitleInfo(titleID string) (string, error) {
	tid, err := parseTitleID(titleID)
	if err != nil {
		return "", err
	}
	return mustJSON(describeTitle(wiiu.GetTitleEntryFromTid(tid))), nil
}

// TitleSize fetches the size in bytes a download of this title would transfer.
func TitleSize(titleID string, version int) (int64, error) {
	tid, err := parseTitleID(titleID)
	if err != nil {
		return 0, err
	}
	size, err := wiiu.FetchTMDSize(tid, version, httpClient)
	return int64(size), err
}

// DownloadTitle downloads and optionally decrypts one title. It blocks until the
// transfer finishes, so callers run it off the UI thread. An error string is
// returned through Progress() as well, so the UI can show failures.
func DownloadTitle(titleID string, version int, outputDirectory string, decrypt bool, deleteEncrypted bool) error {
	tid, err := parseTitleID(titleID)
	if err != nil {
		return err
	}
	if strings.TrimSpace(outputDirectory) == "" {
		return fmt.Errorf("no output directory")
	}

	entry := wiiu.GetTitleEntryFromTid(tid)
	tidStr := fmt.Sprintf("%016x", tid)
	name := entry.Name
	if name == "" {
		name = tidStr
	}

	beginRun(name)
	defer endRun()

	titlePath := fmt.Sprintf("%s/%s [%s] [%s]", strings.TrimRight(outputDirectory, "/"),
		sanitizeFilename(name), wiiu.GetFormattedKind(tid), tidStr)
	if version >= 0 {
		titlePath = fmt.Sprintf("%s [v%d]", titlePath, version)
	}

	runErr := wiiu.DownloadTitle(tidStr, titlePath, version, decrypt, reporter{}, deleteEncrypted, httpClient, "")
	setResult(runErr)
	return runErr
}

// DecryptFolder decrypts an already downloaded title directory. An empty
// outputDirectory decrypts in place.
func DecryptFolder(folder string, outputDirectory string, deleteEncrypted bool) error {
	if strings.TrimSpace(folder) == "" {
		return fmt.Errorf("no folder selected")
	}

	beginRun("Decrypting")
	defer endRun()

	setPhase("decrypt", "Decrypting")
	runErr := wiiu.DecryptContents(folder, reporter{}, deleteEncrypted, outputDirectory)
	setResult(runErr)
	return runErr
}

// DecryptPickedFolder decrypts the folder chosen in the app's folder picker.
//
// Android hands back whatever folder the user tapped, which is often the folder
// that *contains* the downloaded title folders (or the app's output folder with
// several titles in it) rather than a single title folder. So this accepts
// either: the picked folder is decrypted when it holds a title.tmd, otherwise
// every folder below it that does is decrypted in turn. It returns how many
// folders were decrypted.
func DecryptPickedFolder(folder string, deleteEncrypted bool) (int, error) {
	root := strings.TrimSpace(folder)
	if root == "" {
		return 0, fmt.Errorf("no folder selected")
	}

	targets := titleFoldersUnder(root)
	if len(targets) == 0 {
		return 0, fmt.Errorf("no title.tmd in %q or its subfolders: pick the downloaded title folder (the one that holds title.tmd)", filepath.Base(root))
	}

	beginRun("Decrypting")
	defer endRun()

	decrypted := 0
	var firstErr error
	for _, target := range targets {
		if cancelled() {
			break
		}
		setPhase("decrypt", "Decrypting "+filepath.Base(target))
		if err := wiiu.DecryptContents(target, reporter{}, deleteEncrypted, target); err != nil {
			firstErr = fmt.Errorf("%s: %w", filepath.Base(target), err)
			setResult(firstErr)
			break
		}
		decrypted++
	}
	return decrypted, firstErr
}

// titleFoldersUnder lists the folders to decrypt for root: root itself when it
// holds a title.tmd, otherwise the descendant folders that do. A folder that is
// found is not searched further, so a title folder's own files are never
// mistaken for another title.
func titleFoldersUnder(root string) []string {
	if hasTitleTMD(root) {
		return []string{root}
	}

	found := make([]string, 0, 4)
	const maxDepth = 3

	var walk func(dir string, depth int)
	walk = func(dir string, depth int) {
		entries, err := os.ReadDir(dir)
		if err != nil {
			return
		}
		for _, entry := range entries {
			if !entry.IsDir() {
				continue
			}
			child := filepath.Join(dir, entry.Name())
			if hasTitleTMD(child) {
				found = append(found, child)
				continue
			}
			if depth+1 < maxDepth {
				walk(child, depth+1)
			}
		}
	}
	walk(root, 0)

	sort.Strings(found)
	return found
}

func hasTitleTMD(dir string) bool {
	info, err := os.Stat(filepath.Join(dir, "title.tmd"))
	return err == nil && info.Mode().IsRegular()
}

// Cancel requests cancellation of the running transfer.
func Cancel() {
	state.mu.Lock()
	if state.running {
		state.cancelled = true
		state.phase = "cancelling"
		state.message = "Cancelling..."
	}
	state.mu.Unlock()
	state.cancel.Do(func() {
		close(state.done)
	})
}

// IsRunning reports whether a transfer is in flight.
func IsRunning() bool {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.running
}

// Progress returns the current transfer state as a JSON object.
func Progress() string {
	state.mu.Lock()
	defer state.mu.Unlock()

	downloaded := state.completed
	for _, value := range state.perFile {
		downloaded += value
	}

	now := time.Now()
	if state.running && !state.lastSample.IsZero() {
		elapsed := now.Sub(state.lastSample).Seconds()
		if elapsed >= 0.2 {
			delta := downloaded - state.lastBytes
			if delta < 0 {
				delta = 0
			}
			instant := int64(float64(delta) / elapsed)
			// Light smoothing keeps the readout from jumping around.
			if state.speed == 0 {
				state.speed = instant
			} else {
				state.speed = int64(0.6*float64(instant) + 0.4*float64(state.speed))
			}
			state.lastBytes = downloaded
			state.lastSample = now
		}
	}

	percent := 0.0
	if state.total > 0 {
		percent = float64(downloaded) / float64(state.total)
		if percent > 1 {
			percent = 1
		}
	}

	payload := struct {
		Running    bool    `json:"running"`
		Title      string  `json:"title"`
		Phase      string  `json:"phase"`
		Message    string  `json:"message"`
		Downloaded int64   `json:"downloaded"`
		Total      int64   `json:"total"`
		Percent    float64 `json:"percent"`
		Speed      int64   `json:"speed"`
		Cancelled  bool    `json:"cancelled"`
		Error      string  `json:"error"`
	}{
		Running:    state.running,
		Title:      state.title,
		Phase:      state.phase,
		Message:    state.message,
		Downloaded: downloaded,
		Total:      state.total,
		Percent:    percent,
		Speed:      state.speed,
		Cancelled:  state.cancelled,
		Error:      state.err,
	}
	return mustJSON(payload)
}

func beginRun(title string) {
	state.mu.Lock()
	state.running = true
	state.title = title
	state.phase = "preparing"
	state.message = "Preparing..."
	state.err = ""
	state.total = 0
	state.completed = 0
	state.perFile = make(map[string]int64)
	state.cancelled = false
	state.speed = 0
	state.lastBytes = 0
	state.lastSample = time.Now()
	state.mu.Unlock()

	state.cancel = sync.Once{}
	state.done = make(chan struct{})
}

func endRun() {
	state.mu.Lock()
	state.running = false
	if state.cancelled {
		state.phase = "cancelled"
		state.message = "Cancelled"
	} else if state.err == "" {
		state.phase = "done"
		state.message = "Finished"
		if state.total > 0 {
			state.completed = state.total
		}
	} else {
		state.phase = "error"
		state.message = state.err
	}
	state.speed = 0
	state.mu.Unlock()
}

func setResult(err error) {
	if err == nil {
		return
	}
	state.mu.Lock()
	state.err = err.Error()
	state.mu.Unlock()
}

func setPhase(phase, message string) {
	state.mu.Lock()
	state.phase = phase
	state.message = message
	state.mu.Unlock()
}

func cancelled() bool {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.cancelled
}

// reporter adapts the core's ProgressReporter interface onto the polled state,
// keeping the same accounting the desktop UI uses: bytes are accumulated per
// file while it is in flight and folded into the completed total once the file
// is done.
type reporter struct{}

func (reporter) SetGameTitle(title string) {
	state.mu.Lock()
	state.title = title
	state.phase = "metadata"
	state.message = "Fetching title metadata..."
	state.mu.Unlock()
}

func (reporter) UpdateDownloadProgress(downloaded int64, filename string) {
	if downloaded == 0 {
		return
	}
	state.mu.Lock()
	if _, ok := state.perFile[filename]; ok {
		state.perFile[filename] += downloaded
		state.phase = "download"
		state.message = filename
	}
	state.mu.Unlock()
}

func (reporter) UpdateDecryptionProgress(progress float64) {
	state.mu.Lock()
	state.phase = "decrypt"
	state.message = fmt.Sprintf("Decrypting (%d%%)", int(progress*100))
	state.mu.Unlock()
}

func (reporter) Cancelled() bool {
	return cancelled()
}

func (reporter) SetCancelled() {
	Cancel()
}

func (reporter) Done() <-chan struct{} {
	return state.done
}

func (reporter) SetDownloadSize(size int64) {
	state.mu.Lock()
	state.total = size
	state.mu.Unlock()
}

func (reporter) ResetTotals() {
	state.mu.Lock()
	state.total = 0
	state.completed = 0
	state.perFile = make(map[string]int64)
	state.speed = 0
	state.lastBytes = 0
	state.lastSample = time.Now()
	state.mu.Unlock()
}

func (reporter) MarkFileAsDone(filename string) {
	state.mu.Lock()
	state.completed += state.perFile[filename]
	delete(state.perFile, filename)
	state.mu.Unlock()
}

func (reporter) SetTotalDownloadedForFile(filename string, downloaded int64) {
	state.mu.Lock()
	state.perFile[filename] = downloaded
	state.mu.Unlock()
}

func (reporter) SetStartTime(startTime time.Time) {
	state.mu.Lock()
	state.lastSample = time.Now()
	state.mu.Unlock()
}

func describeTitle(entry wiiu.TitleEntry) Title {
	version := entry.Version
	if version == 0 {
		// Database entries use 0 for "latest"; -1 keeps v0 selectable.
		version = wiiu.VersionLatest
	}
	return Title{
		TitleID:    fmt.Sprintf("%016x", entry.TitleID),
		TitleIDNum: entry.TitleID,
		Name:       entry.Name,
		Region:     int(entry.Region),
		RegionName: wiiu.GetFormattedRegion(entry.Region),
		Kind:       wiiu.GetFormattedKind(entry.TitleID),
		Category:   int(entry.Category),
		Version:    version,
	}
}

// rawTitle is the loose shape accepted by ImportTitleDatabaseJSON so users can
// bring title lists produced by other tools.
type rawTitle struct {
	Name      string          `json:"name"`
	TitleID   string          `json:"titleId"`
	TitleID2  string          `json:"title_id"`
	TitleID3  string          `json:"tid"`
	TitleID4  string          `json:"id"`
	Region    json.RawMessage `json:"region"`
	Category  json.RawMessage `json:"category"`
	Kind      string          `json:"kind"`
	Version   *int            `json:"version"`
	KeyType   *int            `json:"key"`
	TitleType json.RawMessage `json:"title_type"`
}

// eshopRegion maps a nus-info top-level region key onto the core's region mask.
func eshopRegion(code string) uint8 {
	switch strings.ToUpper(strings.TrimSpace(code)) {
	case "JP":
		return wiiu.MCP_REGION_JAPAN
	case "US", "CA", "MX", "BR":
		return wiiu.MCP_REGION_USA
	case "GB", "FR", "DE", "ES", "IT", "NL", "PT", "RU", "AU", "BE", "AT", "CH", "IE", "SE", "NO", "DK", "FI", "PL", "CZ", "GR", "HU", "RO":
		return wiiu.MCP_REGION_EUROPE
	default:
		return 0
	}
}

// eshopCategory classifies a catalogue entry from its title ID, since the eShop
// listing only knows about games and demos.
func eshopCategory(tid uint64) uint8 {
	if uint32(tid>>32) == wiiu.TID_HIGH_DEMO {
		return wiiu.TITLE_CATEGORY_DEMO
	}
	return wiiu.TITLE_CATEGORY_GAME
}

func parseEshopCatalog(data string) ([]wiiu.TitleEntry, error) {
	decoder := json.NewDecoder(strings.NewReader(data))

	opening, err := decoder.Token()
	if err != nil {
		return nil, fmt.Errorf("title list is not valid JSON: %w", err)
	}
	if delim, ok := opening.(json.Delim); !ok || delim != '{' {
		return nil, fmt.Errorf("title list must be an object keyed by region")
	}

	entries := make([]wiiu.TitleEntry, 0, 4096)
	index := make(map[uint64]int, 4096)

	for decoder.More() {
		regionToken, err := decoder.Token()
		if err != nil {
			return nil, fmt.Errorf("title list is truncated: %w", err)
		}
		regionCode, _ := regionToken.(string)
		region := eshopRegion(regionCode)

		opening, err := decoder.Token()
		if err != nil {
			return nil, fmt.Errorf("title list is truncated: %w", err)
		}
		if delim, ok := opening.(json.Delim); !ok || delim != '{' {
			return nil, fmt.Errorf("region %q must hold an object of titles", regionCode)
		}

		for decoder.More() {
			if _, err := decoder.Token(); err != nil {
				return nil, fmt.Errorf("title list is truncated: %w", err)
			}
			var leaf struct {
				TitleID string `json:"title_id"`
				Name    string `json:"name"`
			}
			if err := decoder.Decode(&leaf); err != nil {
				return nil, fmt.Errorf("title entry in %q is invalid: %w", regionCode, err)
			}
			tid, err := parseTitleID(leaf.TitleID)
			if err != nil || strings.TrimSpace(leaf.Name) == "" {
				continue
			}
			if position, ok := index[tid]; ok {
				// The same title can be listed for several regions; fold them
				// into one entry so the list shows each game once.
				entries[position].Region |= region
				continue
			}
			index[tid] = len(entries)
			entries = append(entries, wiiu.TitleEntry{
				Name:     strings.TrimSpace(leaf.Name),
				TitleID:  tid,
				Region:   region,
				Category: eshopCategory(tid),
				Version:  wiiu.VersionLatest,
			})
		}

		if _, err := decoder.Token(); err != nil {
			return nil, fmt.Errorf("title list is truncated: %w", err)
		}
	}

	if len(entries) == 0 {
		return nil, fmt.Errorf("title list has no titles")
	}

	sort.Slice(entries, func(i, j int) bool {
		return strings.ToLower(entries[i].Name) < strings.ToLower(entries[j].Name)
	})
	return entries, nil
}

func parseTitleDatabase(data string) ([]wiiu.TitleEntry, error) {
	var raw []rawTitle
	if err := json.Unmarshal([]byte(data), &raw); err != nil {
		return nil, fmt.Errorf("title database must be a JSON array: %w", err)
	}
	if len(raw) == 0 {
		return nil, fmt.Errorf("title database is empty")
	}

	entries := make([]wiiu.TitleEntry, 0, len(raw))
	for _, item := range raw {
		idText := firstNonEmpty(item.TitleID, item.TitleID2, item.TitleID3, item.TitleID4)
		tid, err := parseTitleID(idText)
		if err != nil {
			continue
		}
		version := wiiu.VersionLatest
		if item.Version != nil {
			version = *item.Version
		}
		key := 0
		if item.KeyType != nil {
			key = *item.KeyType
		}
		entries = append(entries, wiiu.TitleEntry{
			Name:     item.Name,
			TitleID:  tid,
			Region:   uint8(parseRegion(item.Region)),
			Key:      uint8(key),
			Category: uint8(parseCategory(item.Category, item.Kind, item.TitleType, tid)),
			Version:  version,
		})
	}
	if len(entries) == 0 {
		return nil, fmt.Errorf("title database has no valid title IDs")
	}
	return entries, nil
}

func parseRegion(raw json.RawMessage) int {
	if len(raw) == 0 {
		return wiiu.MCP_REGION_USA | wiiu.MCP_REGION_EUROPE | wiiu.MCP_REGION_JAPAN
	}
	var mask int
	if err := json.Unmarshal(raw, &mask); err == nil {
		return mask
	}
	var text string
	if err := json.Unmarshal(raw, &text); err != nil {
		return 0
	}
	total := 0
	for _, part := range strings.FieldsFunc(strings.ToLower(text), func(r rune) bool {
		return r == ',' || r == '/' || r == '|' || r == ' '
	}) {
		switch part {
		case "usa", "us", "ntsc-u":
			total |= wiiu.MCP_REGION_USA
		case "eur", "europe", "eu", "pal":
			total |= wiiu.MCP_REGION_EUROPE
		case "jpn", "japan", "jp":
			total |= wiiu.MCP_REGION_JAPAN
		case "all":
			total |= wiiu.MCP_REGION_USA | wiiu.MCP_REGION_EUROPE | wiiu.MCP_REGION_JAPAN
		}
	}
	return total
}

func parseCategory(raw json.RawMessage, kind string, titleType json.RawMessage, tid uint64) int {
	if len(raw) != 0 {
		var value int
		if err := json.Unmarshal(raw, &value); err == nil && value >= 0 && value <= wiiu.TITLE_CATEGORY_ALL {
			return value
		}
		var text string
		if err := json.Unmarshal(raw, &text); err == nil {
			if category, ok := categoryForWord(text); ok {
				return category
			}
		}
	}
	if len(titleType) != 0 {
		var text string
		if err := json.Unmarshal(titleType, &text); err == nil {
			if category, ok := categoryForWord(text); ok {
				return category
			}
		}
	}
	if kind != "" {
		if category, ok := categoryForWord(kind); ok {
			return category
		}
	}
	if category, ok := categoryForWord(wiiu.GetFormattedKind(tid)); ok {
		return category
	}
	return wiiu.TITLE_CATEGORY_GAME
}

func categoryForWord(word string) (int, bool) {
	switch strings.ToLower(strings.TrimSpace(word)) {
	case "game", "games", "base":
		return wiiu.TITLE_CATEGORY_GAME, true
	case "update", "updates", "patch":
		return wiiu.TITLE_CATEGORY_UPDATE, true
	case "dlc", "addon", "add-on":
		return wiiu.TITLE_CATEGORY_DLC, true
	case "demo", "demos":
		return wiiu.TITLE_CATEGORY_DEMO, true
	case "all":
		return wiiu.TITLE_CATEGORY_ALL, true
	}
	return 0, false
}

func parseTitleID(text string) (uint64, error) {
	cleaned := strings.Map(func(r rune) rune {
		switch r {
		case '-', '_', ' ', ':', '.':
			return -1
		default:
			return r
		}
	}, strings.TrimSpace(text))
	if cleaned == "" {
		return 0, fmt.Errorf("empty title ID")
	}
	if len(cleaned) > 16 {
		return 0, fmt.Errorf("title ID %q is too long", text)
	}
	if len(cleaned) < 16 {
		cleaned = strings.Repeat("0", 16-len(cleaned)) + cleaned
	}
	value, err := strconv.ParseUint(cleaned, 16, 64)
	if err != nil {
		return 0, fmt.Errorf("invalid title ID %q: %w", text, err)
	}
	return value, nil
}

func titleMatches(queryLower, normalizedQuery string, title Title) bool {
	if queryLower == "" {
		return true
	}
	if strings.Contains(strings.ToLower(title.TitleID), queryLower) {
		return true
	}
	if normalizedQuery == "" {
		return false
	}
	titleTokens := strings.Fields(normalizeSearchText(title.Name))
	for _, token := range strings.Fields(normalizedQuery) {
		matched := false
		for _, titleToken := range titleTokens {
			if strings.Contains(titleToken, token) {
				matched = true
				break
			}
		}
		if !matched {
			return false
		}
	}
	return true
}

func normalizeSearchText(text string) string {
	var builder strings.Builder
	builder.Grow(len(text))
	lastWasSpace := true
	for _, r := range strings.ToLower(text) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			builder.WriteRune(r)
			lastWasSpace = false
			continue
		}
		if !lastWasSpace {
			builder.WriteByte(' ')
			lastWasSpace = true
		}
	}
	return strings.TrimSpace(builder.String())
}

func sanitizeFilename(name string) string {
	replacer := strings.NewReplacer(
		"/", "_", "\\", "_", ":", "_", "*", "_", "?", "_",
		"\"", "_", "<", "_", ">", "_", "|", "_",
	)
	cleaned := strings.TrimSpace(replacer.Replace(name))
	if cleaned == "" {
		return "title"
	}
	if len(cleaned) > 96 {
		cleaned = cleaned[:96]
	}
	return cleaned
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

func mustJSON(value any) string {
	data, err := json.Marshal(value)
	if err != nil {
		return "[]"
	}
	return string(data)
}
