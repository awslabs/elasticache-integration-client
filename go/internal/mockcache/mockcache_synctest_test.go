// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build go1.25

package mockcache

import (
	"context"
	"crypto/tls"
	"io"
	"testing"
	"testing/synctest"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

func synctestClient(t *testing.T, s *Server) *client {
	t.Helper()
	conn, err := s.Dial(context.Background(), "tcp", "")
	if err != nil {
		t.Fatal(err)
	}
	conn = tls.Client(conn, &tls.Config{RootCAs: s.CertPool(), ServerName: "localhost"})
	c := newClient(t, conn)
	c.wait = 2 * time.Hour
	c.ready()
	return c
}

// The server runs on fake time when it is made inside the bubble.
func TestSynctest(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, err := New(Config{TLS: true})
		if err != nil {
			t.Fatal(err)
		}
		synctestServe(t, s)
	})
}

func synctestServe(t *testing.T, s *Server) {
	t.Helper()
	s.SetOnRequest(func(cmd Command) Reply {
		if cmd.Arg(2) == "deferred" {
			return DeferredAfter(30*time.Minute, resp.IntValue(2))
		}
		return After(time.Hour, resp.IntValue(1))
	})
	c := synctestClient(t, s)
	start := time.Now()
	if v := c.request("after"); v.Int != 1 || time.Since(start) != time.Hour {
		t.Fatalf("After: %v after %v", v, time.Since(start))
	}
	c.request("deferred")
	start = time.Now()
	if v := c.next(); v.Kind != resp.KindPush || time.Since(start) != 30*time.Minute {
		t.Fatalf("DeferredAfter: %v after %v", v, time.Since(start))
	}
	// The certificate outlasts days of fake time.
	time.Sleep(72 * time.Hour)
	synctestClient(t, s)
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
}

// DropAll waits for a write the client has not read yet: the client then reads the whole
// frame and the end of the stream.
func TestDropAllWaitsForAWriteInProgress(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, err := New(Config{})
		if err != nil {
			t.Fatal(err)
		}
		conn, err := s.Dial(context.Background(), "tcp", "")
		if err != nil {
			t.Fatal(err)
		}
		c := newClient(t, conn)
		c.wait = 2 * time.Hour
		c.ready()
		big := resp.AppendBulk(nil, make([]byte, 1<<20))
		wrote := make(chan error, 1)
		go func() { wrote <- s.WriteRaw(big) }()
		synctest.Wait()
		dropped := make(chan struct{})
		go func() { s.DropAll(); close(dropped) }()
		synctest.Wait()
		got, err := io.ReadAll(conn)
		if err != nil || len(got) != len(big) {
			t.Fatalf("read %d bytes, err %v; want %d bytes and the end of the stream", len(got), err, len(big))
		}
		if err := <-wrote; err != nil {
			t.Fatalf("WriteRaw() = %v, want nil", err)
		}
		<-dropped
		if err := s.Close(); err != nil {
			t.Fatal(err)
		}
	})
}
