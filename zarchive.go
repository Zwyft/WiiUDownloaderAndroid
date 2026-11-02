package wiiudownloader

import (
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"hash"
	"io"
	"os"
	"sort"
	"strings"

	"github.com/klauspost/compress/zstd"
)

// ZArchive is the container Cemu uses for .wua files: a stream of independent
// 64 KiB zstd blocks (stored raw when compression does not help), followed by a
// block-offset table, a name table, the file tree, the (unused) metadata
// sections and a footer that carries a SHA-256 over everything before it.
//
// The layout is fixed by Exzap's ZArchive reference implementation, so the
// constants and serialization below follow that format field for field:
//
//	data:          N * zarchiveBlockSize of uncompressed bytes, each block
//	               compressed on its own (a block that does not shrink is
//	               stored as the raw 64 KiB)
//	offsetRecords: per zarchiveEntriesPerRecord blocks: a 64-bit base offset
//	               plus 16 stored sizes (size-1, so a raw block is 0xFFFF)
//	names:         length-prefixed node names (MSB of the first byte selects a
//	               second prefix byte for 128+ byte names)
//	fileTree:      breadth-first entries, children sorted case-insensitively
//	metadata:      empty in archives this package writes
//	footer:        section table, SHA-256, total size, version and magic
const (
	zarchiveBlockSize        = 64 * 1024
	zarchiveEntriesPerRecord = 16
	zarchiveFooterSize       = 16*6 + 32 + 8 + 4 + 4
	zarchiveMagic            = 0x169f52d6
	zarchiveVersion          = 0x61bf3a01
	zarchiveRootNameOffset   = 0x7FFFFFFF
	zarchiveFileTypeFlag     = 0x80000000
)

// ZArchiveEntry is one file to store: the path it should have inside the
// archive and the host file to read it from.
type ZArchiveEntry struct {
	Path   string
	Source string
}

type zarchiveNode struct {
	name     string
	isFile   bool
	size     int64
	offset   int64
	children []*zarchiveNode
	start    uint32 // index of the first child in the serialized tree
}

type zarchiveRecord struct {
	base  uint64
	sizes [zarchiveEntriesPerRecord]uint16
}

// ErrZArchiveAborted reports that abort() asked the writer to stop; the partial
// archive has already been removed by then.
var ErrZArchiveAborted = errors.New("zarchive: packing cancelled")

// WriteZArchive packs entries into a ZArchive file at dstPath. progress, when
// set, receives the number of file bytes written and the total to write, and
// abort is polled between blocks so a long pack can be cancelled.
func WriteZArchive(dstPath string, entries []ZArchiveEntry, progress func(done, total int64), abort func() bool) error {
	if len(entries) == 0 {
		return fmt.Errorf("zarchive: nothing to pack")
	}

	root := &zarchiveNode{}
	fileNodes := make([]*zarchiveNode, len(entries))
	var total int64
	for i, entry := range entries {
		clean := cleanArchivePath(entry.Path)
		if clean == "" {
			return fmt.Errorf("zarchive: empty path for %q", entry.Source)
		}
		info, err := os.Stat(entry.Source)
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() {
			return fmt.Errorf("zarchive: %q is not a regular file", entry.Source)
		}
		node, err := addFileNode(root, clean)
		if err != nil {
			return err
		}
		node.size = info.Size()
		fileNodes[i] = node
		total += info.Size()
	}

	out, err := os.Create(dstPath)
	if err != nil {
		return err
	}

	writer := &zarchiveWriter{file: out, digest: sha256.New(), block: make([]byte, 0, zarchiveBlockSize), abort: abort}
	encoder, err := zstd.NewWriter(nil, zstd.WithEncoderLevel(zstd.SpeedDefault))
	if err != nil {
		out.Close()
		return err
	}
	defer encoder.Close()
	writer.encoder = encoder

	packErr := writer.pack(entries, root, fileNodes, total, progress)
	closeErr := out.Close()
	if packErr != nil {
		// A failed or cancelled pack must not leave a broken archive behind.
		os.Remove(dstPath)
		return packErr
	}
	if closeErr != nil {
		return closeErr
	}
	if err := os.Chmod(dstPath, 0o644); err != nil {
		return err
	}
	return nil
}

