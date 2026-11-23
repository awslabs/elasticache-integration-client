// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"math"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"
)

func validOptions() Options {
	return Options{Endpoint: "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379", CacheName: "my-cache"}
}

func durationPtr(d time.Duration) *time.Duration { return &d }

// mustConfigError checks err is a *ConfigError for option whose message contains want.
func mustConfigError(t *testing.T, err error, option, want string) *ConfigError {
	t.Helper()
	var ce *ConfigError
	if !errors.As(err, &ce) {
		t.Fatalf("err = %v, want *ConfigError", err)
	}
	if ce.Option != option || !strings.Contains(ce.Error(), want) {
		t.Fatalf("err = %q (option %q), want option %q containing %q", ce.Error(), ce.Option, option, want)
	}
	return ce
}

func TestValidateDefaults(t *testing.T) {
	c, err := validOptions().validate()
	if err != nil {
		t.Fatal(err)
	}
	want := &config{
		endpoint:                "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
		host:                    "my-cache-abc123.serverless.use1.cache.amazonaws.com",
		port:                    6379,
		cacheName:               "my-cache",
		userID:                  "default.iam-user",
		cacheRequestTimeout:     5 * time.Second,
		connectionTimeout:       5 * time.Second,
		connections:             1,
		fallbackUnlimited:       true,
		cachePolicyTimeout:      time.Hour,
		iamTokenRefreshDelay:    300 * time.Second,
		maxPending:              100,
		maxResponseSize:         16 << 20,
		readSize:                64 << 10,
		reauthInterval:          900 * time.Second,
		reconnectAttemptTimeout: 5 * time.Second,
		backoffMin:              100 * time.Millisecond,
		backoffMax:              3200 * time.Millisecond,
		jitter:                  0.2,
	}
	if c.logger == nil {
		t.Fatal("no default logger")
	}
	c.logger = nil
	if !reflect.DeepEqual(c, want) {
		t.Fatalf("config =\n%+v\nwant\n%+v", c, want)
	}
	if c.strict() {
		t.Fatal("default config is strict")
	}
}

func TestValidateSetValues(t *testing.T) {
	o := validOptions()
	o.CacheName = "My-Cache"
	o.UserID = "Default.My-User"
	o.CacheRequestTimeout = 250 * time.Millisecond
	o.ConnectionTimeout = 2 * time.Second
	o.FallbackDuration = durationPtr(30 * time.Second)
	o.CachePolicyTimeout = 10 * time.Minute
	o.IAMTokenRefreshDelay = 879 * time.Second
	c, err := o.validate()
	if err != nil {
		t.Fatal(err)
	}
	if c.cacheName != "my-cache" || c.userID != "default.my-user" {
		t.Errorf("names not lowercased: %q %q", c.cacheName, c.userID)
	}
	if c.cacheRequestTimeout != 250*time.Millisecond || c.connectionTimeout != 2*time.Second ||
		c.cachePolicyTimeout != 10*time.Minute || c.iamTokenRefreshDelay != 879*time.Second {
		t.Errorf("durations not kept: %+v", c)
	}
	if c.fallbackUnlimited || c.fallbackDuration != 30*time.Second || c.strict() {
		t.Errorf("fallback = %v %v, strict %v", c.fallbackUnlimited, c.fallbackDuration, c.strict())
	}
}

func TestValidateStrictAndFallbackCopy(t *testing.T) {
	o := validOptions()
	d := time.Duration(0)
	o.FallbackDuration = &d
	c, err := o.validate()
	if err != nil {
		t.Fatal(err)
	}
	d = time.Minute // a later write through the caller's pointer changes nothing
	if !c.strict() || c.fallbackDuration != 0 {
		t.Fatalf("strict() = %v, fallbackDuration = %v", c.strict(), c.fallbackDuration)
	}
}

