// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// cannedCache answers every aws.request with one pre-encoded reply and allocates nothing per
// request, so a benchmark's allocations are the client's.
type cannedCache struct {
	hello, config, ok, reply []byte
}

func newCannedCache(tb testing.TB, served resp.Value) *cannedCache {
	enc := func(v resp.Value) []byte {
		out, err := resp.AppendValue(nil, v)
		if err != nil {
			tb.Fatal(err)
		}
		return out
	}
	return &cannedCache{
		hello:  enc(mockcache.Hello()),
		config: enc(resp.BulkStringValue(mockcache.RouteTenOperations)),
		ok:     enc(mockcache.OK()),
		reply:  enc(served),
	}
}

// dial gives the client one end of a net.Pipe and serves the other.
func (cc *cannedCache) dial(context.Context, string, string) (net.Conn, error) {
	client, server := net.Pipe()
	go cc.serve(server)
	return client, nil
}

func (cc *cannedCache) serve(conn net.Conn) {
	defer conn.Close()
	buf := make([]byte, 0, 1<<20)
	for {
		if len(buf) == cap(buf) {
			buf = append(buf, make([]byte, cap(buf))...)[:len(buf)]
		}
		n, err := conn.Read(buf[len(buf):cap(buf)])
		if err != nil {
			return
		}
		buf = buf[:len(buf)+n]
		for {
			name, size := scanCommand(buf)
			if size == 0 {
				break
			}
			var out []byte
			switch {
			case bytes.EqualFold(name, []byte("hello")):
				out = cc.hello
			case bytes.EqualFold(name, []byte("aws.config")):
				out = cc.config
			case bytes.EqualFold(name, []byte("aws.request")):
				out = cc.reply
			default:
				out = cc.ok
			}
			if _, err := conn.Write(out); err != nil {
				return
			}
			buf = buf[:copy(buf, buf[size:])]
		}
	}
}

// scanCommand returns the name and the length of the RESP array of bulk strings at the start
// of b, or a zero length when b does not hold all of it yet.
func scanCommand(b []byte) (name []byte, size int) {
	count, i, ok := scanHeader(b, 0, '*')
	if !ok {
		return nil, 0
	}
	for e := 0; e < count; e++ {
		n, j, ok := scanHeader(b, i, '$')
		if !ok || j+n+2 > len(b) {
			return nil, 0
		}
		if e == 0 {
			name = b[j : j+n]
		}
		i = j + n + 2
	}
	return name, i
}

func scanHeader(b []byte, i int, typ byte) (n, next int, ok bool) {
	if i >= len(b) || b[i] != typ {
		return 0, 0, false
	}
	for i++; i < len(b) && b[i] != '\r'; i++ {
		n = n*10 + int(b[i]-'0')
	}
	if i+1 >= len(b) {
		return 0, 0, false
	}
	return n, i + 2, true
}

// itemBody is a GetItem response of about size bytes.
func itemBody(size int) string {
	return `{"Item":{"Artist":{"S":"Acme"},"Data":{"S":"` + strings.Repeat("x", size) + `"}}}`
}

var benchSizes = []int{1 << 10, 64 << 10, 390 << 10}

func BenchmarkGetItemHit(b *testing.B) {
	for _, size := range benchSizes {
		b.Run(fmt.Sprintf("%dKiB", size>>10), func(b *testing.B) {
			body := itemBody(size)
			cc := newCannedCache(b, mockcache.Served(200, map[string]string{
				"content-type": "application/x-amz-json-1.0", "x-amz-crc32": crc(body),
			}, []byte(body)))
			c, err := Options{Endpoint: "localhost:6379", CacheName: "my-cache"}.validate()
			if err != nil {
				b.Fatal(err)
			}
			c.plaintext, c.dial = true, cc.dial
			ddb, _ := startSDK(b, c, &fakeOrigin{})
			benchSDKGetItem(b, ddb)
		})
	}
}

