// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

// Package mockcache is an in-process ElastiCache Integrations cache for tests: RESP3 over
// plaintext or TLS, on loopback or on a connection the test hands it. It checks the order
// of the client's commands and the shape of aws.request the way the cache does, records every
// command, and lets a test choose each reply.
//
// Under testing/synctest, make, use and close the server inside the bubble: its channels and
// timers, and the net.Pipe connections Dial makes, belong to the bubble that made them.
package mockcache

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// RouteTenOperations is a config that routes the ten operations the cache advertises.
const RouteTenOperations = `{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"service == 'dynamodb' && operation in ops","vars":{"ops":["GetItem","BatchGetItem","Query","Scan","PutItem","UpdateItem","DeleteItem","BatchWriteItem","TransactGetItems","TransactWriteItems"]}}]}`

const (
	readSize = 64 << 10
	maxFrame = 64 << 20
)

// Config chooses how the server serves. The zero value is plaintext with RouteTenOperations.
type Config struct {
	TLS        bool   // serve TLS with a certificate for localhost, 127.0.0.1 and ::1
	ConfigJSON string // the reply to aws.config with overrides
}

// Command is one command the server received.
type Command struct {
	Conn int      // the connection it came on, numbered from 1 in accept order
	Args [][]byte // the command, its name first
}

// Name is the command name in upper case.
func (c Command) Name() string { return strings.ToUpper(string(c.Args[0])) }

// Arg returns argument i as a string; Arg(0) is the name as sent.
func (c Command) Arg(i int) string { return string(c.Args[i]) }

// Server is the mock cache. Its hooks may be set at any time; each command reads them once.
type Server struct {
	tlsConf *tls.Config
	certPEM []byte
	pool    *x509.CertPool

	nextID atomic.Uint64 // deferred ids, never reused, as the cache's push id counter
	stop   chan struct{}
	wg     sync.WaitGroup

	mu         sync.Mutex
	onRequest  func(Command) Reply
	onAuth     func(user, token string) resp.Value
	onRefresh  func() Reply
	intercept  func(Command) *Reply
	configJSON string
	commands   []Command
	requests   []Command
	overrides  []string
	configured int
	accepted   int
	conns      map[*serverConn]struct{}
	held       chan struct{}
	listener   net.Listener
	failure    error
	closed     bool
}

// New makes a server. It serves nothing until Listen, ServeConn or Dial.
func New(c Config) (*Server, error) {
	s := &Server{
		stop:       make(chan struct{}),
		configJSON: c.ConfigJSON,
		conns:      map[*serverConn]struct{}{},
	}
	if s.configJSON == "" {
		s.configJSON = RouteTenOperations
	}
	if c.TLS {
		cert, certPEM, err := selfSigned()
		if err != nil {
			return nil, err
		}
		s.tlsConf = &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}
		s.certPEM = certPEM
		s.pool = x509.NewCertPool()
		s.pool.AppendCertsFromPEM(certPEM)
	}
	return s, nil
}

// Listen serves on a loopback port and returns its "127.0.0.1:port" address.
func (s *Server) Listen() (string, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	if s.closed || s.listener != nil {
		s.mu.Unlock()
		_ = l.Close()
		return "", errors.New("mockcache: server closed or already listening")
	}
	s.listener = l
	s.wg.Add(1)
	s.mu.Unlock()
	go s.acceptLoop(l)
	return l.Addr().String(), nil
}

func (s *Server) acceptLoop(l net.Listener) {
	defer s.wg.Done()
	for {
		conn, err := l.Accept()
		if err != nil {
			return
		}
		s.ServeConn(conn)
	}
}

// ServeConn serves conn until it closes or the server closes.
func (s *Server) ServeConn(conn net.Conn) {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		_ = conn.Close()
		return
	}
	s.accepted++
	sc := &serverConn{s: s, n: s.accepted, raw: conn, gate: s.held}
	s.conns[sc] = struct{}{}
	s.wg.Add(1)
	s.mu.Unlock()
	go sc.serve()
}