func TestValidateEndpoint(t *testing.T) {
	ok := map[string]struct {
		host string
		port int
	}{
		"host:6379":         {"host", 6379},
		"10.0.0.1:1":        {"10.0.0.1", 1},
		"[::1]:65535":       {"::1", 65535},
		"[fe80::1%eth0]:80": {"fe80::1%eth0", 80},
		"host:006379":       {"host", 6379},
	}
	for endpoint, want := range ok {
		o := validOptions()
		o.Endpoint = endpoint
		c, err := o.validate()
		if err != nil {
			t.Errorf("%q: %v", endpoint, err)
			continue
		}
		if c.host != want.host || c.port != want.port {
			t.Errorf("%q: got %q %d", endpoint, c.host, c.port)
		}
	}
	bad := map[string]string{
		"":                 "Endpoint is required",
		"host":             "is not host:port",
		"::1:6379":         "is not host:port",
		":6379":            "has an empty host",
		"host:":            "needs a port",
		"host:0":           "needs a port",
		"host:65536":       "needs a port",
		"host:+6379":       "needs a port",
		"host:-1":          "needs a port",
		"host:63 79":       "needs a port",
		"host:99999999999": "needs a port",
	}
	for endpoint, want := range bad {
		o := validOptions()
		o.Endpoint = endpoint
		_, err := o.validate()
		mustConfigError(t, err, "Endpoint", want)
	}
}

func TestValidateNames(t *testing.T) {
	for _, tc := range []struct {
		option, value string
		ok            bool
	}{
		{"CacheName", "", false},
		{"CacheName", "a", true},
		{"CacheName", "my-cache-1", true},
		{"CacheName", "1cache", false},
		{"CacheName", "my--cache", false},
		{"CacheName", "cache-", false},
		{"CacheName", "my_cache", false},
		{"CacheName", "MY-CACHE", true},
		{"CacheName", "cache\n", false},
		{"CacheName", " cache", false},
		{"UserID", "", true},
		{"UserID", "default.iam-user", true},
		{"UserID", "myuser-1", true},
		{"UserID", "default.", false},
		{"UserID", "other.user", false},
		{"UserID", "1user", false},
		{"UserID", "user\n", false},
	} {
		o := validOptions()
		if tc.option == "CacheName" {
			o.CacheName = tc.value
		} else {
			o.UserID = tc.value
		}
		_, err := o.validate()
		if tc.ok {
			if err != nil {
				t.Errorf("%s %q: %v", tc.option, tc.value, err)
			}
			continue
		}
		ce := mustConfigError(t, err, tc.option, tc.option)
		if tc.value != "" && strings.Contains(ce.Error(), tc.value) {
			t.Errorf("%s error echoes the value: %q", tc.option, ce.Error())
		}
	}
}

func TestValidateDurations(t *testing.T) {
	type setter func(*Options, time.Duration)
	millis := map[string]setter{
		"CacheRequestTimeout": func(o *Options, d time.Duration) { o.CacheRequestTimeout = d },
		"ConnectionTimeout":   func(o *Options, d time.Duration) { o.ConnectionTimeout = d },
	}
	for name, set := range millis {
		for _, d := range []time.Duration{time.Millisecond, (1<<31 - 1) * time.Millisecond} {
			o := validOptions()
			set(&o, d)
			if _, err := o.validate(); err != nil {
				t.Errorf("%s %v: %v", name, d, err)
			}
		}
		for _, d := range []time.Duration{-time.Millisecond, time.Microsecond, 1500 * time.Microsecond, (1 << 31) * time.Millisecond} {
			o := validOptions()
			set(&o, d)
			_, err := o.validate()
			mustConfigError(t, err, name, "whole number of milliseconds")
		}
	}

	seconds := map[string]struct {
		set setter
		max time.Duration
	}{
		"CachePolicyTimeout":   {func(o *Options, d time.Duration) { o.CachePolicyTimeout = d }, 0},
		"IAMTokenRefreshDelay": {func(o *Options, d time.Duration) { o.IAMTokenRefreshDelay = d }, 879 * time.Second},
	}
	for name, tc := range seconds {
		good := []time.Duration{time.Second, 24 * time.Hour}
		bad := []time.Duration{-time.Second, time.Millisecond, 1500 * time.Millisecond}
		if tc.max > 0 {
			good = []time.Duration{time.Second, tc.max}
			bad = append(bad, tc.max+time.Second)
		}
		for _, d := range good {
			o := validOptions()
			tc.set(&o, d)
			if _, err := o.validate(); err != nil {
				t.Errorf("%s %v: %v", name, d, err)
			}
		}
		for _, d := range bad {
			o := validOptions()
			tc.set(&o, d)
			_, err := o.validate()
			mustConfigError(t, err, name, "whole number of seconds")
		}
	}

	o := validOptions()
	o.IAMTokenRefreshDelay = 880 * time.Second
	_, err := o.validate()
	mustConfigError(t, err, "IAMTokenRefreshDelay", "from 1s to 879s")

	for _, d := range []time.Duration{-time.Second, 500 * time.Millisecond} {
		o := validOptions()
		o.FallbackDuration = durationPtr(d)
		_, err := o.validate()
		mustConfigError(t, err, "FallbackDuration", "whole number of seconds")
	}
}

