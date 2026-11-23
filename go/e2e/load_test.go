// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package e2e

import (
	"context"
	"fmt"
	"math/rand/v2"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"

	"github.com/awslabs/elasticache-integration-client/go/internal/mockcache"
	"github.com/awslabs/elasticache-integration-client/go/internal/resp"
)

// TestConcurrentLoad runs many goroutines of mixed calls on keys of their own through one
// connection, with replies in band, late and deferred, and checks every output against what
// each goroutine wrote, so a reply that reaches the wrong caller fails it.
func TestConcurrentLoad(t *testing.T) {
	t.Parallel()
	const (
		workers = 64
		calls   = 40
		songs   = 4
	)
	h := newHarness(t)
	const seedYear = "1000"
	for w := range workers {
		for i := range songs {
			h.dynamo.put(t, "Music", item{"Artist": sj(fmt.Sprintf("W%02d", w)), "Song": sj(fmt.Sprintf("S%d", i)),
				"Year": nj(seedYear)})
		}
	}
	var mu sync.Mutex
	rng := rand.New(rand.NewPCG(1, 2))
	h.proxy.setWrap(func(_ mockcache.Command, answer func() resp.Value) mockcache.Reply {
		mu.Lock()
		mode, delay := rng.IntN(3), time.Duration(rng.IntN(3))*time.Millisecond
		mu.Unlock()
		switch mode {
		case 0:
			return mockcache.Now(answer())
		case 1:
			return mockcache.After(delay, answer())
		default:
			return mockcache.DeferredAfter(delay, answer())
		}
	})
	ctx := context.Background()
	var wg sync.WaitGroup
	for w := range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			artist := fmt.Sprintf("W%02d", w)
			model := map[string]string{} // song -> Year
			for i := range songs {
				model[fmt.Sprintf("S%d", i)] = seedYear
			}
			r := rand.New(rand.NewPCG(uint64(w), 7))
			song := func() string { return fmt.Sprintf("S%d", r.IntN(songs)) }
			for i := range calls {
				if err := loadCall(ctx, h, artist, model, r.IntN(5), song(), song(), fmt.Sprint(i)); err != nil {
					t.Errorf("%s call %d: %v", artist, i, err)
					return
				}
			}
		}()
	}
	wg.Wait()
	for _, cmd := range h.cache.Requests() {
		target := ""
		for _, line := range strings.Split(cmd.Arg(3), "\n") {
			if v, ok := strings.CutPrefix(line, "x-amz-target:"); ok {
				target = v
			}
		}
		if target != "DynamoDB_20120810."+operation(cmd) {
			t.Errorf("metadata %q with x-amz-target %q", cmd.Arg(5), target)
		}
	}
	if h.cache.Connections() != 1 || h.dynamo.fromClient() != 0 {
		t.Errorf("connections %d, client calls %d; want 1 and 0", h.cache.Connections(), h.dynamo.fromClient())
	}
}

// loadCall makes one call of kind on artist's items and checks it against model. Every item
// exists and every output carries the caller's artist and songs, so a reply delivered to
// another caller, or to another operation, fails the check.
func loadCall(ctx context.Context, h *harness, artist string, model map[string]string, kind int, song, other, year string) error {
	key := func(s string) map[string]types.AttributeValue { return songKey(artist, s) }
	check := func(what string, it map[string]types.AttributeValue, song, year string) error {
		if got := loadItem(it); got != artist+"/"+song+"="+year {
			return fmt.Errorf("%s: %q, want %q", what, got, artist+"/"+song+"="+year)
		}
		return nil
	}
	switch kind {
	case 0: // PutItem
		out, err := h.ddb.PutItem(ctx, &dynamodb.PutItemInput{TableName: aws.String("Music"),
			Item:         map[string]types.AttributeValue{"Artist": sv(artist), "Song": sv(song), "Year": nv(year)},
			ReturnValues: types.ReturnValueAllOld})
		if h.seen(err) != nil {
			return err
		}
		if err := check("PutItem old item", out.Attributes, song, model[song]); err != nil {
			return err
		}
		model[song] = year
	case 1: // UpdateItem
		out, err := h.ddb.UpdateItem(ctx, &dynamodb.UpdateItemInput{TableName: aws.String("Music"), Key: key(song),
			UpdateExpression: aws.String("SET #y = :y"), ExpressionAttributeNames: map[string]string{"#y": "Year"},
			ExpressionAttributeValues: map[string]types.AttributeValue{":y": nv(year)}, ReturnValues: types.ReturnValueAllNew})
		if h.seen(err) != nil {
			return err
		}
		if err := check("UpdateItem new item", out.Attributes, song, year); err != nil {
			return err
		}
		model[song] = year
	case 2: // GetItem
		out, err := h.ddb.GetItem(ctx, &dynamodb.GetItemInput{TableName: aws.String("Music"), Key: key(song)})
		if h.seen(err) != nil {
			return err
		}
		if err := check("GetItem", out.Item, song, model[song]); err != nil {
			return err
		}
	case 3: // BatchGetItem
		keys := []map[string]types.AttributeValue{key(song)}
		want := []string{artist + "/" + song + "=" + model[song]}
		if other != song {
			keys = append(keys, key(other))
			want = append(want, artist+"/"+other+"="+model[other])
		}
		out, err := h.ddb.BatchGetItem(ctx, &dynamodb.BatchGetItemInput{RequestItems: map[string]types.KeysAndAttributes{
			"Music": {Keys: keys}}})
		if h.seen(err) != nil {
			return err
		}
		if got := loadItems(out.Responses["Music"]); !slices.Equal(got, sorted(want)) {
			return fmt.Errorf("BatchGetItem = %v, want %v", got, sorted(want))
		}
	default: // Query
		out, err := h.ddb.Query(ctx, &dynamodb.QueryInput{TableName: aws.String("Music"), KeyConditionExpression: aws.String("Artist = :a"),
			ExpressionAttributeValues: map[string]types.AttributeValue{":a": sv(artist)}})
		if h.seen(err) != nil {
			return err
		}
		var want []string
		for s, y := range model {
			want = append(want, artist+"/"+s+"="+y)
		}
		if got := loadItems(out.Items); !slices.Equal(got, sorted(want)) {
			return fmt.Errorf("Query = %v, want %v", got, sorted(want))
		}
	}
	return nil
}

// loadItem is "<Artist>/<Song>=<Year>" of it, with "" for a missing attribute.
func loadItem(it map[string]types.AttributeValue) string {
	attr := func(name string) string {
		switch v := it[name].(type) {
		case *types.AttributeValueMemberS:
			return v.Value
		case *types.AttributeValueMemberN:
			return v.Value
		}
		return ""
	}
	return attr("Artist") + "/" + attr("Song") + "=" + attr("Year")
}

// loadItems is loadItem of every item, sorted.
func loadItems(items []map[string]types.AttributeValue) []string {
	out := make([]string, 0, len(items))
	for _, it := range items {
		out = append(out, loadItem(it))
	}
	return sorted(out)
}

func sorted(s []string) []string {
	s = slices.Clone(s)
	slices.Sort(s)
	return s
}
