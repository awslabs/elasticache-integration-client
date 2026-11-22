// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package resp

import (
	"errors"
	"io"
	"strconv"
	"unsafe"
)

// maxEmptyReads is how many reads in a row may return no bytes and no error.
const maxEmptyReads = 100

var errInvalidRead = errors.New("resp: reader returned an invalid count")

// valueSize is the memory one decoded element takes.
const valueSize = int(unsafe.Sizeof(Value{}))

// Reader reads RESP values from a stream, such as a connection, for one goroutine.
//
// A value Next returns owns its memory: its slices never alias the read buffer. A frame that
// fits the read buffer is copied out of it once. A larger frame is read into a buffer of its
// own, sized from what Scan says it still needs, and that buffer becomes the value's memory.
// Scan runs again only once the bytes it asked for have arrived.
type Reader struct {
	r          io.Reader
	buf        []byte // the read buffer
	start, end int    // the unread bytes are buf[start:end]
	want       int    // the unread bytes needed before the next Scan
	scanned    int    // the unread bytes the last Scan saw
	maxFrame   int
	readErr    error // returned once the buffered bytes are used up
	err        error // sticky
	scans      int   // Scan calls, for tests
}

// NewReader returns a Reader that reads up to readSize bytes at a time and refuses a frame
// over maxFrame bytes with ErrFrameTooLarge.
func NewReader(r io.Reader, readSize, maxFrame int) *Reader {
	if readSize <= 0 || maxFrame <= 0 {
		panic("resp: NewReader needs a positive read size and frame cap")
	}
	return &Reader{r: r, buf: make([]byte, readSize), maxFrame: maxFrame, want: 1}
}

// Next returns the next value. It returns io.EOF at the end of the stream between frames,
// io.ErrUnexpectedEOF inside a frame, a *CorruptError (ErrCorrupt, and ErrFrameTooLarge for
// a frame over the cap) for bytes that can never form a valid frame, and any other read
// error as it is, once the complete frames read before it are returned. After an error,
// every call returns it.
func (r *Reader) Next() (Value, error) {
	if r.err != nil {
		return Value{}, r.err
	}
	for {
		n := r.end - r.start
		// At the end of the stream, bytes no Scan has seen are scanned once, so corrupt
		// bytes are not reported as a truncated frame.
		if n > 0 && (n >= r.want || r.readErr != nil && n > r.scanned) {
			r.scans++
			r.scanned = n
			ext, err := Scan(r.buf[r.start:r.end])
			if err != nil {
				return r.fail(err)
			}
			if ext.Len > 0 {
				if err := r.overCap(ext); err != nil {
					return r.fail(err)
				}
				frame := make([]byte, ext.Len)
				copy(frame, r.buf[r.start:])
				r.start += ext.Len
				r.want, r.scanned = 1, 0
				if r.start == r.end {
					r.start, r.end = 0, 0
				}
				return build(frame, ext.Values), nil
			}
			if ext.Need > r.maxFrame-n {
				return r.fail(r.tooLarge())
			}
			if ext.Need > len(r.buf)-n && r.readErr == nil {
				return r.readLarge(ext)
			}
			r.want = n + ext.Need
		}
		if r.readErr != nil {
			return r.fail(r.endErr(n))
		}
		if r.start > 0 && len(r.buf)-r.end < r.want-n {
			r.end = copy(r.buf, r.buf[r.start:r.end])
			r.start = 0
		}
		r.end += r.read(r.buf[r.end:])
	}
}

// readLarge reads a frame that does not fit the read buffer into a buffer of its own.
func (r *Reader) readLarge(ext Extent) (Value, error) {
	have := r.end - r.start
	frame := make([]byte, r.frameSize(have, ext))
	copy(frame, r.buf[r.start:r.end])
	r.start, r.end, r.want, r.scanned = 0, 0, 1, 0
	want, scanned := have+ext.Need, have
	for {
		for have < want && r.readErr == nil {
			// At most one read size past want, so the bytes past the frame fit the read buffer.
			have += r.read(frame[have:max(want, have+min(len(r.buf), len(frame)-have))])
		}
		if have < want && have == scanned {
			return r.fail(r.endErr(have))
		}
		scanned = have
		r.scans++
		var err error
		if ext, err = Scan(frame[:have]); err != nil {
			return r.fail(err)
		}
		if ext.Len > 0 {
			if err := r.overCap(ext); err != nil {
				return r.fail(err)
			}
			r.end = copy(r.buf, frame[ext.Len:have])
			return build(frame[:ext.Len:ext.Len], ext.Values), nil
		}
		if ext.Need > r.maxFrame-have {
			return r.fail(r.tooLarge())
		}
		if r.readErr != nil {
			return r.fail(r.endErr(have))
		}
		want = have + ext.Need
		if want > len(frame) {
			grown := make([]byte, r.frameSize(have, ext))
			copy(grown, frame[:have])
			frame = grown
		}
	}
}

// frameSize is exactly the frame when Need is exact. Otherwise it leaves room for one more
// read, so a frame of many small elements is not read a few bytes at a time, and at least
// doubles, so a frame whose Need stays small is not copied once per read.
func (r *Reader) frameSize(have int, ext Extent) int {
	if ext.Exact {
		return have + ext.Need
	}
	return have + min(max(ext.Need, len(r.buf), have), r.maxFrame-have)
}

// read reads into p and returns the count; it records an error or a stalled stream.
func (r *Reader) read(p []byte) int {
	for range maxEmptyReads {
		m, err := r.r.Read(p)
		if m < 0 || m > len(p) {
			r.readErr = errInvalidRead
			return 0
		}
		if err != nil {
			r.readErr = err
		}
		if m > 0 || err != nil {
			return m
		}
	}
	r.readErr = io.ErrNoProgress
	return 0
}

func (r *Reader) endErr(buffered int) error {
	if r.readErr == io.EOF && buffered > 0 {
		return io.ErrUnexpectedEOF
	}
	return r.readErr
}

// overCap refuses a complete frame over the cap, or one whose elements would take more
// memory than the cap allows for the frame itself.
func (r *Reader) overCap(ext Extent) error {
	if ext.Len > r.maxFrame {
		return r.tooLarge()
	}
	if ext.Values > r.maxFrame/valueSize {
		return &CorruptError{Msg: "frame elements need more than " + strconv.Itoa(r.maxFrame) + " bytes", TooLarge: true}
	}
	return nil
}

func (r *Reader) tooLarge() error {
	return &CorruptError{Msg: "frame larger than " + strconv.Itoa(r.maxFrame) + " bytes", TooLarge: true}
}

func (r *Reader) fail(err error) (Value, error) {
	r.err = err
	return Value{}, err
}