func TestValidateConnections(t *testing.T) {
	for _, n := range []int{-1, 17} {
		o := validOptions()
		o.Connections = n
		_, err := o.validate()
		mustConfigError(t, err, "Connections", "from 1 to 16")
	}
	for give, want := range map[int]int{0: 1, 1: 1, 4: 4, 16: 16} {
		o := validOptions()
		o.Connections = give
		c, err := o.validate()
		if err != nil || c.connections != want {
			t.Fatalf("Connections %d: %v, %v; want %d", give, c, err, want)
		}
	}
}

func TestValidateOverridesDeepCopy(t *testing.T) {
	type myString string
	inner := map[string]any{"ttl": 60}
	list := []any{"a", int8(-1)}
	o := validOptions()
	o.CachePolicyOverrides = map[string]any{
		"version": "2.0",
		"big":     uint64(math.MaxUint64),
		"neg":     int64(math.MinInt64),
		"f32":     float32(0.1),
		"f64":     1.5,
		"num":     json.Number("12345678901234567890"),
		"named":   myString("x"),
		"flag":    true,
		"none":    nil,
		"nested":  inner,
		"list":    list,
		"typed":   map[string][]int{"a": {1, 2}},
		"array":   [2]string{"x", "y"},
		"nilmap":  map[string]any(nil),
		"nilList": []string(nil),
	}
	c, err := o.validate()
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]any{
		"version": "2.0",
		"big":     uint64(math.MaxUint64),
		"neg":     int64(math.MinInt64),
		"f32":     float32(0.1),
		"f64":     1.5,
		"num":     json.Number("12345678901234567890"),
		"named":   "x",
		"flag":    true,
		"none":    nil,
		"nested":  map[string]any{"ttl": int64(60)},
		"list":    []any{"a", int64(-1)},
		"typed":   map[string]any{"a": []any{int64(1), int64(2)}},
		"array":   []any{"x", "y"},
		"nilmap":  nil,
		"nilList": nil,
	}
	if !reflect.DeepEqual(c.cachePolicyOverrides, want) {
		t.Fatalf("copy =\n%#v\nwant\n%#v", c.cachePolicyOverrides, want)
	}
	inner["ttl"] = 0
	list[0] = "changed"
	o.CachePolicyOverrides["version"] = "3.0"
	if !reflect.DeepEqual(c.cachePolicyOverrides, want) {
		t.Fatal("a change to the caller's map reached the copy")
	}
}

type (
	textEnum string
	jsonEnum int
	myKey    string
)

func (e textEnum) MarshalText() ([]byte, error) { return []byte("T" + string(e)), nil }
func (e jsonEnum) MarshalJSON() ([]byte, error) { return []byte(`"J"`), nil }

