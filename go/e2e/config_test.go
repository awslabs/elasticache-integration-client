// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"context"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/elasticacheintegrations"
	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// routeEverything routes the ten operations and refreshes every second.
const routeEverything = `{"version":"1.0","refresh_interval":1,"predicates":[{"expr":"service == 'dynamodb' && operation in ops","vars":{"ops":["GetItem","BatchGetItem","Query","Scan","PutItem","UpdateItem","DeleteItem","BatchWriteItem","TransactGetItems","TransactWriteItems"]}}]}`

// routeNothing is a config with no matching predicate, refreshed every second.
const routeNothing = `{"version":"1.0","refresh_interval":1,"predicates":[]}`

// getOne reads Acme/One and fails the test on an error.
func (h *harness) getOne(t *testing.T) *dynamodb.GetItemOutput {
	t.Helper()
	out, err := h.ddb.GetItem(context.Background(), &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: songKey("Acme", "One")})
	if h.seen(err) != nil {
		t.Fatal(err)
	}
	return out
}

// commands counts the commands named name (upper case) with n arguments after the name.
func (h *harness) commands(name string, n int) int {
	count := 0
	for _, cmd := range h.cache.Commands() {
		if cmd.Name() == name && len(cmd.Args) == n+1 {
			count++
		}
	}
	return count
}

// countingClient is an application's own HTTP client: it counts its calls.
type countingClient struct {
	next  *http.Client
	calls atomic.Int64
}

func (c *countingClient) Do(r *http.Request) (*http.Response, error) {
	c.calls.Add(1)
	return c.next.Do(r)
}

func TestOverridesReachTheCache(t *testing.T) {
	t.Parallel()
	t.Run("nested", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t, withOptions(func(o *elasticacheintegrations.Options) {
			o.CachePolicyOverrides = map[string]any{"item_ttl": 300, "cached_tables": []any{"Music"},
				"nested": map[string]any{"b": 1, "a": "x"}}
		}))
		want := `{"version":"1.0","cached_tables":["Music"],"item_ttl":300,"nested":{"a":"x","b":1}}`
		if got := h.cache.Overrides(); len(got) != 1 || got[0] != want {
			t.Fatalf("overrides sent %q, want [%s]", got, want)
		}
	})
	t.Run("none", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t)
		if got := h.cache.Overrides(); len(got) != 1 || got[0] != `{"version":"1.0"}` {
			t.Fatalf("overrides sent %q, want [{\"version\":\"1.0\"}]", got)
		}
	})
	t.Run("copied", func(t *testing.T) {
		t.Parallel()
		tables := []any{"Music"}
		overrides := map[string]any{"cached_tables": tables}
		h := newHarness(t, withOptions(func(o *elasticacheintegrations.Options) { o.CachePolicyOverrides = overrides }))
		tables[0] = "Changed"
		overrides["item_ttl"] = 1
		h.cache.DropAll()
		eventually(t, "the reconnect", func() bool { return h.cache.Connections() == 2 })
		want := `{"version":"1.0","cached_tables":["Music"]}`
		if got := h.cache.Overrides(); len(got) != 2 || got[0] != want || got[1] != want {
			t.Fatalf("overrides sent %q, want %s twice", got, want)
		}
	})
}

func TestOverridesRejectedFailsStartAtOnce(t *testing.T) {
	t.Parallel()
	h := newHarness(t, startMayFail(),
		withOptions(func(o *elasticacheintegrations.Options) { o.ConnectionTimeout = 10 * time.Second }),
		withCache(func(s *mockcache.Server) {
			s.SetIntercept(func(cmd mockcache.Command) *mockcache.Reply {
				if cmd.Name() == "AWS.CONFIG" && len(cmd.Args) == 2 {
					r := mockcache.Now(resp.ErrorValue("CONFIG_INVALID", "unknown key item_ttl_x"))
					return &r
				}
				return nil
			})
		}))
	errorAs[*elasticacheintegrations.OverridesRejectedError](t, h.startErr)
	if h.ddb != nil || h.client != nil {
		t.Error("NewFromConfig returned a client with its error")
	}
	if n := h.commands("AWS.CONFIG", 1); n != 1 {
		t.Errorf("aws.config sent %d times, want 1", n)
	}
	if h.started > 5*time.Second {
		t.Errorf("start-up took %v; a rejection must not wait for ConnectionTimeout", h.started)
	}
}

