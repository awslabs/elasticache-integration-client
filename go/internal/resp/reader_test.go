// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package resp

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"runtime"
	"strings"
	"testing"
)

const (
	testReadSize = 64 << 10
	testMaxFrame = 16 << 20
)

// chunkReader returns data in reads of at most chunk bytes, then err (io.EOF if nil).
type chunkReader struct {
	data  []byte
	chunk int
	err   error
	reads int
}

func (c *chunkReader) Read(p []byte) (int, error) {
	c.reads++
	if len(c.data) == 0 {
		if c.err != nil {
			return 0, c.err
		}
		return 0, io.EOF
	}
	n := min(len(p), c.chunk, len(c.data))
	copy(p, c.data[:n])
	c.data = c.data[n:]
	return n, nil
}

func newChunked(data string, chunk int) *Reader {
	return NewReader(&chunkReader{data: []byte(data), chunk: chunk}, testReadSize, testMaxFrame)
}

func readAll(t testing.TB, r *Reader) []Value {
	t.Helper()
	var out []Value
	for {
		v, err := r.Next()
		if errors.Is(err, io.EOF) {
			return out
		}
		if err != nil {
			t.Fatalf("Next after %d values: %v", len(out), err)
		}
		out = append(out, v)
	}
}

func TestReaderPipelinedValuesInEveryChunking(t *testing.T) {
	var wire strings.Builder
	var want []Value
	for _, vec := range loadVectors(t).Decode {
		wire.WriteString(vec.Reply)
		want = append(want, mustDecode(t, vec.Reply))
	}
	wire.WriteString(response(70 << 10)) // over the read buffer, between small frames
	want = append(want, mustDecode(t, response(70<<10)))
	wire.WriteString("+OK\r\n")
	want = append(want, mustDecode(t, "+OK\r\n"))
	for _, chunk := range []int{1, 2, 3, 7, 100, 16 << 10, 1 << 30} {
		got := readAll(t, newChunked(wire.String(), chunk))
		if len(got) != len(want) {
			t.Fatalf("chunk %d: %d values, want %d", chunk, len(got), len(want))
		}
		for i := range want {
			if !equal(got[i], want[i]) {
				t.Fatalf("chunk %d: value %d differs", chunk, i)
			}
		}
	}
}

// TestAlignmentRESPReader reads every shared reply and frame one byte at a time.
func TestAlignmentRESPReader(t *testing.T) {
	vec := loadVectors(t)
	for _, d := range vec.Decode {
		if got := readAll(t, newChunked(d.Reply, 1)); len(got) != 1 || !equal(got[0], mustDecode(t, d.Reply)) {
			t.Fatalf("%s: %v", d.Name, got)
		}
	}
	for _, e := range vec.Encode {
		if got := readAll(t, newChunked(e.Frame, 1)); len(got) != 1 || !equal(got[0], mustDecode(t, e.Frame)) {
			t.Fatalf("%s: %v", e.Name, got)
		}
	}
}

func TestReaderLargeReplyScansFewTimes(t *testing.T) {
	wire := response(390 << 10)
	src := &chunkReader{data: []byte(wire), chunk: 16 << 10} // like TLS records
	r := NewReader(src, testReadSize, testMaxFrame)
	v, err := r.Next()
	if err != nil || !equal(v, mustDecode(t, wire)) {
		t.Fatalf("Next: %v", err)
	}
	if r.scans > 3 {
		t.Fatalf("%d scans for one 390 KiB reply, want at most 3", r.scans)
	}
	if body := v.Elems[2].Bytes; len(body) != 390<<10 || cap(body) != len(body) {
		t.Fatalf("body len %d cap %d", len(body), cap(body))
	}
	if _, err := r.Next(); err != io.EOF {
		t.Fatalf("after the reply: %v", err)
	}
}

// TestReaderValuesOwnTheirBytes checks that later reads never change a returned value.
func TestReaderValuesOwnTheirBytes(t *testing.T) {
	var wire strings.Builder
	var want []string
	for i := range 200 {
		size := 10 + i*37
		if i%50 == 49 {
			size = 100 << 10
		}
		body := strings.Repeat(string(rune('a'+i%26)), size)
		wire.WriteString(fmt.Sprintf("*3\r\n:200\r\n$0\r\n\r\n$%d\r\n%s\r\n", len(body), body))
		want = append(want, body)
	}
	for _, chunk := range []int{1000, 16 << 10, 1 << 30} {
		got := readAll(t, newChunked(wire.String(), chunk))
		for i, v := range got {
			if string(v.Elems[2].Bytes) != want[i] {
				t.Fatalf("chunk %d: body %d changed after later reads", chunk, i)
			}
		}
	}
}