// BenchmarkGetItemPlain is the same call on a plain SDK client whose HTTP client answers from
// memory: the baseline BenchmarkGetItemHit adds to.
func BenchmarkGetItemPlain(b *testing.B) {
	for _, size := range benchSizes {
		b.Run(fmt.Sprintf("%dKiB", size>>10), func(b *testing.B) {
			body := itemBody(size)
			sum := crc(body)
			origin := aws.HTTPClient(httpDoFunc(func(req *http.Request) (*http.Response, error) {
				if req.Body != nil {
					_, _ = io.Copy(io.Discard, req.Body)
				}
				return &http.Response{
					StatusCode: 200, Status: "200 OK", Proto: "HTTP/1.1", ProtoMajor: 1, ProtoMinor: 1,
					Header:        http.Header{"Content-Type": {"application/x-amz-json-1.0"}, "X-Amz-Crc32": {sum}},
					Body:          io.NopCloser(strings.NewReader(body)),
					ContentLength: int64(len(body)),
					Request:       req,
				}, nil
			}))
			ddb := dynamodb.NewFromConfig(awsConfig(), func(o *dynamodb.Options) { o.HTTPClient = origin })
			benchSDKGetItem(b, ddb)
		})
	}
}

// BenchmarkPutItemHit is a PutItem the cache answers 200 {}: the request body travels to the
// socket, the reply is small.
func BenchmarkPutItemHit(b *testing.B) {
	for _, size := range benchSizes {
		b.Run(fmt.Sprintf("%dKiB", size>>10), func(b *testing.B) {
			cc := newCannedCache(b, mockcache.Served(200, map[string]string{
				"content-type": "application/x-amz-json-1.0", "x-amz-crc32": crc("{}"),
			}, []byte("{}")))
			c, err := Options{Endpoint: "localhost:6379", CacheName: "my-cache"}.validate()
			if err != nil {
				b.Fatal(err)
			}
			c.plaintext, c.dial = true, cc.dial
			ddb, _ := startSDK(b, c, &fakeOrigin{})
			benchSDKPutItem(b, ddb, size)
		})
	}
}

// BenchmarkPutItemPlain is the same PutItem on a plain SDK client answering from memory.
func BenchmarkPutItemPlain(b *testing.B) {
	for _, size := range benchSizes {
		b.Run(fmt.Sprintf("%dKiB", size>>10), func(b *testing.B) {
			sum := crc("{}")
			ddb := dynamodb.NewFromConfig(awsConfig(), func(o *dynamodb.Options) {
				o.HTTPClient = httpDoFunc(func(req *http.Request) (*http.Response, error) {
					if req.Body != nil {
						_, _ = io.Copy(io.Discard, req.Body)
					}
					return &http.Response{
						StatusCode: 200, Status: "200 OK", Proto: "HTTP/1.1", ProtoMajor: 1, ProtoMinor: 1,
						Header:        http.Header{"Content-Type": {"application/x-amz-json-1.0"}, "X-Amz-Crc32": {sum}},
						Body:          io.NopCloser(strings.NewReader("{}")),
						ContentLength: 2,
						Request:       req,
					}, nil
				})
			})
			benchSDKPutItem(b, ddb, size)
		})
	}
}

type httpDoFunc func(*http.Request) (*http.Response, error)

func (f httpDoFunc) Do(req *http.Request) (*http.Response, error) { return f(req) }

func benchSDKGetItem(b *testing.B, ddb *dynamodb.Client) {
	ctx := context.Background()
	in := &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: musicKey()}
	b.ReportAllocs()
	b.ResetTimer()
	for b.Loop() {
		out, err := ddb.GetItem(ctx, in)
		if err != nil || len(out.Item) != 2 {
			b.Fatal(out, err)
		}
	}
}

func benchSDKPutItem(b *testing.B, ddb *dynamodb.Client, size int) {
	ctx := context.Background()
	in := &dynamodb.PutItemInput{TableName: aws.String("Music"), Item: map[string]types.AttributeValue{
		"Artist": &types.AttributeValueMemberS{Value: "Acme"},
		"Data":   &types.AttributeValueMemberS{Value: strings.Repeat("x", size)},
	}}
	b.ReportAllocs()
	b.ResetTimer()
	for b.Loop() {
		if _, err := ddb.PutItem(ctx, in); err != nil {
			b.Fatal(err)
		}
	}
}
