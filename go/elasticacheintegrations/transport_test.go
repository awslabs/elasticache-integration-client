// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"log/slog"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// startMock starts a mock cache on loopback and closes it after the test, failing the test on
// a server failure.
func startMock(t *testing.T, c mockcache.Config) (*mockcache.Server, string) {
	t.Helper()
	s, err := mockcache.New(c)
	if err != nil {
		t.Fatal(err)
	}
	addr, err := s.Listen()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := s.Close(); err != nil {
			t.Errorf("mock cache: %v", err)
		}
	})
	return s, addr
}

// mockConfig is a validated config for endpoint, trusting the mock's certificate when it has
// one, and plaintext otherwise.
func mockConfig(t *testing.T, s *mockcache.Server, endpoint string) *config {
	t.Helper()
	c, err := Options{Endpoint: endpoint, CacheName: "my-cache", Logger: slog.New(slog.DiscardHandler)}.validate()
	if err != nil {
		t.Fatal(err)
	}
	if s != nil && s.CertPool() != nil {
		c.rootCAs = s.CertPool()
	} else {
		c.plaintext = true
	}
	return c
}

func TestDialCacheTLS(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{TLS: true})
	_, port, _ := net.SplitHostPort(addr)
	for _, host := range []string{"127.0.0.1", "localhost"} {
		c := mockConfig(t, s, net.JoinHostPort(host, port))
		conn, err := dialCache(context.Background(), c)
		if err != nil {
			t.Fatalf("%s: %v", host, err)
		}
		state := conn.(*tls.Conn).ConnectionState()
		if !state.HandshakeComplete || state.Version < tls.VersionTLS12 || state.ServerName != hostSNI(host) {
			t.Errorf("%s: state %+v", host, state)
		}
		_ = conn.Close()
	}
}

// hostSNI is the SNI Go sends for host: none for an IP literal.
func hostSNI(host string) string {
	if net.ParseIP(host) != nil {
		return ""
	}
	return host
}

func TestDialCacheRefusesBadCertificates(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{TLS: true})
	toMock := func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	}

	wrongName := mockConfig(t, s, "example.com:6379")
	wrongName.dial = toMock
	_, err := dialCache(context.Background(), wrongName)
	var hostErr x509.HostnameError
	var ce *ConnectionError
	if !errors.As(err, &ce) || !errors.As(err, &hostErr) || !strings.Contains(err.Error(), "TLS handshake with example.com:6379 failed") {
		t.Fatalf("wrong name: %v", err)
	}

	untrusted := mockConfig(t, s, addr)
	untrusted.rootCAs = nil // system roots
	_, err = dialCache(context.Background(), untrusted)
	var authErr x509.UnknownAuthorityError
	if !errors.As(err, &ce) || !errors.As(err, &authErr) {
		t.Fatalf("untrusted: %v", err)
	}
	if errors.As(err, new(*TimeoutError)) {
		t.Fatalf("a refused certificate reads as a timeout: %v", err)
	}
}

func TestDialCacheInsecureSkipVerifyWarnsOnce(t *testing.T) {
	insecureSkipVerifyWarned.Store(false)
	t.Cleanup(func() { insecureSkipVerifyWarned.Store(false) })
	s, addr := startMock(t, mockcache.Config{TLS: true})
	logs, logger := testutil.NewLogCapture()
	c := mockConfig(t, s, addr)
	c.rootCAs = nil
	c.insecureSkipVerify = true
	c.logger = newLogger(logger)
	for range 2 {
		conn, err := dialCache(context.Background(), c)
		if err != nil {
			t.Fatal(err)
		}
		_ = conn.Close()
	}
	var warnings int
	for _, r := range logs.Records() {
		if v, _ := testutil.Attr(r, "event"); v.Int64() == int64(eventInsecureSkipVerify) {
			warnings++
		}
	}
	if warnings != 1 {
		t.Fatalf("%d insecure warnings, want 1", warnings)
	}
}

func TestDialCacheTimesOut(t *testing.T) {
	s, addr := startMock(t, mockcache.Config{TLS: true})
	s.Hold()
	defer s.Release()
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err := dialCache(ctx, mockConfig(t, s, addr))
	var ce *ConnectionError
	var te *TimeoutError
	if !errors.As(err, &ce) || !errors.As(err, &te) || !strings.Contains(ce.Msg, "TLS handshake") || !strings.Contains(ce.Msg, "timed out") {
		t.Fatalf("held handshake: %v", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("took %v", time.Since(start))
	}
}

func TestDialCacheRefusedPort(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	_ = l.Close()
	_, err = dialCache(context.Background(), mockConfig(t, nil, addr))
	var ce *ConnectionError
	if !errors.As(err, &ce) || !strings.Contains(ce.Msg, "connect to "+addr+" failed") || errors.As(err, new(*TimeoutError)) {
		t.Fatalf("refused port: %v", err)
	}
	if !errors.Is(err, ErrIntegrations) {
		t.Fatal("not an ErrIntegrations")
	}
}

func TestDialCachePlaintextAndCustomDial(t *testing.T) {
	s, err := mockcache.New(mockcache.Config{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	c := mockConfig(t, s, "cache.example:6379")
	var dialed string
	c.dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		dialed = network + " " + address
		return s.Dial(ctx, network, address)
	}
	conn, err := dialCache(context.Background(), c)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, ok := conn.(*tls.Conn); ok || dialed != "tcp cache.example:6379" {
		t.Fatalf("conn %T, dialed %q", conn, dialed)
	}
}

func TestTLSConfig(t *testing.T) {
	c := mockConfig(t, nil, "[::1]:6379")
	tc := tlsConfig(c)
	if tc.ServerName != "::1" || tc.MinVersion != tls.VersionTLS12 || tc.InsecureSkipVerify || tc.RootCAs != nil {
		t.Fatalf("%+v", tc)
	}
}