func TestReaderBytesAfterALargeFrameGoBackToTheReadBuffer(t *testing.T) {
	// The first element is large and a sibling follows, so Need is not exact and the frame
	// buffer gets room for one more read, which takes in the frames after it.
	big := strings.Repeat("x", 100<<10)
	wire := fmt.Sprintf("*2\r\n$%d\r\n%s\r\n:1\r\n+OK\r\n:7\r\n", len(big), big)
	for _, chunk := range []int{1 << 30, 5000} {
		got := readAll(t, newChunked(wire, chunk))
		if len(got) != 3 || string(got[0].Elems[0].Bytes) != big || got[1].Kind != KindSimpleString || got[2].Int != 7 {
			t.Fatalf("chunk %d: %v", chunk, got)
		}
	}
}

func TestReaderFrameCap(t *testing.T) {
	cases := map[string]struct {
		wire               string
		readSize, maxFrame int
	}{
		"complete frame over the cap":  {"$200\r\n" + strings.Repeat("a", 200) + "\r\n", 1024, 100},
		"exact need over the cap":      {"$1000\r\n", 64, 100},
		"inexact need over the cap":    {"*2000000000\r\n", testReadSize, testMaxFrame},
		"map count over the cap":       {"%2147483647\r\n", testReadSize, testMaxFrame},
		"over the cap in a large read": {"*2\r\n$70000\r\n" + strings.Repeat("a", 70000) + "\r\n$70000\r\n", 64, 100000},
		"complete large frame":         {"*2\r\n$600\r\n" + strings.Repeat("a", 600) + "\r\n+OK\r\n", 64, 500},
		"elements over the cap":        {"*2000\r\n" + strings.Repeat("_\r\n", 2000), 1 << 20, 100000},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			src := &chunkReader{data: []byte(tc.wire), chunk: 1 << 30}
			r := NewReader(src, tc.readSize, tc.maxFrame)
			_, err := r.Next()
			if !errors.Is(err, ErrFrameTooLarge) || !errors.Is(err, ErrCorrupt) {
				t.Fatalf("Next = %v, want ErrFrameTooLarge", err)
			}
			if elems := strings.Contains(err.Error(), "elements"); elems != strings.HasPrefix(name, "elements") {
				t.Fatalf("Next = %v: the message names the wrong limit", err)
			}
			if _, again := r.Next(); again != err {
				t.Fatalf("error not sticky: %v", again)
			}
		})
	}
	// As many elements as the cap allows for the frame are accepted.
	nulls := 100000 / valueSize
	r0 := NewReader(strings.NewReader(fmt.Sprintf("*%d\r\n%s", nulls, strings.Repeat("_\r\n", nulls))), 1<<20, 100000)
	if v, err := r0.Next(); err != nil || len(v.Elems) != nulls {
		t.Fatalf("elements at the cap: %v", err)
	}
	// A frame of exactly the cap is accepted.
	wire := "$93\r\n" + strings.Repeat("a", 93) + "\r\n"
	r := NewReader(&chunkReader{data: []byte(wire), chunk: 7}, 16, len(wire))
	if v, err := r.Next(); err != nil || len(v.Bytes) != 93 {
		t.Fatalf("frame at the cap: %v", err)
	}
}

func TestReaderEndOfStream(t *testing.T) {
	cases := map[string]struct {
		wire string
		want error
	}{
		"empty":                  {"", io.EOF},
		"after a frame":          {"+OK\r\n", io.EOF},
		"inside a frame":         {"+OK\r\n$5\r\nhel", io.ErrUnexpectedEOF},
		"inside a large frame":   {"$100000\r\n" + strings.Repeat("a", 70000), io.ErrUnexpectedEOF},
		"corrupt before the end": {"+OK\r\n*2\r\n0", ErrCorrupt},
		"corrupt before the end of a large frame": {"*2\r\n$70000\r\n" + strings.Repeat("a", 70000) + "\r\nX", ErrCorrupt},
	}
	for name, tc := range cases {
		r := newChunked(tc.wire, 1000)
		var err error
		for err == nil {
			_, err = r.Next()
		}
		if !errors.Is(err, tc.want) {
			t.Errorf("%s: %v, want %v", name, err, tc.want)
		}
		if _, again := r.Next(); again != err {
			t.Errorf("%s: not sticky: %v", name, again)
		}
	}
}