// Dial returns the client end of an in-memory connection the server serves, for use as a
// dial function. The address is ignored.
func (s *Server) Dial(ctx context.Context, _, _ string) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	client, server := net.Pipe()
	s.ServeConn(server)
	return client, nil
}

// Close stops the server, closes every connection, waits for every goroutine, and returns
// the first failure: a command out of order or not a command, or a reply it could not send.
func (s *Server) Close() error {
	s.mu.Lock()
	if !s.closed {
		s.closed = true
		close(s.stop)
		if s.listener != nil {
			_ = s.listener.Close()
		}
		for sc := range s.conns {
			_ = sc.raw.Close()
		}
	}
	s.mu.Unlock()
	s.wg.Wait()
	return s.Err()
}

// Err returns the first failure so far.
func (s *Server) Err() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.failure
}

func (s *Server) fail(err error) {
	s.mu.Lock()
	if s.failure == nil {
		s.failure = err
	}
	s.mu.Unlock()
}

// SetOnRequest sets the reply to a valid aws.request. The default is Served(200, nil, "{}").
func (s *Server) SetOnRequest(f func(Command) Reply) { s.mu.Lock(); s.onRequest = f; s.mu.Unlock() }

// SetOnAuth sets the reply to AUTH and to the inline AUTH of HELLO 3. The default is +OK.
// An error reply to HELLO is sent in place of the HELLO reply.
func (s *Server) SetOnAuth(f func(user, token string) resp.Value) {
	s.mu.Lock()
	s.onAuth = f
	s.mu.Unlock()
}

// SetOnRefresh sets the reply to the no-argument aws.config. The default is a null: unchanged.
func (s *Server) SetOnRefresh(f func() Reply) { s.mu.Lock(); s.onRefresh = f; s.mu.Unlock() }

// SetIntercept sets a hook that sees every command after the order check. A non-nil, non-zero
// reply replaces the normal one.
func (s *Server) SetIntercept(f func(Command) *Reply) { s.mu.Lock(); s.intercept = f; s.mu.Unlock() }

// SetConfigJSON sets the reply to aws.config with overrides.
func (s *Server) SetConfigJSON(j string) { s.mu.Lock(); s.configJSON = j; s.mu.Unlock() }

// Push writes v to every open connection.
func (s *Server) Push(v resp.Value) error {
	b, err := resp.AppendValue(nil, v)
	if err != nil {
		return err
	}
	return s.WriteRaw(b)
}

// WriteRaw writes b to every open connection. It fails when no connection is open.
func (s *Server) WriteRaw(b []byte) error {
	s.mu.Lock()
	conns := make([]*serverConn, 0, len(s.conns))
	for sc := range s.conns {
		conns = append(conns, sc)
	}
	s.mu.Unlock()
	wrote := false
	for _, sc := range conns {
		if sc.write(b) == nil {
			wrote = true
		}
	}
	if !wrote {
		return errors.New("mockcache: no open connection")
	}
	return nil
}

// DropAll closes every open connection; the client sees the end of the stream.
func (s *Server) DropAll() {
	s.mu.Lock()
	conns := make([]*serverConn, 0, len(s.conns))
	for sc := range s.conns {
		conns = append(conns, sc)
	}
	s.mu.Unlock()
	for _, sc := range conns {
		sc.drop()
	}
}

// Hold makes connections accepted from now on wait, before TLS, until Release.
func (s *Server) Hold() {
	s.mu.Lock()
	if s.held == nil {
		s.held = make(chan struct{})
	}
	s.mu.Unlock()
}

// Release lets held connections go on.
func (s *Server) Release() {
	s.mu.Lock()
	if s.held != nil {
		close(s.held)
		s.held = nil
	}
	s.mu.Unlock()
}

// Commands returns every command received, in arrival order.
func (s *Server) Commands() []Command {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Command(nil), s.commands...)
}

