// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

type relayMode int32

const (
	relayForward relayMode = iota // copy bytes both ways
	relayReset                    // reset every connection, and new ones at once
	relayHang                     // keep connections open and drop every byte
)

// relay forwards TCP connections to one cache address, and can cut or hang them. TLS passes
// through it untouched, so the client still verifies the real cache certificate.
type relay struct {
	ln     net.Listener
	target string
	dial   func(network, addr string, timeout time.Duration) (net.Conn, error)
	mode   atomic.Int32 // written under mu, so a connection is tracked under the mode it was set up for
	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
	wg     sync.WaitGroup
}

func startRelay(addr, target string) (*relay, error) {
	return startRelayDial(addr, target, net.DialTimeout)
}

func startRelayDial(addr, target string, dial func(network, addr string, timeout time.Duration) (net.Conn, error)) (*relay, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	r := &relay{ln: ln, target: target, dial: dial, conns: map[net.Conn]struct{}{}}
	r.wg.Add(1)
	go r.accept()
	return r, nil
}

func (r *relay) addr() string { return r.ln.Addr().String() }

// set changes the mode. Entering reset, or leaving hang, resets every open connection: a hung
// connection has lost bytes, so it cannot carry on.
func (r *relay) set(m relayMode) {
	r.mu.Lock()
	prev := relayMode(r.mode.Swap(int32(m)))
	var conns map[net.Conn]struct{}
	if m == relayReset || prev == relayHang {
		conns = r.takeLocked()
	}
	r.mu.Unlock()
	resetEach(conns)
}

func (r *relay) close() {
	_ = r.ln.Close()
	r.mu.Lock()
	r.closed = true
	conns := r.takeLocked()
	r.mu.Unlock()
	resetEach(conns)
	r.wg.Wait()
}

func (r *relay) accept() {
	defer r.wg.Done()
	for {
		c, err := r.ln.Accept()
		if err != nil {
			return
		}
		m := relayMode(r.mode.Load())
		switch m {
		case relayReset:
			reset(c)
		case relayHang:
			if !r.track(m, c) {
				continue
			}
			r.wg.Add(1)
			go func() { defer r.wg.Done(); r.pipe(io.Discard, c, nil) }()
		default:
			up, err := r.dial("tcp", r.target, 5*time.Second)
			if err != nil {
				reset(c)
				continue
			}
			if !r.track(m, c, up) {
				continue
			}
			r.wg.Add(2)
			go func() { defer r.wg.Done(); r.pipe(up, c, up) }()
			go func() { defer r.wg.Done(); r.pipe(c, up, c) }()
		}
	}
}

// pipe copies src to dst until src fails, dropping the bytes while the relay hangs, then
// closes src and peer.
func (r *relay) pipe(dst io.Writer, src net.Conn, peer net.Conn) {
	buf := make([]byte, 32<<10)
	for {
		n, err := src.Read(buf)
		if n > 0 && relayMode(r.mode.Load()) == relayForward {
			if _, werr := dst.Write(buf[:n]); werr != nil {
				err = werr
			}
		}
		if err != nil {
			break
		}
	}
	r.untrack(src)
	if peer != nil {
		r.untrack(peer)
	}
}

// track registers conns, or resets them when the relay closed or left mode m since the
// caller read it.
func (r *relay) track(m relayMode, conns ...net.Conn) bool {
	r.mu.Lock()
	ok := !r.closed && relayMode(r.mode.Load()) == m
	if ok {
		for _, c := range conns {
			r.conns[c] = struct{}{}
		}
	}
	r.mu.Unlock()
	if !ok {
		for _, c := range conns {
			reset(c)
		}
	}
	return ok
}

// untrack closes c once.
func (r *relay) untrack(c net.Conn) {
	r.mu.Lock()
	_, ok := r.conns[c]
	delete(r.conns, c)
	r.mu.Unlock()
	if ok {
		_ = c.Close()
	}
}

func (r *relay) takeLocked() map[net.Conn]struct{} {
	conns := r.conns
	r.conns = map[net.Conn]struct{}{}
	return conns
}

func resetEach(conns map[net.Conn]struct{}) {
	for c := range conns {
		reset(c)
	}
}

// reset closes c with a TCP RST rather than a FIN.
func reset(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetLinger(0)
	}
	_ = c.Close()
}
