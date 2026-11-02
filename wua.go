package wiiudownloader

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// A .wua is a ZArchive that holds one or more titles, each in a folder named
// after its 16-digit title ID plus "_v" and the decimal title version (for
// example 0005000010143500_v16). Cemu looks titles up by that folder name, so
// packing a decrypted title folder means copying everything except the raw WUP
// files (title.tmd/title.tik/title.cert and the encrypted .app/.h3 payloads)
// under that name.
const wuaExtension = ".wua"

// rawWUPFiles are the files a decrypted title folder keeps only until its
// contents have been unpacked; they are what a .wua replaces.
var rawWUPFiles = map[string]bool{
	"title.tmd":  true,
	"title.tik":  true,
	"title.cert": true,
}

var rawWUPExtensions = []string{".app", ".h3", ".h2", ".part", ".resume.json"}

// WUAFolderName returns the folder name Cemu expects for a title inside a .wua.
func WUAFolderName(titleID uint64, titleVersion uint16) string {
	return fmt.Sprintf("%016X_v%d", titleID, titleVersion)
}

// TitleFolderName reads title.tmd from folder and builds the .wua folder name
// for that title.
func TitleFolderName(folder string) (string, error) {
	data, err := os.ReadFile(filepath.Join(folder, "title.tmd"))
	if err != nil {
		return "", err
	}
	tmd, err := ParseTMD(data)
	if err != nil {
		return "", err
	}
	return WUAFolderName(tmd.TitleID, tmd.TitleVersion), nil
}

// PackWUA packs a decrypted title folder into a .wua archive at wuaPath. Only
// the extracted title data goes in: the encrypted payloads, the ticket and the
// tmd stay behind in the folder and are removed by the caller once the archive
// is written.
func PackWUA(titleFolder, wuaPath string, progress func(done, total int64), abort func() bool) error {
	folderName, err := TitleFolderName(titleFolder)
	if err != nil {
		return fmt.Errorf("cannot read title metadata: %w", err)
	}

	entries, err := wuaEntries(titleFolder, wuaPath, folderName)
	if err != nil {
		return err
	}
	if len(entries) == 0 {
		return errors.New("nothing to pack: the folder holds no decrypted title data")
	}

	return WriteZArchive(wuaPath, entries, progress, abort)
}

// wuaEntries lists the files that make up the archive contents.
func wuaEntries(titleFolder, wuaPath, folderName string) ([]ZArchiveEntry, error) {
	archivePath, err := filepath.Abs(wuaPath)
	if err != nil {
		return nil, err
	}

	entries := make([]ZArchiveEntry, 0, 256)

	walkErr := filepath.WalkDir(titleFolder, func(path string, dir fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if dir.IsDir() {
			return nil
		}
		if !dir.Type().IsRegular() {
			// Symlinks or device nodes have no business in a title archive.
			return nil
		}

		absolute, err := filepath.Abs(path)
		if err != nil {
			return err
		}
		if absolute == archivePath {
			return nil
		}

		relative, err := filepath.Rel(titleFolder, path)
		if err != nil {
			return err
		}
		relative = filepath.ToSlash(relative)

		// Raw WUP files only ever sit at the top of the folder; the extracted
		// tree below them is the title itself.
		if !strings.Contains(relative, "/") && isRawWUPFile(relative) {
			return nil
		}

		entries = append(entries, ZArchiveEntry{
			Path:   folderName + "/" + relative,
			Source: path,
		})
		return nil
	})
	if walkErr != nil {
		return nil, walkErr
	}
	return entries, nil
}

func isRawWUPFile(name string) bool {
	lower := strings.ToLower(name)
	if rawWUPFiles[lower] {
		return true
	}
	for _, extension := range rawWUPExtensions {
		if strings.HasSuffix(lower, extension) {
			return true
		}
	}
	return false
}
