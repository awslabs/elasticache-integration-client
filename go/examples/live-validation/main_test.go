// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"io"
	"log/slog"
	"strings"
	"sync"
	"testing"

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