func TestPredicatesChooseWhatIsRouted(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	t.Run("table_allowlist", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t, withConfig(`{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"service == 'dynamodb' && operation in ['GetItem', 'PutItem'] && body.TableName == 'Music'"}]}`))
		seed(t, h.dynamo)
		h.getOne(t)
		if _, err := h.ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String("Music"),
			Item: map[string]types.AttributeValue{"Artist": sv("New"), "Song": sv("S")}}); h.seen(err) != nil {
			t.Fatal(err)
		}
		if h.requests() != 2 || h.dynamo.fromClient() != 0 {
			t.Fatalf("aws.requests %d, client calls %d; want 2 and 0", h.requests(), h.dynamo.fromClient())
		}
		out, err := h.ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Other"),
			Key: map[string]types.AttributeValue{"Id": sv("1")}})
		if h.seen(err) != nil || out.Item == nil {
			t.Fatalf("GetItem Other = %v, %v", out, err)
		}
		if _, err := h.ddb.Scan(ctx, &dynamodb.ScanInput{TableName: aws.String("Music")}); h.seen(err) != nil {
			t.Fatal(err)
		}
		if h.requests() != 2 || h.dynamo.fromClient() != 2 {
			t.Errorf("aws.requests %d, client calls %d; want 2 and 2", h.requests(), h.dynamo.fromClient())
		}
		for _, c := range h.dynamo.clientCalls() {
			if !strings.HasPrefix(c.header.Get("Authorization"), "AWS4-HMAC-SHA256 Credential=") {
				t.Errorf("%s reached DynamoDB unsigned", c.op)
			}
		}
	})
	t.Run("own_http_client", func(t *testing.T) {
		t.Parallel()
		own := &countingClient{next: http.DefaultClient}
		h := newHarness(t, withSDKOptions(func(o *dynamodb.Options) { o.HTTPClient = own }),
			withConfig(`{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"operation == 'GetItem'"}]}`))
		seed(t, h.dynamo)
		h.getOne(t)
		if own.calls.Load() != 0 {
			t.Errorf("a routed call went through the application's HTTP client")
		}
		if _, err := h.ddb.Scan(ctx, &dynamodb.ScanInput{TableName: aws.String("Music")}); h.seen(err) != nil {
			t.Fatal(err)
		}
		if own.calls.Load() != 1 || h.dynamo.fromClient() != 1 || h.requests() != 1 {
			t.Errorf("application client calls %d, DynamoDB client calls %d, aws.requests %d; want 1 each",
				own.calls.Load(), h.dynamo.fromClient(), h.requests())
		}
	})
	t.Run("no_predicates", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t, withConfig(`{"version":"1.0","refresh_interval":60}`))
		seed(t, h.dynamo)
		if h.getOne(t).Item == nil {
			t.Fatal("no item")
		}
		if h.requests() != 0 || h.dynamo.fromClient() != 1 {
			t.Errorf("aws.requests %d, client calls %d; want 0 and 1", h.requests(), h.dynamo.fromClient())
		}
	})
}

func TestUnusableConfigFailsStart(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name, config string
		many         bool // retried until ConnectionTimeout
		check        func(*testing.T, error)
	}{
		{"no_refresh_interval", `{"version":"1.0","predicates":[]}`, true, func(t *testing.T, err error) {
			errorAs[*elasticacheintegrations.ProtocolError](t, err)
		}},
		{"zero_refresh_interval", `{"version":"1.0","refresh_interval":0,"predicates":[]}`, true, func(t *testing.T, err error) {
			errorAs[*elasticacheintegrations.ProtocolError](t, err)
		}},
		{"predicates_not_a_list", `{"version":"1.0","refresh_interval":60,"predicates":"all"}`, false, func(t *testing.T, err error) {
			errorAs[*elasticacheintegrations.ConfigUnevaluableError](t, err)
		}},
		{"predicate_does_not_compile", `{"version":"1.0","refresh_interval":60,"predicates":[{"expr":"operation =="}]}`, false, func(t *testing.T, err error) {
			errorAs[*elasticacheintegrations.ConfigUnevaluableError](t, err)
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			h := newHarness(t, startMayFail(), withConfig(tc.config),
				// Long enough that a slow attempt under load is not cut by the deadline, which
				// would make the error a ConnectionError.
				withOptions(func(o *elasticacheintegrations.Options) { o.ConnectionTimeout = 2 * time.Second }))
			if h.startErr == nil {
				t.Fatal("NewFromConfig accepted the config")
			}
			if !tc.many || !cutByDeadline(t, h) {
				tc.check(t, h.startErr)
			}
			if n := h.commands("HELLO", 4); (n > 1) != tc.many {
				t.Errorf("%d connection attempts; retried = %v, want %v", n, n > 1, tc.many)
			}
			if h.dynamo.fromClient() != 0 {
				t.Error("start-up called DynamoDB")
			}
		})
	}
}

// cutByDeadline reports whether start-up failed because its last attempt ran into the
// ConnectionTimeout deadline after an earlier attempt got the config: the client returns the
// last attempt's error, as Java does, so under load a retried config error can end as a
// timeout.
func cutByDeadline(t *testing.T, h *harness) bool {
	t.Helper()
	if !errors.As(h.startErr, new(*elasticacheintegrations.TimeoutError)) ||
		!errors.As(h.startErr, new(*elasticacheintegrations.ConnectionError)) || h.commands("AWS.CONFIG", 1) == 0 {
		return false
	}
	t.Logf("the last attempt ran into the deadline: %v", h.startErr)
	return true
}