type zarchiveWriter struct {
	file       *os.File
	abort      func() bool
	digest     hash.Hash
	written    int64 // compressed bytes written so far
	stream     int64 // uncompressed bytes appended so far
	block      []byte
	records    []zarchiveRecord
	blocks     int
	encoder    *zstd.Encoder
	scratch    []byte
	buffer     []byte
	chunkBytes int64
}

func (w *zarchiveWriter) pack(entries []ZArchiveEntry, root *zarchiveNode, nodes []*zarchiveNode, total int64, progress func(done, total int64)) error {
	w.buffer = make([]byte, zarchiveBlockSize)
	if progress != nil {
		progress(0, total)
	}

	for i, entry := range entries {
		node := nodes[i]
		node.offset = w.stream

		source, err := os.Open(entry.Source)
		if err != nil {
			return err
		}
		appended, copyErr := w.appendFile(source)
		closeErr := source.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
		if appended != node.size {
			return fmt.Errorf("zarchive: %q changed size while packing", entry.Source)
		}
		if progress != nil {
			progress(w.stream, total)
		}
	}

	// The data section is always a whole number of blocks; the tail is padded
	// with zeros, which is what the reference writer does in Finalize().
	if len(w.block) > 0 {
		pad := make([]byte, zarchiveBlockSize-len(w.block))
		w.block = append(w.block, pad...)
		if err := w.storeBlock(w.block); err != nil {
			return err
		}
		w.block = w.block[:0]
	}

	dataSize := w.written

	offsetRecordsOffset := w.written
	if err := w.writeOffsetRecords(); err != nil {
		return err
	}
	offsetRecordsSize := w.written - offsetRecordsOffset

	namesOffset := w.written
	nameOffsets, err := w.writeNameTable(root)
	if err != nil {
		return err
	}
	namesSize := w.written - namesOffset

	treeOffset := w.written
	if err := w.writeFileTree(root, nameOffsets); err != nil {
		return err
	}
	treeSize := w.written - treeOffset

	metaOffset := w.written // both metadata sections are empty; their offset is the footer's start
	return w.writeFooter(zarchiveFooter{
		data:         zarchiveSpan{0, uint64(dataSize)},
		offsetRecord: zarchiveSpan{uint64(offsetRecordsOffset), uint64(offsetRecordsSize)},
		names:        zarchiveSpan{uint64(namesOffset), uint64(namesSize)},
		tree:         zarchiveSpan{uint64(treeOffset), uint64(treeSize)},
		metaDir:      zarchiveSpan{uint64(metaOffset), 0},
		metaData:     zarchiveSpan{uint64(metaOffset), 0},
	})
}

func (w *zarchiveWriter) appendFile(source *os.File) (int64, error) {
	var appended int64
	for {
		if w.abort != nil && w.abort() {
			return appended, ErrZArchiveAborted
		}
		space := zarchiveBlockSize - len(w.block)
		if space == 0 {
			if err := w.storeBlock(w.block); err != nil {
				return appended, err
			}
			w.block = w.block[:0]
			continue
		}
		n, err := source.Read(w.buffer[:space])
		if n > 0 {
			w.block = append(w.block, w.buffer[:n]...)
			w.stream += int64(n)
			appended += int64(n)
		}
		if err == io.EOF {
			return appended, nil
		}
		if err != nil {
			return appended, err
		}
	}
}

func (w *zarchiveWriter) storeBlock(data []byte) error {
	if len(data) != zarchiveBlockSize {
		return fmt.Errorf("zarchive: block is %d bytes, want %d", len(data), zarchiveBlockSize)
	}

	w.scratch = w.encoder.EncodeAll(data, w.scratch[:0])
	payload := w.scratch
	if len(w.scratch) >= zarchiveBlockSize {
		// Incompressible: the format stores the block verbatim, which the reader
		// recognises by its size being exactly one block.
		payload = data
	}

	recordIndex := w.blocks % zarchiveEntriesPerRecord
	if recordIndex == 0 {
		w.records = append(w.records, zarchiveRecord{base: uint64(w.written)})
	}
	w.records[len(w.records)-1].sizes[recordIndex] = uint16(len(payload) - 1)

	if err := w.write(payload); err != nil {
		return err
	}
	w.blocks++
	return nil
}

