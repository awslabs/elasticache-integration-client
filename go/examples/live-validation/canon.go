// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/base64"
	"fmt"
	"slices"
	"strconv"
	"strings"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// canon is the canonical text of an item: names and sets sorted, so equal items compare equal.
// A nil item is "<none>".
func canon(item map[string]types.AttributeValue) string {
	if item == nil {
		return "<none>"
	}
	var b strings.Builder
	writeMap(&b, item)
	return b.String()
}

// canonAll is the canonical text of a list of items, sorted.
func canonAll(items []map[string]types.AttributeValue) string {
	out := make([]string, len(items))
	for i, item := range items {
		out[i] = canon(item)
	}
	slices.Sort(out)
	return "[" + strings.Join(out, ",") + "]"
}

func writeMap(b *strings.Builder, m map[string]types.AttributeValue) {
	names := make([]string, 0, len(m))
	for name := range m {
		names = append(names, name)
	}
	slices.Sort(names)
	b.WriteByte('{')
	for i, name := range names {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(strconv.Quote(name))
		b.WriteByte(':')
		writeValue(b, m[name])
	}
	b.WriteByte('}')
}

func writeValue(b *strings.Builder, v types.AttributeValue) {
	switch v := v.(type) {
	case *types.AttributeValueMemberS:
		b.WriteString("S" + strconv.Quote(v.Value))
	case *types.AttributeValueMemberN:
		b.WriteString("N" + v.Value)
	case *types.AttributeValueMemberB:
		b.WriteString("B" + base64.StdEncoding.EncodeToString(v.Value))
	case *types.AttributeValueMemberBOOL:
		b.WriteString("BOOL" + strconv.FormatBool(v.Value))
	case *types.AttributeValueMemberNULL:
		b.WriteString("NULL")
	case *types.AttributeValueMemberSS:
		writeSet(b, "SS", v.Value, strconv.Quote)
	case *types.AttributeValueMemberNS:
		writeSet(b, "NS", v.Value, func(s string) string { return s })
	case *types.AttributeValueMemberBS:
		writeSet(b, "BS", v.Value, base64.StdEncoding.EncodeToString)
	case *types.AttributeValueMemberL:
		b.WriteString("L[")
		for i, e := range v.Value {
			if i > 0 {
				b.WriteByte(',')
			}
			writeValue(b, e)
		}
		b.WriteByte(']')
	case *types.AttributeValueMemberM:
		b.WriteByte('M')
		writeMap(b, v.Value)
	default:
		fmt.Fprintf(b, "?%T", v)
	}
}

func writeSet[T any](b *strings.Builder, tag string, values []T, text func(T) string) {
	out := make([]string, len(values))
	for i, v := range values {
		out[i] = text(v)
	}
	slices.Sort(out)
	b.WriteString(tag + "[" + strings.Join(out, ",") + "]")
}