// Requests returns every valid aws.request.
func (s *Server) Requests() []Command {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Command(nil), s.requests...)
}

// Overrides returns the argument of every accepted aws.config with overrides.
func (s *Server) Overrides() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.overrides...)
}

// Connections counts the connections that got their first config.
func (s *Server) Connections() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.configured
}

// Open counts the connections the server has not finished serving: once one leaves the
// count, every byte it received was read and recorded.
func (s *Server) Open() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.conns)
}

// CertPool trusts the server's certificate; nil without TLS.
func (s *Server) CertPool() *x509.CertPool { return s.pool }

// CertPEM is the server's certificate as a PEM CA bundle; nil without TLS.
func (s *Server) CertPEM() []byte { return s.certPEM }

// serverConn is one connection. Its writes are serialized, so replies never interleave.
type serverConn struct {
	s    *Server
	n    int
	raw  net.Conn
	gate chan struct{}

	wmu     sync.Mutex
	out     net.Conn // set once TLS is up
	dropped atomic.Bool

	authenticated bool
	configured    bool
	hung          bool
}

func (sc *serverConn) serve() {
	s := sc.s
	defer s.wg.Done()
	defer func() {
		_ = sc.raw.Close()
		s.mu.Lock()
		delete(s.conns, sc)
		s.mu.Unlock()
	}()
	if sc.gate != nil {
		select {
		case <-sc.gate:
		case <-s.stop:
			return
		}
	}
	conn := sc.raw
	if s.tlsConf != nil {
		tc := tls.Server(sc.raw, s.tlsConf)
		if err := tc.Handshake(); err != nil {
			return
		}
		conn = tc
	}
	sc.wmu.Lock()
	sc.out = conn
	sc.wmu.Unlock()
	r := resp.NewReader(conn, readSize, maxFrame)
	for {
		v, err := r.Next()
		if err != nil {
			if errors.Is(err, resp.ErrCorrupt) {
				s.fail(fmt.Errorf("mockcache: connection %d sent a corrupt frame: %w", sc.n, err))
			}
			return
		}
		if sc.dropped.Load() {
			continue // the write side is shut; read until the client closes
		}
		cmd, ok := toCommand(sc.n, v)
		if !ok {
			s.fail(fmt.Errorf("mockcache: connection %d sent %v, not a command", sc.n, v))
			sc.drop()
			continue
		}
		if !sc.send(sc.handle(cmd)) {
			return
		}
	}
}

func toCommand(conn int, v resp.Value) (Command, bool) {
	if v.Kind != resp.KindArray || len(v.Elems) == 0 {
		return Command{}, false
	}
	args := make([][]byte, len(v.Elems))
	for i, e := range v.Elems {
		if e.Kind != resp.KindBulkString {
			return Command{}, false
		}
		args[i] = e.Bytes
	}
	return Command{Conn: conn, Args: args}, true
}

// handle records cmd and picks its reply.
func (sc *serverConn) handle(cmd Command) Reply {
	s := sc.s
	s.mu.Lock()
	s.commands = append(s.commands, cmd)
	intercept := s.intercept
	s.mu.Unlock()
	if sc.hung {
		return Hang()
	}
	name := cmd.Name()
	if err := sc.checkOrder(name, len(cmd.Args)-1); err != nil {
		s.fail(fmt.Errorf("mockcache: connection %d: %w", sc.n, err))
		return Drop()
	}
	var reply Reply
	if intercept != nil {
		if r := intercept(cmd); r != nil {
			reply = *r
		}
	}
	if !reply.valid() {
		switch name {
		case "HELLO":
			reply = s.hello(cmd, sc.authenticated)
		case "AUTH":
			reply = s.auth(cmd)
		case "AWS.CONFIG":
			reply = s.config(cmd)
		case "AWS.REQUEST":
			reply = s.request(cmd)
		default:
			reply = Now(resp.ErrorValue("ERR", "unknown command '"+cmd.Arg(0)+"'"))
		}
	}
	sc.observe(name, len(cmd.Args)-1, reply)
	return reply
}

