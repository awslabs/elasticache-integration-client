// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

func TestCanon(t *testing.T) {
	a := map[string]types.AttributeValue{
		"id":  str("k"),
		"ss":  &types.AttributeValueMemberSS{Value: []string{"b", "a"}},
		"ns":  &types.AttributeValueMemberNS{Value: []string{"2", "1"}},
		"bs":  &types.AttributeValueMemberBS{Value: [][]byte{{2}, {1}}},
		"m":   &types.AttributeValueMemberM{Value: map[string]types.AttributeValue{"y": num(1), "x": &types.AttributeValueMemberNULL{Value: true}}},
		"l":   &types.AttributeValueMemberL{Value: []types.AttributeValue{&types.AttributeValueMemberBOOL{Value: true}, &types.AttributeValueMemberB{Value: []byte("z")}}},
		"num": num(7),
	}
	b := map[string]types.AttributeValue{
		"num": num(7),
		"l":   &types.AttributeValueMemberL{Value: []types.AttributeValue{&types.AttributeValueMemberBOOL{Value: true}, &types.AttributeValueMemberB{Value: []byte("z")}}},
		"m":   &types.AttributeValueMemberM{Value: map[string]types.AttributeValue{"x": &types.AttributeValueMemberNULL{Value: true}, "y": num(1)}},
		"bs":  &types.AttributeValueMemberBS{Value: [][]byte{{1}, {2}}},
		"ns":  &types.AttributeValueMemberNS{Value: []string{"1", "2"}},
		"ss":  &types.AttributeValueMemberSS{Value: []string{"a", "b"}},
		"id":  str("k"),
	}
	if canon(a) != canon(b) {
		t.Fatalf("equal items differ:\n%s\n%s", canon(a), canon(b))
	}
	want := `{"bs":BS[AQ==,Ag==],"id":S"k","l":L[BOOLtrue,Beg==],"m":M{"x":NULL,"y":N1},"ns":NS[1,2],"num":N7,"ss":SS["a","b"]}`
	if got := canon(a); got != want {
		t.Fatalf("canon = %s, want %s", got, want)
	}
	if canon(nil) != "<none>" {
		t.Fatalf("canon(nil) = %s", canon(nil))
	}
	c := map[string]types.AttributeValue{"id": str("k"), "v": num(1)}
	d := map[string]types.AttributeValue{"id": str("k"), "v": str("1")}
	if canon(c) == canon(d) {
		t.Fatal("a number and a string compare equal")
	}
	if canonAll([]map[string]types.AttributeValue{c, a}) != canonAll([]map[string]types.AttributeValue{b, c}) {
		t.Fatal("canonAll depends on order")
	}
	if canonAll(nil) == canonAll([]map[string]types.AttributeValue{nil}) {
		t.Fatal("an empty list equals a list with one missing item")
	}
}

func TestLoadSettings(t *testing.T) {
	env := map[string]string{
		"ELASTICACHE_CACHE_HOST": "h", "ELASTICACHE_CACHE_NAME": "n", "ELASTICACHE_TABLE_NAME": "t",
	}
	get := func(k string) string { return env[k] }
	s, err := loadSettings(get)
	if err != nil || s.port != 6379 || s.region != "us-east-1" || s.host != "h" || s.cacheName != "n" || s.table != "t" {
		t.Fatalf("defaults: %+v %v", s, err)
	}
	env["ELASTICACHE_CACHE_PORT"] = "70000"
	if _, err := loadSettings(get); err == nil {
		t.Fatal("port 70000 accepted")
	}
	env["ELASTICACHE_CACHE_PORT"] = "6380"
	delete(env, "ELASTICACHE_TABLE_NAME")
	if _, err := loadSettings(get); err == nil || !strings.Contains(err.Error(), "ELASTICACHE_TABLE_NAME") {
		t.Fatalf("missing table: %v", err)
	}
}