// write appends bytes to the archive and folds them into the integrity hash.
func (w *zarchiveWriter) write(data []byte) error {
	if len(data) == 0 {
		return nil
	}
	if _, err := w.file.Write(data); err != nil {
		return err
	}
	w.digest.Write(data)
	w.written += int64(len(data))
	return nil
}

func (w *zarchiveWriter) writeUint32(value uint32) error {
	var buf [4]byte
	binary.BigEndian.PutUint32(buf[:], value)
	return w.write(buf[:])
}

func (w *zarchiveWriter) writeUint64(value uint64) error {
	var buf [8]byte
	binary.BigEndian.PutUint64(buf[:], value)
	return w.write(buf[:])
}

func (w *zarchiveWriter) writeOffsetRecords() error {
	for _, record := range w.records {
		if err := w.writeUint64(record.base); err != nil {
			return err
		}
		for _, size := range record.sizes {
			var buf [2]byte
			binary.BigEndian.PutUint16(buf[:], size)
			if err := w.write(buf[:]); err != nil {
				return err
			}
		}
	}
	return nil
}

// writeNameTable stores every node name once and reports the offset of each.
func (w *zarchiveWriter) writeNameTable(root *zarchiveNode) (map[string]uint32, error) {
	names := make([]string, 0, 16)
	seen := make(map[string]bool, 16)
	var collect func(node *zarchiveNode)
	collect = func(node *zarchiveNode) {
		if node.name != "" && !seen[node.name] {
			seen[node.name] = true
			names = append(names, node.name)
		}
		for _, child := range node.children {
			collect(child)
		}
	}
	collect(root)

	offsets := make(map[string]uint32, len(names))
	var offset uint32
	for _, name := range names {
		offsets[name] = offset
		if len(name) >= 0x80 {
			header := [2]byte{byte(len(name)&0x7F) | 0x80, byte(len(name) >> 7)}
			if err := w.write(header[:]); err != nil {
				return nil, err
			}
			offset += 2
		} else {
			header := [1]byte{byte(len(name))}
			if err := w.write(header[:]); err != nil {
				return nil, err
			}
			offset++
		}
		if err := w.write([]byte(name)); err != nil {
			return nil, err
		}
		offset += uint32(len(name))
	}
	return offsets, nil
}

// writeFileTree serializes the tree breadth-first; every directory records
// where its children start, so children of one directory stay contiguous.
func (w *zarchiveWriter) writeFileTree(root *zarchiveNode, nameOffsets map[string]uint32) error {
	order := make([]*zarchiveNode, 0, 64)
	queue := []*zarchiveNode{root}
	next := uint32(1) // the root occupies index 0
	for len(queue) > 0 {
		node := queue[0]
		queue = queue[1:]
		order = append(order, node)
		if node.isFile {
			continue
		}
		sort.SliceStable(node.children, func(a, b int) bool {
			return zarchiveCompareName(node.children[a].name, node.children[b].name) < 0
		})
		node.start = next
		next += uint32(len(node.children))
		queue = append(queue, node.children...)
	}

	for _, node := range order {
		nameOffset := zarchiveRootNameOffset
		if node != root {
			offset, ok := nameOffsets[node.name]
			if !ok {
				return fmt.Errorf("zarchive: no name table entry for %q", node.name)
			}
			nameOffset = int(offset)
		}

		header := uint32(nameOffset)
		if node.isFile {
			header |= zarchiveFileTypeFlag
		}
		if err := w.writeUint32(header); err != nil {
			return err
		}

		if node.isFile {
			offset := uint64(node.offset)
			size := uint64(node.size)
			if offset > 0xFFFFFFFFFFFF || size > 0xFFFFFFFFFFFF {
				return fmt.Errorf("zarchive: %q is too large for the format", node.name)
			}
			if err := w.writeUint32(uint32(offset & 0xFFFFFFFF)); err != nil {
				return err
			}
			if err := w.writeUint32(uint32(size & 0xFFFFFFFF)); err != nil {
				return err
			}
			high := uint32((offset>>32)&0xFFFF) | uint32((size>>16)&0xFFFF0000)
			if err := w.writeUint32(high); err != nil {
				return err
			}
		} else {
			if err := w.writeUint32(node.start); err != nil {
				return err
			}
			if err := w.writeUint32(uint32(len(node.children))); err != nil {
				return err
			}
			if err := w.writeUint32(0); err != nil {
				return err
			}
		}
	}
	return nil
}