func TestValidateOverridesInterfaceKeys(t *testing.T) {
	o := validOptions()
	o.CachePolicyOverrides = map[string]any{
		"table_overrides": map[any]any{"Users": map[any]any{"item_ttl": 600}},
	}
	c, err := o.validate()
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]any{"table_overrides": map[string]any{"Users": map[string]any{"item_ttl": int64(600)}}}
	if !reflect.DeepEqual(c.cachePolicyOverrides, want) {
		t.Fatalf("copy = %#v, want %#v", c.cachePolicyOverrides, want)
	}
}

func TestValidateOverridesNilAndEmpty(t *testing.T) {
	c, err := validOptions().validate()
	if err != nil || c.cachePolicyOverrides != nil {
		t.Fatalf("nil overrides: %v %v", c, err)
	}
	o := validOptions()
	o.CachePolicyOverrides = map[string]any{}
	c, err = o.validate()
	if err != nil || c.cachePolicyOverrides == nil || len(c.cachePolicyOverrides) != 0 {
		t.Fatalf("empty overrides: %v %v", c.cachePolicyOverrides, err)
	}
}

func TestValidateOverridesRefused(t *testing.T) {
	cyclic := map[string]any{}
	cyclic["self"] = cyclic
	cyclicList := make([]any, 1)
	cyclicList[0] = cyclicList
	shared := map[string]any{"k": 1}
	ttl := 5
	for _, tc := range []struct {
		value  any
		option string
		want   string
	}{
		{math.NaN(), "CachePolicyOverrides.v", "finite"},
		{math.Inf(1), "CachePolicyOverrides.v", "finite"},
		{float32(math.Inf(-1)), "CachePolicyOverrides.v", "finite"},
		{json.Number("1e"), "CachePolicyOverrides.v", "valid JSON number"},
		{json.Number("abc"), "CachePolicyOverrides.v", "valid JSON number"},
		{"\xff", "CachePolicyOverrides.v", "UTF-8"},
		{map[string]any{"\xff": 1}, "CachePolicyOverrides.v", "UTF-8 keys"},
		{map[int]any{1: 1}, "CachePolicyOverrides.v", "string keys"},
		{[]byte("x"), "CachePolicyOverrides.v", "byte slice"},
		{&ttl, "CachePolicyOverrides.v", "got *int"},
		{struct{}{}, "CachePolicyOverrides.v", "got struct {}"},
		{func() {}, "CachePolicyOverrides.v", "got func()"},
		{make(chan int), "CachePolicyOverrides.v", "got chan int"},
		{cyclic, "CachePolicyOverrides.v.self", "contain itself"},
		{cyclicList, "CachePolicyOverrides.v[0]", "contain itself"},
		{[]any{1, []any{"x", time.Time{}}}, "CachePolicyOverrides.v[1][1]", "not a time.Time"},
		{10 * time.Minute, "CachePolicyOverrides.v", "time.Duration"},
		{map[string]any{"item_ttl": time.Second}, "CachePolicyOverrides.v.item_ttl", "nanoseconds"},
		{textEnum("x"), "CachePolicyOverrides.v", "own JSON or text encoding"},
		{jsonEnum(1), "CachePolicyOverrides.v", "own JSON or text encoding"},
		{map[any]any{"a": 1, 2: 1}, "CachePolicyOverrides.v", "key of type int"},
		{map[any]any{nil: 1}, "CachePolicyOverrides.v", "key of type nil"},
		{map[any]any{myKey("a"): 1}, "CachePolicyOverrides.v", "key of type elasticacheintegrations.myKey"},
	} {
		o := validOptions()
		o.CachePolicyOverrides = map[string]any{"v": tc.value}
		_, err := o.validate()
		mustConfigError(t, err, tc.option, tc.want)
	}

	// The same map twice, not nested in itself, is not a cycle.
	o := validOptions()
	o.CachePolicyOverrides = map[string]any{"a": shared, "b": []any{shared, shared}}
	if _, err := o.validate(); err != nil {
		t.Fatal(err)
	}
}