func TestEventLog(t *testing.T) {
	var out bytes.Buffer
	l := newEventLog(&out)
	lg := l.logger().With(slog.Int("event", 1101))
	lg.Debug("re-AUTH failed; retrying", slog.String("error", "WRONGPASS bad"))
	mark := l.mark()
	lg.Warn("re-AUTH failed; retrying", slog.String("error", "WRONGPASS bad"))
	l.logger().Info("reconnected to the cache", slog.Int("event", 1304))
	if n := l.since(0, 1101, "re-AUTH"); n != 2 {
		t.Fatalf("since(0) = %d, want 2", n)
	}
	if n := l.since(mark, 1101, "re-AUTH"); n != 1 {
		t.Fatalf("since(mark) = %d, want 1", n)
	}
	if strings.Contains(out.String(), "DEBUG") || !strings.Contains(out.String(), "WRONGPASS bad") || !strings.Contains(out.String(), "1304") {
		t.Fatalf("printed:\n%s", out.String())
	}
	e, ok := l.wait(context.Background(), 0, 1101, "re-AUTH", 2)
	if !ok || e.level != slog.LevelWarn || e.err != "WRONGPASS bad" {
		t.Fatalf("wait = %+v %v", e, ok)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if _, ok := l.wait(ctx, 0, 1101, "re-AUTH", 3); ok {
		t.Fatal("wait found a third event")
	}
}

func TestEventLogNonIntEvent(t *testing.T) {
	l := newEventLog(io.Discard)
	l.logger().Info("odd", slog.String("event", "1301"))
	l.logger().Info("applied", slog.Int("event", 1301))
	if n := l.since(0, 1301, ""); n != 1 {
		t.Fatalf("since = %d, want 1", n)
	}
	if n := l.mark(); n != 2 {
		t.Fatalf("recorded %d events, want 2", n)
	}
}

func TestCheckConcurrent(t *testing.T) {
	l := &live{out: io.Discard}
	var wg sync.WaitGroup
	for i := range 50 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			l.check("c", i%5 != 0, "")
		}()
	}
	wg.Wait()
	if l.passed != 40 || l.failed != 10 {
		t.Fatalf("passed %d, failed %d", l.passed, l.failed)
	}
	if exitCode(l, nil) != 1 {
		t.Fatal("a failed check exits 0")
	}
}

func TestSwitchableCredentials(t *testing.T) {
	s := &switchableCredentials{next: aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
		return aws.Credentials{AccessKeyID: "AKID", SecretAccessKey: "secret"}, nil
	})}
	c, _ := s.Retrieve(context.Background())
	if c.SecretAccessKey != "secret" {
		t.Fatalf("secret %q", c.SecretAccessKey)
	}
	s.broken.Store(true)
	c, _ = s.Retrieve(context.Background())
	if c.SecretAccessKey == "secret" || c.AccessKeyID != "AKID" {
		t.Fatalf("broken credentials %+v", c)
	}
	s.next = aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
		return aws.Credentials{}, errors.New("no credentials")
	})
	if _, err := s.Retrieve(context.Background()); err == nil {
		t.Fatal("error swallowed")
	}
}

// echoServer answers every connection by echoing its bytes.
func echoServer(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { _, _ = io.Copy(c, c); _ = c.Close() }()
		}
	}()
	return ln.Addr().String()
}

func roundTrip(c net.Conn, msg string) (string, error) {
	_ = c.SetDeadline(time.Now().Add(300 * time.Millisecond))
	if _, err := c.Write([]byte(msg)); err != nil {
		return "", err
	}
	buf := make([]byte, len(msg))
	_, err := io.ReadFull(c, buf)
	return string(buf), err
}

func TestRelay(t *testing.T) {
	r, err := startRelay("127.0.0.1:0", echoServer(t))
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	dial := func() net.Conn {
		c, err := net.Dial("tcp", r.addr())
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = c.Close() })
		return c
	}

	live := dial()
	if got, err := roundTrip(live, "ping"); err != nil || got != "ping" {
		t.Fatalf("forward: %q %v", got, err)
	}

	r.set(relayHang)
	if _, err := roundTrip(live, "lost"); !isTimeout(err) {
		t.Fatalf("hang: a live connection did not hang: %v", err)
	}
	if _, err := roundTrip(dial(), "lost"); !isTimeout(err) {
		t.Fatalf("hang: a new connection did not hang: %v", err)
	}

	r.set(relayForward)
	if _, err := roundTrip(live, "x"); !isClosed(err) {
		t.Fatalf("a hung connection was not reset after the hang: %v", err)
	}
	fresh := dial()
	if got, err := roundTrip(fresh, "pong"); err != nil || got != "pong" {
		t.Fatalf("forward after hang: %q %v", got, err)
	}

	r.set(relayReset)
	if _, err := roundTrip(fresh, "x"); !isClosed(err) {
		t.Fatalf("reset: a live connection was not reset: %v", err)
	}
	if c, err := net.Dial("tcp", r.addr()); err == nil { // the reset can already fail the dial
		_, err = roundTrip(c, "x")
		_ = c.Close()
		if !isClosed(err) {
			t.Fatalf("reset: a new connection was not reset: %v", err)
		}
	}

	r.set(relayForward)
	if got, err := roundTrip(dial(), "back"); err != nil || got != "back" {
		t.Fatalf("forward after reset: %q %v", got, err)
	}
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// isClosed reports an error from a closed or reset connection, not a timeout.
func isClosed(err error) bool { return err != nil && !isTimeout(err) }