func TestConfigRefreshChangesRouting(t *testing.T) {
	t.Parallel()
	var phase atomic.Int32 // 0: unchanged, 1: route nothing, 2: route everything
	h := newHarness(t, withConfig(routeEverything), withCache(func(s *mockcache.Server) {
		s.SetOnRefresh(func() mockcache.Reply {
			switch phase.Load() {
			case 1:
				return mockcache.Now(resp.BulkStringValue(routeNothing))
			case 2:
				return mockcache.Now(resp.BulkStringValue(routeEverything))
			default:
				return mockcache.Now(resp.NullValue())
			}
		})
	}))
	seed(t, h.dynamo)
	eventually(t, "an unchanged refresh", func() bool { return h.commands("AWS.CONFIG", 0) >= 1 })
	h.getOne(t)
	if h.requests() != 1 || h.dynamo.fromClient() != 0 {
		t.Fatalf("after an unchanged refresh: aws.requests %d, client calls %d; want 1 and 0", h.requests(), h.dynamo.fromClient())
	}
	phase.Store(1)
	eventually(t, "a call to go to DynamoDB", func() bool {
		h.getOne(t)
		return h.dynamo.fromClient() > 0
	})
	phase.Store(2)
	routed := h.requests()
	eventually(t, "a call to go to the cache again", func() bool {
		h.getOne(t)
		return h.requests() > routed
	})
	if h.cache.Connections() != 1 {
		t.Errorf("connections = %d; a refresh must keep the connection", h.cache.Connections())
	}
}

func TestConfigRefreshFailuresReconnect(t *testing.T) {
	t.Parallel()
	t.Run("hung_refresh", func(t *testing.T) {
		t.Parallel()
		var hung atomic.Bool
		h := newHarness(t, withConfig(routeEverything),
			withOptions(func(o *elasticacheintegrations.Options) { o.CacheRequestTimeout = 300 * time.Millisecond }),
			withCache(func(s *mockcache.Server) {
				s.SetOnRefresh(func() mockcache.Reply {
					if hung.CompareAndSwap(false, true) {
						return mockcache.Hang()
					}
					return mockcache.Now(resp.NullValue())
				})
			}))
		seed(t, h.dynamo)
		eventually(t, "the reconnect after the hung refresh", func() bool { return h.cache.Connections() >= 2 })
		h.getOne(t)
		if h.dynamo.fromClient() != 0 || h.requests() == 0 {
			t.Errorf("aws.requests %d, client calls %d; want the cache to serve and no client call", h.requests(), h.dynamo.fromClient())
		}
	})
	t.Run("stale", func(t *testing.T) {
		t.Parallel()
		var failing atomic.Bool
		failing.Store(true)
		h := newHarness(t, withConfig(routeEverything),
			withOptions(func(o *elasticacheintegrations.Options) { o.CachePolicyTimeout = time.Second }),
			withCache(func(s *mockcache.Server) {
				s.SetOnRefresh(func() mockcache.Reply {
					if failing.Load() {
						return mockcache.Now(resp.ErrorValue("ERR", "refresh failed"))
					}
					return mockcache.Now(resp.NullValue())
				})
			}))
		seed(t, h.dynamo)
		h.getOne(t)
		eventually(t, "the reconnect for a current config", func() bool { return h.cache.Connections() >= 2 })
		failing.Store(false)
		h.getOne(t)
		if h.dynamo.fromClient() != 0 {
			t.Errorf("the client sent %d calls to DynamoDB", h.dynamo.fromClient())
		}
	})
}

func TestTLSVerification(t *testing.T) {
	t.Parallel()
	other, err := mockcache.New(mockcache.Config{TLS: true})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = other.Close() })
	otherCA := filepath.Join(t.TempDir(), "other.pem")
	if err := os.WriteFile(otherCA, other.CertPEM(), 0o600); err != nil {
		t.Fatal(err)
	}
	short := func(o *elasticacheintegrations.Options) { o.ConnectionTimeout = 300 * time.Millisecond }
	t.Run("another_ca", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t, startMayFail(), withOptions(func(o *elasticacheintegrations.Options) { short(o); o.CACertsFile = otherCA }))
		errorAs[*elasticacheintegrations.ConnectionError](t, h.startErr)
		if h.commands("HELLO", 4) != 0 {
			t.Error("the client sent HELLO over an unverified connection")
		}
	})
	t.Run("system_roots", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t, startMayFail(), systemRoots(), withOptions(short))
		errorAs[*elasticacheintegrations.ConnectionError](t, h.startErr)
	})
	t.Run("insecure_skip_verify", func(t *testing.T) {
		t.Parallel()
		h := newHarness(t, withOptions(func(o *elasticacheintegrations.Options) { o.InsecureSkipVerify = true }))
		seed(t, h.dynamo)
		if h.getOne(t).Item == nil || h.requests() != 1 {
			t.Fatal("not served over an unverified connection")
		}
	})
}
