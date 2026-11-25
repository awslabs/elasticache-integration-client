// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

//go:build unix

package elasticacheintegrations

import (
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

func TestValidateCACertsFileFIFO(t *testing.T) {
	fifo := filepath.Join(t.TempDir(), "ca.pem")
	if err := syscall.Mkfifo(fifo, 0o600); err != nil {
		t.Skip("mkfifo:", err)
	}
	o := validOptions()
	o.CACertsFile = fifo
	done := make(chan error, 1)
	go func() {
		_, err := o.validate()
		done <- err
	}()
	select {
	case err := <-done:
		mustConfigError(t, err, "CACertsFile", "not a regular file")
	case <-time.After(5 * time.Second):
		t.Fatal("validate blocked on a FIFO")
	}
}
