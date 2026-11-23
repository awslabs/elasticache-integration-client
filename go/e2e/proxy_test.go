// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// reads are the operations the proxy answers from what it kept.
var reads = map[string]bool{"GetItem": true, "BatchGetItem": true, "Query": true, "Scan": true, "TransactGetItems": true}

// kept is a DynamoDB reply as the proxy holds it.
type kept struct {
	status  int
	headers map[string]string
	body    []byte
}

func (k kept) value() resp.Value { return mockcache.Served(k.status, k.headers, k.body) }

// proxy is the mock cache's aws.request handler. It answers like the proxy as far as a client
// can tell: a read is served from what it kept or fetched from DynamoDB and kept; a write goes
// to DynamoDB and, when it succeeds, drops everything kept.
type proxy struct {
	dynamo *dynamo
	hits   atomic.Int64
	misses atomic.Int64

	mu   sync.Mutex
	kept map[string]kept
	wrap func(cmd mockcache.Command, answer func() resp.Value) mockcache.Reply
}

func newProxy(d *dynamo) *proxy { return &proxy{dynamo: d, kept: map[string]kept{}} }

// setWrap makes w choose the reply to each aws.request; answer gives the proxy's own reply.
// nil restores in-band replies.
func (p *proxy) setWrap(w func(cmd mockcache.Command, answer func() resp.Value) mockcache.Reply) {
	p.mu.Lock()
	p.wrap = w
	p.mu.Unlock()
}

func (p *proxy) handle(cmd mockcache.Command) mockcache.Reply {
	p.mu.Lock()
	w := p.wrap
	p.mu.Unlock()
	answer := func() resp.Value { return p.answer(cmd).value() }
	if w != nil {
		return w(cmd, answer)
	}
	return mockcache.Now(answer())
}

// answer is the proxy's reply to cmd.
func (p *proxy) answer(cmd mockcache.Command) kept {
	op := operation(cmd)
	key := op + "\x00" + canonical(cmd.Args[4])
	if reads[op] {
		p.mu.Lock()
		k, ok := p.kept[key]
		p.mu.Unlock()
		if ok {
			p.hits.Add(1)
			return k
		}
		p.misses.Add(1)
	}
	got := p.forward(cmd)
	if got.status/100 == 2 {
		p.mu.Lock()
		if reads[op] {
			p.kept[key] = got
		} else {
			clear(p.kept)
		}
		p.mu.Unlock()
	}
	return got
}

// forward sends cmd to the fake DynamoDB, as the proxy calls DynamoDB on a miss or a write.
func (p *proxy) forward(cmd mockcache.Command) kept {
	req, err := http.NewRequest(http.MethodPost, p.dynamo.url()+"/", bytes.NewReader(cmd.Args[4]))
	if err != nil {
		return internalError(err)
	}
	for _, line := range strings.Split(cmd.Arg(3), "\n") {
		if name, value, ok := strings.Cut(line, ":"); ok {
			req.Header.Set(name, value)
		}
	}
	req.Header.Set(callerHeader, "cache")
	res, err := originClient.Do(req)
	if err != nil {
		return internalError(err)
	}
	defer res.Body.Close()
	body, err := io.ReadAll(res.Body)
	if err != nil {
		return internalError(err)
	}
	headers := map[string]string{}
	for _, name := range []string{"Content-Type", "X-Amz-Crc32", "X-Amzn-Requestid", "Date"} {
		if v := res.Header.Get(name); v != "" {
			headers[strings.ToLower(name)] = v
		}
	}
	return kept{status: res.StatusCode, headers: headers, body: body}
}

// originClient is the proxy's client to DynamoDB; the timeout stops a stuck call from hanging
// the suite.
var originClient = &http.Client{Timeout: 10 * time.Second}

func internalError(err error) kept {
	body := mustJSON(map[string]string{"__type": "com.amazonaws.dynamodb.v20120810#InternalServerError", "message": err.Error()})
	return kept{status: 500, headers: map[string]string{"content-type": "application/x-amz-json-1.0"}, body: body}
}

// canonical is body with its object keys sorted, since the SDK writes map keys in random
// order. A body that is not JSON is its own key.
func canonical(body []byte) string {
	d := json.NewDecoder(bytes.NewReader(body))
	d.UseNumber()
	var v any
	if d.Decode(&v) != nil {
		return string(body)
	}
	b, err := json.Marshal(v)
	if err != nil {
		return string(body)
	}
	return string(b)
}

// operation is the op of an aws.request, from its metadata.
func operation(cmd mockcache.Command) string {
	_, op, _ := strings.Cut(cmd.Arg(5), "&op=")
	return op
}
