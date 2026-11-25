// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package mockcache

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

// RelayMode is what a Relay does with a connection it accepts.
type RelayMode uint8

const (
	// Forward copies bytes both ways between the connection and the target.
	Forward RelayMode = iota
	// Refuse closes the connection at once, as when the cache is unreachable.
	Refuse
	// Blackhole keeps the connection open and never answers, as a peer that hangs.
	Blackhole
)

// Relay is a TCP pass-through on loopback, put between a client and a server to cut, refuse or
// swallow connections.
type Relay struct {
	target   string
	listener net.Listener
	dial     func(ctx context.Context, addr string) (net.Conn, error)
	ctx      context.Context // the lifetime of the relay's goroutines, cancelled by Close
	cancel   context.CancelFunc
	wg       sync.WaitGroup

	mu       sync.Mutex
	mode     RelayMode
	accepted int
	open     map[net.Conn]struct{}
	closed   bool
	flowing  chan struct{} // closed while bytes flow; open while paused
	changed  chan struct{} // closed and replaced when a connection is reset or forgotten
}

// NewRelay listens on a loopback port and forwards each connection to target ("host:port").
func NewRelay(target string) (*Relay, error) {
	d := &net.Dialer{Timeout: 5 * time.Second}
	return newRelayWithDial(target, func(ctx context.Context, addr string) (net.Conn, error) {
		return d.DialContext(ctx, "tcp", addr)
	})
}

// newRelayWithDial is NewRelay with the dial to the target replaced.
func newRelayWithDial(target string, dial func(ctx context.Context, addr string) (net.Conn, error)) (*Relay, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	flowing := make(chan struct{})
	close(flowing)
	r := &Relay{target: target, listener: l, dial: dial, ctx: ctx, cancel: cancel, open: map[net.Conn]struct{}{},
		flowing: flowing, changed: make(chan struct{})}
	r.wg.Add(1)
	go r.acceptLoop()
	return r, nil
}

// Addr is the relay's "127.0.0.1:port" address.
func (r *Relay) Addr() string { return r.listener.Addr().String() }

// SetMode sets what the relay does with connections accepted from now on. Open connections
// are not affected.
func (r *Relay) SetMode(m RelayMode) {
	r.mu.Lock()
	r.mode = m
	r.mu.Unlock()
}

// Pause stops copying bytes on every forwarded connection, open or new, until Resume. The
// sockets stay open, so a writer blocks once the socket buffers are full, as on a link that
// stopped delivering.
func (r *Relay) Pause() {
	r.mu.Lock()
	select {
	case <-r.flowing:
		r.flowing = make(chan struct{})
	default:
	}
	r.mu.Unlock()
}

// Resume undoes Pause.
func (r *Relay) Resume() {
	r.mu.Lock()
	select {
	case <-r.flowing:
	default:
		close(r.flowing)
	}
	r.mu.Unlock()
}

// Accepted counts the connections the relay accepted, in any mode.
func (r *Relay) Accepted() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.accepted
}

// Disconnect resets every open connection, including one still connecting to the target: the
// client sees a reset, not the end of the stream.
func (r *Relay) Disconnect() {
	r.mu.Lock()
	conns := make([]net.Conn, 0, len(r.open))
	for c := range r.open {
		// Linger 0 is set before the socket leaves the set: a copy that then finds it gone
		// closes its pair, and that close must reset too.
		if tcp, ok := c.(*net.TCPConn); ok {
			_ = tcp.SetLinger(0)
		}
		conns = append(conns, c)
		delete(r.open, c)
	}
	r.wakeLocked()
	r.mu.Unlock()
	for _, c := range conns {
		_ = c.Close()
	}
}

// Close stops accepting, resets every open connection and waits for every goroutine. It is
// safe to call more than once.
func (r *Relay) Close() error {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		r.wg.Wait()
		return nil
	}
	r.closed = true
	r.mu.Unlock()
	err := r.listener.Close()
	r.Disconnect()
	r.cancel()
	r.wg.Wait()
	if errors.Is(err, net.ErrClosed) {
		err = nil
	}
	return err
}

func (r *Relay) acceptLoop() {
	defer r.wg.Done()
	for {
		conn, err := r.listener.Accept()
		if err != nil {
			return
		}
		r.mu.Lock()
		if r.closed {
			r.mu.Unlock()
			_ = conn.Close()
			return
		}
		r.accepted++
		mode := r.mode
		if mode != Refuse {
			r.open[conn] = struct{}{}
		}
		r.wg.Add(1)
		r.mu.Unlock()
		go r.serve(conn, mode)
	}
}

func (r *Relay) serve(client net.Conn, mode RelayMode) {
	defer r.wg.Done()
	switch mode {
	case Refuse:
		_ = client.Close()
		return
	case Blackhole:
		_, _ = io.Copy(io.Discard, client)
		r.untrack(client)
		return
	}
	upstream, err := r.dial(r.ctx, r.target)
	if err != nil {
		r.untrack(client)
		return
	}
	if !r.trackUpstream(client, upstream) {
		_ = upstream.Close()
		return
	}
	done := make(chan struct{}, 2)
	pipe := func(dst, src net.Conn) {
		r.copy(dst, src)
		done <- struct{}{}
	}
	go pipe(upstream, client)
	go pipe(client, upstream)
	<-done
	r.untrack(client, upstream)
	<-done
}

// copy copies src to dst until either fails, holding each chunk while the relay is paused.
func (r *Relay) copy(dst, src net.Conn) {
	buf := make([]byte, 32<<10)
	for {
		n, err := src.Read(buf)
		if n > 0 {
			if !r.waitFlowing(src) {
				return
			}
			if _, werr := dst.Write(buf[:n]); werr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

// waitFlowing blocks while the relay is paused. It reports false once conn is no longer open.
func (r *Relay) waitFlowing(conn net.Conn) bool {
	for {
		r.mu.Lock()
		_, open := r.open[conn]
		flowing, changed := r.flowing, r.changed
		r.mu.Unlock()
		if !open {
			return false
		}
		select {
		case <-flowing:
			return true
		case <-changed:
		case <-r.ctx.Done():
			return false
		}
	}
}

// wakeLocked wakes every waitFlowing to check its connection again. r.mu is held.
func (r *Relay) wakeLocked() {
	close(r.changed)
	r.changed = make(chan struct{})
}

// trackUpstream records upstream as open with client. It reports false when client was reset
// or the relay closed while the target was dialed.
func (r *Relay) trackUpstream(client, upstream net.Conn) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.open[client]; !ok || r.closed {
		return false
	}
	r.open[upstream] = struct{}{}
	return true
}

// untrack closes conns and forgets them.
func (r *Relay) untrack(conns ...net.Conn) {
	r.mu.Lock()
	for _, c := range conns {
		delete(r.open, c)
	}
	r.wakeLocked()
	r.mu.Unlock()
	for _, c := range conns {
		_ = c.Close()
	}
}