func TestValidateCACertsFile(t *testing.T) {
	dir := t.TempDir()
	good := filepath.Join(dir, "ca.pem")
	if err := os.WriteFile(good, selfSignedPEM(t), 0o600); err != nil {
		t.Fatal(err)
	}
	empty := filepath.Join(dir, "empty.pem")
	if err := os.WriteFile(empty, []byte("not a certificate\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	o := validOptions()
	o.CACertsFile = good
	c, err := o.validate()
	if err != nil || c.rootCAs == nil {
		t.Fatalf("good file: %v %v", c, err)
	}

	o.CACertsFile = empty
	_, err = o.validate()
	mustConfigError(t, err, "CACertsFile", "holds no PEM certificate")

	o.CACertsFile = filepath.Join(dir, "missing.pem")
	_, err = o.validate()
	ce := mustConfigError(t, err, "CACertsFile", "cannot be read")
	if !errors.Is(ce, os.ErrNotExist) {
		t.Fatalf("missing file error does not wrap os.ErrNotExist: %v", ce)
	}

	o.CACertsFile = dir
	_, err = o.validate()
	mustConfigError(t, err, "CACertsFile", "not a regular file")

	big := filepath.Join(dir, "big.pem")
	if err := os.WriteFile(big, append(selfSignedPEM(t), make([]byte, maxCACertsSize)...), 0o600); err != nil {
		t.Fatal(err)
	}
	o.CACertsFile = big
	_, err = o.validate()
	mustConfigError(t, err, "CACertsFile", "larger than")

	o.CACertsFile = good
	o.InsecureSkipVerify = true
	_, err = o.validate()
	mustConfigError(t, err, "CACertsFile", "InsecureSkipVerify")

	o.CACertsFile = ""
	if c, err = o.validate(); err != nil || !c.insecureSkipVerify || c.rootCAs != nil {
		t.Fatalf("InsecureSkipVerify alone: %v %v", c, err)
	}
}

func selfSignedPEM(t *testing.T) []byte {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "test-ca"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		IsCA:         true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
}

func FuzzParseEndpoint(f *testing.F) {
	for _, s := range []string{"host:6379", "[::1]:1", "[fe80::1%eth0]:80", "host:006379", "::1:6379", ":6379", "host:+1", "[a]:1", "h:65535"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, endpoint string) {
		host, port, err := parseEndpoint(endpoint)
		if err != nil {
			var ce *ConfigError
			if !errors.As(err, &ce) || ce.Option != "Endpoint" {
				t.Fatalf("%q: err %v is not an Endpoint *ConfigError", endpoint, err)
			}
			return
		}
		if host == "" || port < 1 || port > 65535 {
			t.Fatalf("%q: accepted host %q port %d", endpoint, host, port)
		}
		if h, p, err := net.SplitHostPort(net.JoinHostPort(host, strconv.Itoa(port))); err != nil || h != host || p != strconv.Itoa(port) {
			t.Fatalf("%q: host %q port %d do not round-trip", endpoint, host, port)
		}
	})
}

func FuzzCopyOverrides(f *testing.F) {
	for _, s := range []string{`{"item_ttl":60}`, `{"a":[1,"x",null,true,{"b":1.5e300}]}`, `{"version":"2.0","n":12345678901234567890}`} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, text string) {
		var in map[string]any
		d := json.NewDecoder(strings.NewReader(text))
		d.UseNumber()
		if d.Decode(&in) != nil || in == nil {
			return
		}
		out, err := copyOverrides(in)
		if err != nil {
			// encoding/json output is always a valid override.
			t.Fatalf("%q: %v", text, err)
		}
		if !reflect.DeepEqual(out, in) {
			t.Fatalf("%q: copy %#v differs from %#v", text, out, in)
		}
	})
}
