// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"strconv"
)

// dialCache opens the byte stream to the cache: TCP, then TLS unless c.plaintext. Both steps
// run under ctx, whose deadline is the bring-up deadline.
func dialCache(ctx context.Context, c *config) (net.Conn, error) {
	addr := net.JoinHostPort(c.host, strconv.Itoa(c.port))
	dial := c.dial
	if dial == nil {
		dial = (&net.Dialer{}).DialContext // TCP keepalive and TCP_NODELAY are on by default
	}
	raw, err := dial(ctx, "tcp", addr)
	if err != nil {
		return nil, stepFailed(ctx, "connect to "+addr, err)
	}
	if c.plaintext {
		return raw, nil
	}
	conn := tls.Client(raw, tlsConfig(c))
	if err := conn.HandshakeContext(ctx); err != nil {
		_ = raw.Close()
		return nil, stepFailed(ctx, "TLS handshake with "+addr, err)
	}
	return conn, nil
}

// tlsConfig is TLS 1.2 or later, verified against the endpoint host. For an IP literal Go sends
// no SNI and checks the IP against the certificate's IP addresses.
func tlsConfig(c *config) *tls.Config {
	t := &tls.Config{ServerName: c.host, MinVersion: tls.VersionTLS12, RootCAs: c.rootCAs}
	if c.insecureSkipVerify {
		c.logger.warnInsecureSkipVerify()
		t.InsecureSkipVerify = true
	}
	return t
}

// stepFailed is the *ConnectionError for a failed bring-up step. A step that ran out of time
// wraps a *TimeoutError.
func stepFailed(ctx context.Context, step string, err error) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) || isTimeout(err) {
		return &ConnectionError{Msg: step + " timed out", Err: &TimeoutError{Err: err}}
	}
	return &ConnectionError{Msg: step + " failed", Err: err}
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}