// blockedRelay starts a relay to an echo server whose upstream dial waits for release, and
// reports on entered when it starts.
func blockedRelay(t *testing.T) (r *relay, entered, release chan struct{}) {
	t.Helper()
	entered, release = make(chan struct{}, 1), make(chan struct{})
	r, err := startRelayDial("127.0.0.1:0", echoServer(t), func(network, addr string, timeout time.Duration) (net.Conn, error) {
		entered <- struct{}{}
		<-release
		return net.DialTimeout(network, addr, timeout)
	})
	if err != nil {
		t.Fatal(err)
	}
	return r, entered, release
}

func TestRelayModeChangeWhileDialing(t *testing.T) {
	for _, m := range []relayMode{relayReset, relayHang} {
		r, entered, release := blockedRelay(t)
		c, err := net.Dial("tcp", r.addr())
		if err != nil {
			t.Fatal(err)
		}
		<-entered
		r.set(m)
		close(release)
		if _, err := roundTrip(c, "x"); !isClosed(err) {
			t.Errorf("mode %d set while dialing: the connection was not reset: %v", m, err)
		}
		_ = c.Close()
		r.close()
	}
}

func TestRelayCloseWhileDialing(t *testing.T) {
	r, entered, release := blockedRelay(t)
	c, err := net.Dial("tcp", r.addr())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	<-entered
	done := make(chan struct{})
	go func() { r.close(); close(done) }()
	time.Sleep(20 * time.Millisecond)
	close(release)
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("close did not return: a connection set up while closing is still piped")
	}
}

func TestCheckRelayTarget(t *testing.T) {
	for target, ok := range map[string]bool{
		"10.0.1.7:6379": true, "[2600:1f18::1]:6379": true,
		"": false, ":6379": false, "127.0.0.1:6379": false, "[::1]:6379": false, "0.0.0.0:6379": false,
		"cache.example.com:6379": false, "10.0.1.7": false, "10.0.1.7:0": false, "10.0.1.7:x": false,
	} {
		if err := checkRelayTarget(target); (err == nil) != ok {
			t.Errorf("checkRelayTarget(%q) = %v", target, err)
		}
	}
}

// slowBody delays its first read, as decoding a large reply takes time.
type slowBody struct {
	r     io.Reader
	delay time.Duration
	once  sync.Once
}

func (b *slowBody) Read(p []byte) (int, error) {
	b.once.Do(func() { time.Sleep(b.delay) })
	return b.r.Read(p)
}

func (b *slowBody) Close() error { return nil }

type replyHTTP struct{ body string }

func (h replyHTTP) Do(r *http.Request) (*http.Response, error) {
	return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"application/x-amz-json-1.0"}},
		Body: &slowBody{r: strings.NewReader(h.body), delay: 100 * time.Millisecond}, Request: r}, nil
}

func TestRecordArrivalBeforeDecode(t *testing.T) {
	c := dynamodb.New(dynamodb.Options{Region: "us-east-1", Credentials: aws.AnonymousCredentials{},
		HTTPClient: replyHTTP{`{"Item":{"id":{"S":"k"}}}`}})
	l := &live{s: settings{table: "t"}}
	var arrived time.Time
	item, err := l.getAt(context.Background(), c, map[string]types.AttributeValue{"id": str("k")}, &arrived)
	returned := time.Now()
	if err != nil || canon(item) != canon(map[string]types.AttributeValue{"id": str("k")}) {
		t.Fatalf("getAt = %v %v", item, err)
	}
	if arrived.IsZero() || returned.Sub(arrived) < 90*time.Millisecond {
		t.Fatalf("arrival %v is not before the body was decoded (returned %v)", arrived, returned)
	}
}

func TestOvertook(t *testing.T) {
	miss := time.Now()
	for _, tc := range []struct {
		hit  time.Time
		want bool
	}{
		{miss.Add(-5 * time.Millisecond), true},
		{miss.Add(-overtakeMargin), false},
		{miss.Add(-100 * time.Microsecond), false},
		{miss.Add(time.Millisecond), false},
		{time.Time{}, false},
	} {
		if got := overtook(tc.hit, miss); got != tc.want {
			t.Errorf("overtook(miss%+v) = %v", tc.hit.Sub(miss), got)
		}
	}
	if overtook(miss.Add(-5*time.Millisecond), time.Time{}) {
		t.Error("a missing miss arrival counts")
	}
}

func TestRelayUnreachableTarget(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	target := ln.Addr().String()
	_ = ln.Close()
	r, err := startRelay("127.0.0.1:0", target)
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	c, err := net.Dial("tcp", r.addr())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := roundTrip(c, "x"); err == nil {
		t.Fatal("a connection to an unreachable target works")
	}
}
