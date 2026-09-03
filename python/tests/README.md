# Photon client tests

How to run the Python client's tests locally and what each part covers. Run everything from the
`python/` directory (the project root for this package).

## Prerequisites

- Python >= 3.10
- Install the package with its test extras (editable install recommended):

  ```bash
  cd python
  pip install -e '.[test]'
  ```

  This installs the test tools (`pytest`, `pytest-cov`) and `cryptography` (used by the in-process
  mock cache to mint a self-signed cert) plus the package's runtime dependencies (`boto3`,
  `botocore`). The transport is **pure-Python** — there are no native or optional dependencies, and
  no external services are required: the end-to-end test runs against an in-process mock cache.

## Running

From `python/`:

```bash
python -m pytest                       # run the whole suite (coverage is reported automatically)
python -m pytest -q                    # quieter output
python -m pytest --no-cov              # skip coverage for a faster inner loop
python -m pytest tests/test_protocol.py              # one file
python -m pytest tests/test_connection.py            # connector unit + integration tests
```

Coverage (`--cov=photon --cov-report=term-missing`) is enabled by default via `pyproject.toml`.

### Fast loop vs. end-to-end

Most tests are fast and hermetic. The end-to-end test lives in `tests/test_ddb.py`; it always runs
(no optional dependency to skip on).

```bash
python -m pytest --ignore=tests/test_ddb.py   # everything except the e2e
python -m pytest tests/test_ddb.py            # just the end-to-end test
```

## What the tests cover

| File | Kind | Covers |
|---|---|---|
| `test_scaffolding.py` | smoke | package imports cleanly and exposes `__version__` |
| `test_resp.py` | component | `photon.resp` RESP3 wire codec (encode/decode, all prefixes, `IncompleteFrameError` truncation, `PhotonProtocolError` garbage) |
| `test_protocol.py` | component | `photon.protocol` (command building, header normalization, request arg-list serialization, config/request response parsing) |
| `test_connection.py` | component + integration | `photon.connection` `_SocketReader`/`_PendingRequests` (in-memory `MockSocket`) **and** the real `PhotonConnection` vs `MockCache` over a plaintext loopback (config/request, FIFO concurrency, timeout, error/drop) |
| `test_session.py` | component | `photon.session.PhotonSession` sync API, driven by the `mock_connection()` autospec double |
| `test_hooks.py` | component | `photon.hooks` + boto3 `before-send` hook, driven by the `mock_session()` autospec double |
| `test_mock.py` | component | the contract logic in `tests/mock.py` (`MockCache.handle` shape validation + `TTL`/`NTTL` parsing) |
| `test_ddb.py` | **end-to-end** | the full path `photon.client → boto3 GetItem → hook → PhotonSession → real PhotonConnection (TLS) → MockCache` |

All test doubles live in one module, `tests/mock.py` (imported by tests, not collected as test cases):

- `MockCache` — an in-process, synchronous, threaded RESP mock of the cache that answers
  `photon.config` and `photon.request`. Its pure `handle()` method is the contract engine
  (command-shape validation, `TTL`/`NTTL` parsing, reply selection); a thin threaded server wraps
  it. **Plaintext by default** (`tls=False`); pass `tls=True` for a verifying TLS server (mints a
  self-signed cert, exposed via `cert_path`). Replies are configured with `Reply.ok`/`Reply.error`,
  a `response_fn` callable (e.g. reflect), or the `response_*` defaults; `failure="hang"|"drop"`
  drives the network failure modes. Call `start()`/`stop()` to manage the server.
- `MockSocket` — an in-memory, scriptable socket for the `_SocketReader` unit tests.
- `MockClient` — a botocore client stand-in exposing the real `.meta.events` emitter the hooks
  register on.
- `mock_connection()` / `mock_session()` — `create_autospec` factory functions bound to the real
  `PhotonConnection` / `PhotonSession`, with sensible default replies, for the session and hooks
  tests.

## The end-to-end test (`test_ddb.py`)

`test_ddb.py` exercises the **real** `PhotonConnection` over TLS (not a double), validating the
actual transport + protocol framing together:

- It starts `MockCache(tls=True)` in-process with a **self-signed** certificate, and the client
  performs real (verifying) TLS against it — `photon.PhotonConnection` is patched to inject
  `ca_certs=cache.cert_path`; no real cache or DynamoDB is contacted.
- The transport is pure stdlib, so there is no optional dependency — the e2e always runs.

> Note: `tests/mock.py` is an import-only module of test doubles (no test cases).