type zarchiveSpan struct {
	offset uint64
	size   uint64
}

type zarchiveFooter struct {
	data         zarchiveSpan
	offsetRecord zarchiveSpan
	names        zarchiveSpan
	tree         zarchiveSpan
	metaDir      zarchiveSpan
	metaData     zarchiveSpan
}

func (w *zarchiveWriter) writeFooter(footer zarchiveFooter) error {
	totalSize := uint64(w.written + zarchiveFooterSize)
	serialize := func(integrity []byte) []byte {
		buf := make([]byte, 0, zarchiveFooterSize)
		spans := []zarchiveSpan{
			footer.data, footer.offsetRecord, footer.names,
			footer.tree, footer.metaDir, footer.metaData,
		}
		for _, span := range spans {
			buf = binary.BigEndian.AppendUint64(buf, span.offset)
			buf = binary.BigEndian.AppendUint64(buf, span.size)
		}
		buf = append(buf, integrity...)
		buf = binary.BigEndian.AppendUint64(buf, totalSize)
		buf = binary.BigEndian.AppendUint32(buf, zarchiveVersion)
		buf = binary.BigEndian.AppendUint32(buf, zarchiveMagic)
		return buf
	}

	// The hash covers everything up to the footer plus the footer itself with
	// the hash field zeroed.
	w.digest.Write(serialize(make([]byte, 32)))
	integrity := w.digest.Sum(nil)
	if _, err := w.file.Write(serialize(integrity)); err != nil {
		return err
	}
	w.written += zarchiveFooterSize
	return nil
}

func cleanArchivePath(path string) string {
	cleaned := strings.ReplaceAll(path, "\\", "/")
	for strings.HasPrefix(cleaned, "/") {
		cleaned = cleaned[1:]
	}
	for strings.HasSuffix(cleaned, "/") {
		cleaned = cleaned[:len(cleaned)-1]
	}
	return cleaned
}

// addFileNode inserts a file at path below root, creating parent directories.
func addFileNode(root *zarchiveNode, path string) (*zarchiveNode, error) {
	parts := strings.Split(path, "/")
	node := root
	for i, part := range parts {
		if part == "" || part == "." || part == ".." {
			return nil, fmt.Errorf("zarchive: invalid path %q", path)
		}
		isFile := i == len(parts)-1
		var child *zarchiveNode
		for _, existing := range node.children {
			if existing.name == part {
				child = existing
				break
			}
		}
		if child == nil {
			child = &zarchiveNode{name: part, isFile: isFile}
			node.children = append(node.children, child)
		} else if child.isFile != isFile {
			return nil, fmt.Errorf("zarchive: %q collides with an existing entry", path)
		}
		if isFile {
			return child, nil
		}
		node = child
	}
	return nil, fmt.Errorf("zarchive: invalid path %q", path)
}

// zarchiveCompareName orders node names like the reference implementation:
// case-insensitive (ASCII only), shorter names first when one is a prefix.
func zarchiveCompareName(a, b string) int {
	for i := 0; i < len(a) && i < len(b); i++ {
		ca, cb := lowerASCII(a[i]), lowerASCII(b[i])
		if ca != cb {
			return int(ca) - int(cb)
		}
	}
	switch {
	case len(a) < len(b):
		return -1
	case len(a) > len(b):
		return 1
	}
	return 0
}

func lowerASCII(c byte) byte {
	if c >= 'A' && c <= 'Z' {
		return c + ('a' - 'A')
	}
	return c
}