func (r Reply) valid() bool { return r.kind != replyNow || r.hasValue }

// checkOrder enforces the client's order: HELLO 3 AUTH, then aws.config with overrides, then
// anything.
func (sc *serverConn) checkOrder(name string, args int) error {
	switch {
	case name == "HELLO":
		return nil
	case !sc.authenticated:
		return fmt.Errorf("the client sent %s before HELLO 3 AUTH", name)
	case name == "AWS.CONFIG" && args == 0 && !sc.configured:
		return errors.New("the client sent aws.config without overrides first")
	case name != "AWS.CONFIG" && name != "AUTH" && !sc.configured:
		return fmt.Errorf("the client sent %s before aws.config", name)
	}
	return nil
}

// observe tracks the replies the client saw.
func (sc *serverConn) observe(name string, args int, r Reply) {
	if r.kind != replyNow {
		return
	}
	switch {
	case name == "HELLO" && r.value.Kind == resp.KindMap:
		sc.authenticated = true
	case name == "AWS.CONFIG" && args == 1 && r.value.Kind == resp.KindBulkString && !sc.configured:
		sc.configured = true
		sc.s.mu.Lock()
		sc.s.configured++
		sc.s.mu.Unlock()
	}
}

func (s *Server) authReply(user, token string) resp.Value {
	s.mu.Lock()
	f := s.onAuth
	s.mu.Unlock()
	if f == nil {
		return OK()
	}
	return f(user, token)
}

func (s *Server) hello(cmd Command, authenticated bool) Reply {
	if len(cmd.Args) < 2 || cmd.Arg(1) != "3" {
		return Now(resp.ErrorValue("NOPROTO", "unsupported protocol version"))
	}
	if len(cmd.Args) == 2 {
		if !authenticated {
			return Now(resp.ErrorValue("NOAUTH", "HELLO must be called with the client already authenticated, "+
				"otherwise the HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client and "+
				"select the RESP protocol version at the same time"))
		}
		return Now(Hello())
	}
	if len(cmd.Args) != 5 || !strings.EqualFold(cmd.Arg(2), "AUTH") {
		return Now(resp.ErrorValue("ERR", "syntax error"))
	}
	if r := s.authReply(cmd.Arg(3), cmd.Arg(4)); r.Kind == resp.KindError {
		return Now(r)
	}
	return Now(Hello())
}

func (s *Server) auth(cmd Command) Reply {
	if len(cmd.Args) != 3 {
		return Now(resp.ErrorValue("ERR", "wrong number of arguments for 'auth' command"))
	}
	return Now(s.authReply(cmd.Arg(1), cmd.Arg(2)))
}

func (s *Server) config(cmd Command) Reply {
	switch len(cmd.Args) {
	case 1:
		s.mu.Lock()
		f := s.onRefresh
		s.mu.Unlock()
		if f == nil {
			return Now(resp.NullValue())
		}
		return f()
	case 2:
		var doc map[string]any
		if json.Unmarshal(cmd.Args[1], &doc) != nil || !validVersion(doc["version"]) {
			return Now(resp.ErrorValue("CONFIG_INVALID", `overrides must be one JSON object with a "<major>.<minor>" version`))
		}
		s.mu.Lock()
		s.overrides = append(s.overrides, cmd.Arg(1))
		j := s.configJSON
		s.mu.Unlock()
		return Now(resp.BulkStringValue(j))
	default:
		return Now(resp.ErrorValue("ERR", "wrong number of arguments for 'aws.config' command"))
	}
}

