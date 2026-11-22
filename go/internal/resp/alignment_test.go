// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package resp

import (
	"encoding/json"
	"testing"

	"github.com/awslabs/elasticache-integration-client/go/internal/testutil"
)

// protocolVectors is shared/protocol-vectors.json. The RESP layer checks the wire bytes; the
// fields it does not interpret are kept raw, so a new field still fails the strict decode.
type protocolVectors struct {
	Description string `json:"description"`
	Encode      []struct {
		Name      string          `json:"name"`
		Request   json.RawMessage `json:"request"`
		Overrides json.RawMessage `json:"overrides"`
		Frame     string          `json:"frame"`
	} `json:"encode"`
	Decode []struct {
		Name     string          `json:"name"`
		Command  string          `json:"command"`
		Reply    string          `json:"reply"`
		Response json.RawMessage `json:"response"`
		Config   json.RawMessage `json:"config"`
		Error    string          `json:"error"`
		Code     string          `json:"code"`
	} `json:"decode"`
}

func loadVectors(t testing.TB) protocolVectors {
	t.Helper()
	var v protocolVectors
	testutil.DecodeStrict(t, testutil.SharedFixture(t, "protocol-vectors.json"), &v)
	if len(v.Encode) == 0 || len(v.Decode) == 0 {
		t.Fatal("protocol-vectors.json has no vectors")
	}
	return v
}

// TestAlignmentRESPDecodeVectors decodes every reply in one piece and checks every prefix.
func TestAlignmentRESPDecodeVectors(t *testing.T) {
	for _, vec := range loadVectors(t).Decode {
		t.Run(vec.Name, func(t *testing.T) {
			v := mustDecode(t, vec.Reply)
			checkPrefixes(t, []byte(vec.Reply), len(vec.Reply), 1)
			if got, err := AppendValue(nil, v); err != nil || !equal(mustDecode(t, string(got)), v) {
				t.Fatalf("re-encoding: %q, %v", got, err)
			}
			if vec.Code != "" && (v.Kind != KindError || v.ErrorCode() != vec.Code) {
				t.Fatalf("got %v code %q, want error code %q", v, v.ErrorCode(), vec.Code)
			}
		})
	}
}

// TestAlignmentRESPEncodeFrames decodes every command frame as an array of bulk strings
// that starts with the command name, and encodes those arguments back to the same bytes.
func TestAlignmentRESPEncodeFrames(t *testing.T) {
	for _, vec := range loadVectors(t).Encode {
		t.Run(vec.Name, func(t *testing.T) {
			v := mustDecode(t, vec.Frame)
			checkPrefixes(t, []byte(vec.Frame), len(vec.Frame), 1)
			if v.Kind != KindArray || len(v.Elems) == 0 {
				t.Fatalf("got %v", v)
			}
			args := make([][]byte, len(v.Elems))
			for i, e := range v.Elems {
				if e.Kind != KindBulkString {
					t.Fatalf("element %v", e)
				}
				args[i] = e.Bytes
			}
			if got := string(AppendCommand(nil, args...)); got != vec.Frame {
				t.Fatalf("re-encoded %q, want %q", got, vec.Frame)
			}
			if name := string(v.Elems[0].Bytes); name != "aws.request" && name != "aws.config" {
				t.Fatalf("command %q", name)
			}
		})
	}
}