// errAfterData returns its data and an error in the same read.
type errAfterData struct {
	data []byte
	err  error
}

func (e *errAfterData) Read(p []byte) (int, error) {
	n := copy(p, e.data)
	e.data = e.data[n:]
	return n, e.err
}

func TestReaderReturnsBufferedFramesBeforeAReadError(t *testing.T) {
	boom := errors.New("boom")
	r := NewReader(&errAfterData{data: []byte("+A\r\n+B\r\n+C"), err: boom}, testReadSize, testMaxFrame)
	for _, want := range []string{"A", "B"} {
		if v, err := r.Next(); err != nil || string(v.Bytes) != want {
			t.Fatalf("Next = %v, %v; want %s", v, err, want)
		}
	}
	if _, err := r.Next(); err != boom {
		t.Fatalf("Next = %v, want the read error", err)
	}
}

type stalled struct{ reads int }

func (s *stalled) Read([]byte) (int, error) { s.reads++; return 0, nil }

type lying struct{}

func (lying) Read(p []byte) (int, error) { return len(p) + 1, nil }

func TestReaderBadSources(t *testing.T) {
	s := &stalled{}
	if _, err := NewReader(s, 16, 100).Next(); err != io.ErrNoProgress || s.reads != maxEmptyReads {
		t.Fatalf("stalled: %v after %d reads", err, s.reads)
	}
	if _, err := NewReader(lying{}, 16, 100).Next(); err != errInvalidRead {
		t.Fatalf("lying: %v", err)
	}
	if _, err := NewReader(strings.NewReader("#t\r\n"), 16, 100).Next(); !errors.Is(err, ErrCorrupt) {
		t.Fatalf("corrupt: %v", err)
	}
}

func TestNewReaderRefusesBadSizes(t *testing.T) {
	for _, sizes := range [][2]int{{0, 1}, {1, 0}, {-1, 1}} {
		func() {
			defer func() {
				if recover() == nil {
					t.Errorf("NewReader(%d, %d) did not panic", sizes[0], sizes[1])
				}
			}()
			NewReader(strings.NewReader(""), sizes[0], sizes[1])
		}()
	}
}

// loop replays one frame forever.
type loop struct {
	frame []byte
	pos   int
	chunk int
}

func (l *loop) Read(p []byte) (int, error) {
	n := copy(p[:min(len(p), l.chunk)], l.frame[l.pos:])
	l.pos = (l.pos + n) % len(l.frame)
	return n, nil
}

func TestReaderAllocations(t *testing.T) {
	if raceEnabled {
		t.Skip("the race detector changes allocation counts")
	}
	for _, tc := range []struct{ size, chunk int }{{1 << 10, 1 << 30}, {390 << 10, 16 << 10}, {390 << 10, 1 << 30}} {
		frame := []byte(response(tc.size))
		r := NewReader(&loop{frame: frame, chunk: tc.chunk}, testReadSize, testMaxFrame)
		next := func() {
			if _, err := r.Next(); err != nil {
				t.Fatal(err)
			}
		}
		next()
		if n := testing.AllocsPerRun(50, next); n > 2 {
			t.Errorf("%d bytes in %d-byte reads: %v allocations per Next, want at most 2", tc.size, tc.chunk, n)
		}
		var before, after runtime.MemStats
		runtime.ReadMemStats(&before)
		const runs = 20
		for range runs {
			next()
		}
		runtime.ReadMemStats(&after)
		// The frame, rounded up to whole 8 KiB pages by the allocator, and its elements.
		if per := (after.TotalAlloc - before.TotalAlloc) / runs; per > uint64(len(frame))+8<<10+1024 {
			t.Errorf("%d bytes in %d-byte reads: %d bytes allocated per Next for a %d-byte frame", tc.size, tc.chunk, per, len(frame))
		}
	}
}

