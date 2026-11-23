// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"crypto/x509"
	"encoding"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net"
	"os"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

var (
	// The IAM developer toolkit's patterns, checked after lowercasing.
	cacheNamePattern = regexp.MustCompile(`^[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*$`)
	userIDPattern    = regexp.MustCompile(`^(?:default\.)?[a-zA-Z][a-zA-Z0-9\-]*$`)
	jsonNumber       = regexp.MustCompile(`^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$`)
)

// validate checks every option before any network I/O and returns the resolved config.
// Every failure is a *ConfigError naming the option.
func (o Options) validate() (*config, error) {
	c := &config{
		endpoint:                o.Endpoint,
		insecureSkipVerify:      o.InsecureSkipVerify,
		logger:                  newLogger(o.Logger),
		maxPending:              defaultMaxPending,
		maxResponseSize:         defaultMaxResponseSize,
		readSize:                defaultReadSize,
		reauthInterval:          defaultReauthInterval,
		reconnectAttemptTimeout: defaultReconnectAttempt,
		backoffMin:              defaultBackoffMin,
		backoffMax:              defaultBackoffMax,
		jitter:                  defaultJitter,
	}
	var err error
	if c.host, c.port, err = parseEndpoint(o.Endpoint); err != nil {
		return nil, err
	}
	if c.cacheName, err = checkName("CacheName", o.CacheName, "", cacheNamePattern); err != nil {
		return nil, err
	}
	if c.userID, err = checkName("UserID", o.UserID, defaultUserID, userIDPattern); err != nil {
		return nil, err
	}
	if c.cacheRequestTimeout, err = wholeMillis("CacheRequestTimeout", o.CacheRequestTimeout, defaultCacheRequestTimeout); err != nil {
		return nil, err
	}
	if c.connectionTimeout, err = wholeMillis("ConnectionTimeout", o.ConnectionTimeout, defaultConnectionTimeout); err != nil {
		return nil, err
	}
	switch {
	case o.Connections == 0:
		c.connections = defaultConnections
	case o.Connections < 0 || o.Connections > maxConnections:
		return nil, &ConfigError{Option: "Connections", Msg: fmt.Sprintf("must be from 1 to %d (0 for the default), got %d", maxConnections, o.Connections)}
	default:
		c.connections = o.Connections
	}
	if o.FallbackDuration == nil {
		c.fallbackUnlimited = true
	} else {
		d := *o.FallbackDuration
		if d < 0 || d%time.Second != 0 {
			return nil, &ConfigError{Option: "FallbackDuration",
				Msg: fmt.Sprintf("must be a whole number of seconds, 0 or more (nil for no limit), got %v", d)}
		}
		c.fallbackDuration = d
	}
	if c.cachePolicyTimeout, err = wholeSeconds("CachePolicyTimeout", o.CachePolicyTimeout, defaultCachePolicyTimeout, 0); err != nil {
		return nil, err
	}
	if c.iamTokenRefreshDelay, err = wholeSeconds("IAMTokenRefreshDelay", o.IAMTokenRefreshDelay, defaultIAMTokenRefreshDelay, maxIAMTokenRefreshDelay); err != nil {
		return nil, err
	}
	if c.cachePolicyOverrides, err = copyOverrides(o.CachePolicyOverrides); err != nil {
		return nil, err
	}
	if o.CACertsFile != "" {
		if o.InsecureSkipVerify {
			return nil, &ConfigError{Option: "CACertsFile", Msg: "cannot be set with InsecureSkipVerify, which would ignore it"}
		}
		if c.rootCAs, err = loadCACerts(o.CACertsFile); err != nil {
			return nil, err
		}
	}
	return c, nil
}

// parseEndpoint splits "host:port". The port must be decimal digits in 1-65535.
func parseEndpoint(endpoint string) (string, int, error) {
	if endpoint == "" {
		return "", 0, &ConfigError{Option: "Endpoint", Msg: "is required"}
	}
	host, portText, err := net.SplitHostPort(endpoint)
	if err != nil {
		return "", 0, &ConfigError{Option: "Endpoint", Msg: fmt.Sprintf("%q is not host:port", endpoint), Err: err}
	}
	if host == "" {
		return "", 0, &ConfigError{Option: "Endpoint", Msg: fmt.Sprintf("%q has an empty host", endpoint)}
	}
	port, err := strconv.Atoi(portText)
	if err != nil || strings.TrimLeft(portText, "0123456789") != "" || port < 1 || port > 65535 {
		return "", 0, &ConfigError{Option: "Endpoint", Msg: fmt.Sprintf("%q needs a port from 1 to 65535, in decimal digits", endpoint)}
	}
	return host, port, nil
}

