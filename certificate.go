package wiiudownloader

import (
	"fmt"
	"net/http"
	"os"
	"path"
	"sync"
)

var (
	cetkMu   sync.Mutex
	cetkData []byte
)

// TempDir overrides the directory used for short-lived downloads such as the
// OSv10 ticket. Empty keeps os.TempDir(). Android apps have to set it: Go's
// default there is /data/local/tmp, which apps cannot write to.
var TempDir string

func temporaryDir() string {
	if TempDir != "" {
		return TempDir
	}
	return os.TempDir()
}

const (
	// Embedded certificate chain starts at this range inside the downloaded cetk.
	CETK_CERT_START_OFFSET = 0x350
	CETK_CERT_SIZE         = 0x300
)

func getDefaultCert(progressReporter ProgressReporter, client *http.Client) ([]byte, error) {
	cetkMu.Lock()
	defer cetkMu.Unlock()
	if hasCetkCertData(cetkData) {
		return cetkData[CETK_CERT_START_OFFSET : CETK_CERT_START_OFFSET+CETK_CERT_SIZE], nil
	}
	cetkDir := path.Join(temporaryDir(), "cetk")
	if err := downloadFile(progressReporter, client, "http://ccs.cdn.c.shop.nintendowifi.net/ccs/download/000500101000400a/cetk", cetkDir, true); err != nil {
		return nil, err
	}
	data, err := os.ReadFile(cetkDir)
	if err != nil {
		return nil, err
	}

	if err := os.Remove(cetkDir); err != nil {
		return nil, err
	}
	// Drop the resume journal the temporary download leaves behind.
	_ = cleanupPartialDownload(cetkDir)

	if hasCetkCertData(data) {
		cetkData = data
		return cetkData[CETK_CERT_START_OFFSET : CETK_CERT_START_OFFSET+CETK_CERT_SIZE], nil
	}
	return nil, fmt.Errorf("failed to download OSv10 cetk, length: %d", len(data))
}

func GenerateCert(tmd *TMD, outputPath string, progressReporter ProgressReporter, client *http.Client) error {
	cert, err := os.Create(outputPath)
	if err != nil {
		return err
	}
	defer cert.Close()

	if _, err := cert.Write(tmd.Certificate1); err != nil {
		return err
	}

	if _, err := cert.Write(tmd.Certificate2); err != nil {
		return err
	}

	defaultCert, err := getDefaultCert(progressReporter, client)
	if err != nil {
		return err
	}

	if _, err := cert.Write(defaultCert); err != nil {
		return err
	}
	return nil
}

func hasCetkCertData(data []byte) bool {
	return len(data) >= CETK_CERT_START_OFFSET+CETK_CERT_SIZE
}
