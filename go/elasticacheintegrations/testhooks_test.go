// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package elasticacheintegrations

// Views of internal state that only tests read.

// err returns why the connection died, or nil while it is alive.
func (c *connection) err() error {
	if c.alive() {
		return nil
	}
	return c.cause
}

// pending counts the entries awaiting a reply, in the FIFO and deferred.
func (p *pendingRequests) pending() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.fifo) - p.head + len(p.deferred)
}

// decodeBody returns the body as cel-go input. An empty body is {}. It reports false when the
// body is not usable by predicates (see scanJSON).
func decodeBody(body []byte) (map[string]any, bool) {
	pr := predicateRequest{req: &request{body: body}}
	defer pr.release()
	return pr.body()
}