// checkName lowercases a name and checks it against the toolkit pattern. The message never
// includes the value.
func checkName(option, value, def string, pattern *regexp.Regexp) (string, error) {
	if value == "" {
		if def == "" {
			return "", &ConfigError{Option: option, Msg: "is required"}
		}
		return def, nil
	}
	lower := strings.ToLower(value)
	if !pattern.MatchString(lower) {
		return "", &ConfigError{Option: option, Msg: "must match " + pattern.String()}
	}
	return lower, nil
}

// wholeMillis checks a timeout: 0 means def, otherwise whole milliseconds in [1ms, 2^31-1 ms].
func wholeMillis(option string, d, def time.Duration) (time.Duration, error) {
	if d == 0 {
		return def, nil
	}
	if d < time.Millisecond || d > maxTimeout || d%time.Millisecond != 0 {
		return 0, &ConfigError{Option: option,
			Msg: fmt.Sprintf("must be a whole number of milliseconds from 1ms to 2147483647ms, got %v", d)}
	}
	return d, nil
}

// wholeSeconds checks a period: 0 means def, otherwise whole seconds from 1s to max
// (no upper bound when max is 0).
func wholeSeconds(option string, d, def, max time.Duration) (time.Duration, error) {
	if d == 0 {
		return def, nil
	}
	if d < time.Second || (max > 0 && d > max) || d%time.Second != 0 {
		limit := "or more"
		if max > 0 {
			limit = fmt.Sprintf("to %ds", max/time.Second)
		}
		return 0, &ConfigError{Option: option,
			Msg: fmt.Sprintf("must be a whole number of seconds from 1s %s, got %v", limit, d)}
	}
	return d, nil
}

// maxCACertsSize bounds the CA bundle read; a full public root bundle is about 250 KiB.
const maxCACertsSize = 4 << 20

// loadCACerts reads a regular file, so a pipe or a device cannot block or exhaust start-up.
func loadCACerts(path string) (*x509.CertPool, error) {
	// Stat before Open: opening a pipe for reading blocks until a writer appears.
	info, err := os.Stat(path)
	if err != nil {
		return nil, &ConfigError{Option: "CACertsFile", Msg: "cannot be read", Err: err}
	}
	if !info.Mode().IsRegular() {
		return nil, &ConfigError{Option: "CACertsFile", Msg: fmt.Sprintf("%q is not a regular file", path)}
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, &ConfigError{Option: "CACertsFile", Msg: "cannot be read", Err: err}
	}
	defer f.Close()
	pem, err := io.ReadAll(io.LimitReader(f, maxCACertsSize+1))
	if err != nil {
		return nil, &ConfigError{Option: "CACertsFile", Msg: "cannot be read", Err: err}
	}
	if len(pem) > maxCACertsSize {
		return nil, &ConfigError{Option: "CACertsFile", Msg: fmt.Sprintf("%q is larger than %d bytes", path, maxCACertsSize)}
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pem) {
		return nil, &ConfigError{Option: "CACertsFile", Msg: fmt.Sprintf("%q holds no PEM certificate", path)}
	}
	return pool, nil
}

// copyOverrides deep-copies the overrides into maps of string to any, slices of any, and
// string, bool, int64, uint64, float32, float64, json.Number or nil leaves, so integers above
// 2^53 keep their value and a later change to the caller's map has no effect.
func copyOverrides(overrides map[string]any) (map[string]any, error) {
	if overrides == nil {
		return nil, nil
	}
	v, err := copyJSONValue(reflect.ValueOf(overrides), "CachePolicyOverrides", map[ancestor]bool{})
	if err != nil {
		return nil, err
	}
	return v.(map[string]any), nil
}

// ancestor identifies a map or slice on the path being copied, so a value that contains
// itself is refused instead of recursing forever.
type ancestor struct {
	ptr uintptr
	len int
}