func (s *Server) request(cmd Command) Reply {
	if len(cmd.Args) != 6 || !hasServiceAndOperation(cmd.Args[5]) {
		return Now(resp.ErrorValue("PARSE_ERROR", "failed to parse aws.request"))
	}
	s.mu.Lock()
	s.requests = append(s.requests, cmd)
	f := s.onRequest
	s.mu.Unlock()
	if f == nil {
		return Now(Served(200, nil, []byte("{}")))
	}
	return f(cmd)
}

// validVersion reports whether v is "<major>.<minor>" with numeric parts, as the cache needs.
func validVersion(v any) bool {
	s, ok := v.(string)
	if !ok {
		return false
	}
	major, minor, ok := strings.Cut(s, ".")
	return ok && digits(major) && digits(minor)
}

func digits(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func hasServiceAndOperation(metadata []byte) bool {
	var service, op bool
	for _, pair := range bytes.Split(metadata, []byte("&")) {
		k, v, ok := bytes.Cut(pair, []byte("="))
		if !ok || len(v) == 0 {
			continue
		}
		service = service || string(k) == "service"
		op = op || string(k) == "op"
	}
	return service && op
}

// send carries out r and reports whether to go on reading the connection.
func (sc *serverConn) send(r Reply) bool {
	s := sc.s
	if !r.valid() {
		s.fail(fmt.Errorf("mockcache: connection %d: a hook returned the zero Reply", sc.n))
		sc.drop()
		return true
	}
	if r.delay > 0 && r.kind == replyNow && !sc.sleep(r.delay) {
		return false
	}
	switch r.kind {
	case replyNow:
		return sc.writeValue(r.value)
	case replyDeferred:
		id := strconv.FormatUint(s.nextID.Add(1), 10)
		if !sc.writeValue(resp.SimpleValue(id)) {
			return false
		}
		s.wg.Add(1)
		go sc.pushLater(id, r)
		return true
	case replyHang:
		sc.hung = true
		return true
	case replyDrop:
		if r.hasValue {
			sc.writeValue(r.value)
		}
		sc.drop()
		return true
	default: // replyRaw
		return sc.write(r.raw) == nil
	}
}

func (sc *serverConn) pushLater(id string, r Reply) {
	defer sc.s.wg.Done()
	v := r.value
	switch {
	case r.later != nil:
		var ok bool
		select {
		case v, ok = <-r.later:
			if !ok {
				return
			}
		case <-sc.s.stop:
			return
		}
	case r.delay > 0:
		if !sc.sleep(r.delay) {
			return
		}
	}
	sc.writeValue(ResponsePush(id, v))
}

func (sc *serverConn) sleep(d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-sc.s.stop:
		return false
	}
}

func (sc *serverConn) writeValue(v resp.Value) bool {
	b, err := resp.AppendValue(nil, v)
	if err != nil {
		sc.s.fail(fmt.Errorf("mockcache: cannot encode a reply: %w", err))
		return false
	}
	return sc.write(b) == nil
}

func (sc *serverConn) write(b []byte) error {
	sc.wmu.Lock()
	defer sc.wmu.Unlock()
	if sc.dropped.Load() {
		return errors.New("mockcache: connection dropped")
	}
	if sc.out == nil {
		return errors.New("mockcache: connection not ready")
	}
	_, err := sc.out.Write(b)
	return err
}

// drop ends the stream after what was written. A TCP connection shuts its write side and is
// read until the client closes it: closing it with unread input would reset it, and the
// client could see the reset in place of the end of the stream. A write stuck on a client
// that does not read is cut by closing the connection.
func (sc *serverConn) drop() {
	if !sc.wmu.TryLock() {
		sc.dropped.Store(true)
		_ = sc.raw.Close()
		return
	}
	defer sc.wmu.Unlock()
	if sc.dropped.Swap(true) {
		return
	}
	tcp, ok := sc.raw.(*net.TCPConn)
	if !ok || sc.out == nil {
		_ = sc.raw.Close()
		return
	}
	if tc, ok := sc.out.(*tls.Conn); ok {
		_ = tc.CloseWrite()
	}
	_ = tcp.CloseWrite()
}