// FuzzReader checks that any chunking and read size give the values and error class that
// decoding the whole input one value at a time gives.
func FuzzReader(f *testing.F) {
	addSeeds(f)
	f.Fuzz(func(t *testing.T, data []byte) {
		if len(data) == 0 {
			return
		}
		chunk := int(data[0])%13 + 1
		readSize := int(data[len(data)-1])%61 + 4
		r := NewReader(&chunkReader{data: bytes.Clone(data), chunk: chunk}, readSize, 1<<20)
		for off := 0; ; {
			got, err := r.Next()
			want, n, derr := Decode(data[off:])
			switch {
			case off == len(data):
				if err != io.EOF {
					t.Fatalf("at the end: %v", err)
				}
				return
			case derr == nil:
				if err != nil || !equal(got, want) {
					t.Fatalf("at %d: reader %v %v, decode %v", off, got, err, want)
				}
				off += n
			case errors.Is(derr, ErrCorrupt):
				if !errors.Is(err, ErrCorrupt) {
					t.Fatalf("at %d: reader %v, decode %v", off, err, derr)
				}
				return
			default: // incomplete
				if err != io.ErrUnexpectedEOF && !errors.Is(err, ErrFrameTooLarge) {
					t.Fatalf("at %d: reader %v on an incomplete value", off, err)
				}
				return
			}
		}
	})
}

func BenchmarkReader(b *testing.B) {
	for _, tc := range []struct {
		name        string
		size, chunk int
	}{{"1KiB", 1 << 10, 1 << 30}, {"390KiB", 390 << 10, 1 << 30}, {"390KiB-16KiBReads", 390 << 10, 16 << 10}} {
		frame := []byte(response(tc.size))
		b.Run(tc.name, func(b *testing.B) {
			r := NewReader(&loop{frame: frame, chunk: tc.chunk}, testReadSize, testMaxFrame)
			b.ReportAllocs()
			b.SetBytes(int64(len(frame)))
			for b.Loop() {
				if _, err := r.Next(); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}

// TestReaderScansTheLastBytesAtTheEnd reports corrupt bytes that arrive after a Scan asked
// for more, just before the end of the stream, as corrupt rather than as truncated.
func TestReaderScansTheLastBytesAtTheEnd(t *testing.T) {
	big := strings.Repeat("a", 70000)
	for _, tc := range []struct {
		wire  string
		chunk int
	}{
		{"*2\r\n0", 4},
		{"*2\r\n$70000\r\n" + big + "\r\nX", 70000 + 13},
	} {
		if _, err := newChunked(tc.wire, tc.chunk).Next(); !errors.Is(err, ErrCorrupt) {
			t.Errorf("%d bytes in %d-byte reads: %v", len(tc.wire), tc.chunk, err)
		}
	}
}

// TestReaderGrowsLargeFramesGeometrically reads frames whose Need stays small while they
// arrive, so their buffer must grow, without copying them once per read.
func TestReaderGrowsLargeFramesGeometrically(t *testing.T) {
	if raceEnabled {
		t.Skip("the race detector changes allocation counts")
	}
	var many strings.Builder
	many.WriteString("*256\r\n")
	for range 256 {
		fmt.Fprintf(&many, "$16000\r\n%s\r\n", strings.Repeat("b", 16000))
	}
	for name, wire := range map[string]string{
		"long simple string": "+" + strings.Repeat("a", 4<<20) + "\r\n",
		"many bulk strings":  many.String(),
	} {
		for _, chunk := range []int{16 << 10, 1 << 30} {
			r := NewReader(&chunkReader{data: []byte(wire), chunk: chunk}, testReadSize, testMaxFrame)
			var before, after runtime.MemStats
			runtime.ReadMemStats(&before)
			v, err := r.Next()
			runtime.ReadMemStats(&after)
			if err != nil || !equal(v, mustDecode(t, wire)) {
				t.Fatalf("%s in %d-byte reads: %v", name, chunk, err)
			}
			if total := after.TotalAlloc - before.TotalAlloc; total > 5*uint64(len(wire)) {
				t.Errorf("%s in %d-byte reads: %d bytes allocated for a %d-byte frame", name, chunk, total, len(wire))
			}
		}
	}
}

// TestReaderKeepsEveryByteAfterAGrownFrame puts more than a read buffer of frames right after
// a frame whose buffer grew, all in one read.
func TestReaderKeepsEveryByteAfterAGrownFrame(t *testing.T) {
	wire := "+" + strings.Repeat("a", 1<<20) + "\r\n" + strings.Repeat(":7\r\n", 100000)
	got := readAll(t, newChunked(wire, 1<<30))
	if len(got) != 100001 || len(got[0].Bytes) != 1<<20 || got[100000].Int != 7 {
		t.Fatalf("%d values", len(got))
	}
}