var (
	jsonNumberType    = reflect.TypeOf(json.Number(""))
	durationType      = reflect.TypeOf(time.Duration(0))
	stringType        = reflect.TypeOf("")
	jsonMarshalerType = reflect.TypeOf((*json.Marshaler)(nil)).Elem()
	textMarshalerType = reflect.TypeOf((*encoding.TextMarshaler)(nil)).Elem()
)

func copyJSONValue(v reflect.Value, path string, enclosing map[ancestor]bool) (any, error) {
	for v.Kind() == reflect.Interface {
		if v.IsNil() {
			return nil, nil
		}
		v = v.Elem()
	}
	refuse := func(msg string) (any, error) {
		return nil, &ConfigError{Option: path, Msg: msg}
	}
	if !v.IsValid() {
		return nil, nil
	}
	if v.Type() == jsonNumberType {
		if !jsonNumber.MatchString(v.String()) {
			return refuse("must be a valid JSON number")
		}
		return json.Number(v.String()), nil
	}
	if v.Type() == durationType {
		return refuse("must not be a time.Duration, which would be sent in nanoseconds; give whole seconds as an integer")
	}
	if v.Type().Implements(jsonMarshalerType) || v.Type().Implements(textMarshalerType) {
		return refuse("must be a plain JSON value, not a " + v.Type().String() + " with its own JSON or text encoding")
	}
	switch v.Kind() {
	case reflect.String:
		if !utf8.ValidString(v.String()) {
			return refuse("must be valid UTF-8")
		}
		return v.String(), nil
	case reflect.Bool:
		return v.Bool(), nil
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return v.Int(), nil
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return v.Uint(), nil
	case reflect.Float32:
		if f := v.Float(); math.IsInf(f, 0) || math.IsNaN(f) {
			return refuse("must be a finite number")
		}
		return float32(v.Float()), nil
	case reflect.Float64:
		if f := v.Float(); math.IsInf(f, 0) || math.IsNaN(f) {
			return refuse("must be a finite number")
		}
		return v.Float(), nil
	case reflect.Map:
		// An interface key type, as YAML decoders produce, is allowed when every key is a string.
		keyKind := v.Type().Key().Kind()
		if keyKind != reflect.String && keyKind != reflect.Interface {
			return refuse("must have string keys, got " + v.Type().String())
		}
		if v.IsNil() {
			return nil, nil
		}
		id := ancestor{ptr: v.Pointer()}
		if enclosing[id] {
			return refuse("must not contain itself")
		}
		enclosing[id] = true
		defer delete(enclosing, id)
		out := make(map[string]any, v.Len())
		for it := v.MapRange(); it.Next(); {
			k := it.Key()
			if keyKind == reflect.Interface {
				if k.IsNil() || k.Elem().Type() != stringType {
					return refuse("must have string keys, got a key of type " + keyTypeName(k))
				}
				k = k.Elem()
			}
			key := k.String()
			if !utf8.ValidString(key) {
				return refuse("must have valid UTF-8 keys")
			}
			elem, err := copyJSONValue(it.Value(), path+"."+key, enclosing)
			if err != nil {
				return nil, err
			}
			out[key] = elem
		}
		return out, nil
	case reflect.Slice, reflect.Array:
		if v.Type().Elem().Kind() == reflect.Uint8 {
			return refuse("must not be a byte slice or array, got " + v.Type().String())
		}
		if v.Kind() == reflect.Slice {
			if v.IsNil() {
				return nil, nil
			}
			id := ancestor{ptr: v.Pointer(), len: v.Len()}
			if enclosing[id] {
				return refuse("must not contain itself")
			}
			enclosing[id] = true
			defer delete(enclosing, id)
		}
		out := make([]any, v.Len())
		for i := range out {
			elem, err := copyJSONValue(v.Index(i), path+"["+strconv.Itoa(i)+"]", enclosing)
			if err != nil {
				return nil, err
			}
			out[i] = elem
		}
		return out, nil
	default:
		return refuse("must be a JSON value, got " + v.Type().String())
	}
}

func keyTypeName(k reflect.Value) string {
	if k.IsNil() {
		return "nil"
	}
	return k.Elem().Type().String()
}
