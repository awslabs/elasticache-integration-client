// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * Test doubles for the ElastiCacheIntegrations client suite - all in one place.
 *
 * This module holds the authored mock the suite uses:
 *
 * - Reply stands in for the ElastiCacheIntegrations cache response types.
 * - MockCache models the client-to-cache wire contract: the two commands
 *   `aws.config` and `aws.request`, synchronous success replies, and
 *   error replies. Its pure `handle` method is the contract engine: it
 *   validates command shape and picks a reply without doing I/O. The socket
 *   server wraps it so a real RESP client can talk to the mock over plaintext
 *   or TLS.
 */
import { readFileSync } from "node:fs";
import {
  connect as connectTcp,
  createServer,
  type Server as NetServer,
  type Socket,
} from "node:net";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer as createTlsServer, type Server as TlsServer } from "node:tls";

import { CONFIG_COMMAND, REQUEST_COMMAND } from "../src/protocol.js";
import { decodeResp, IncompleteFrame } from "../src/resp.js";

type ReplyFactory = (request: ReceivedRequest) => Reply;
type Failure = "hang" | "drop";

const CRLF = Buffer.from("\r\n");

function _bulk(value: Buffer): Buffer {
  return Buffer.concat([Buffer.from(`$${value.length}`), CRLF, value, CRLF]);
}

function _simple(value: Buffer): Buffer {
  return Buffer.concat([Buffer.from("+"), value, CRLF]);
}

function _int(value: number | bigint): Buffer {
  return Buffer.concat([Buffer.from(`:${value}`), CRLF]);
}

function _array(values: Buffer[]): Buffer {
  return Buffer.concat([Buffer.from(`*${values.length}`), CRLF, ...values]);
}

function _map(entries: Array<[Buffer, Buffer]>): Buffer {
  return Buffer.concat([
    Buffer.from(`%${entries.length}`),
    CRLF,
    ...entries.flatMap(([key, value]) => [key, value]),
  ]);
}

function _push(values: Buffer[]): Buffer {
  return Buffer.concat([Buffer.from(`>${values.length}`), CRLF, ...values]);
}

function _null(): Buffer {
  return Buffer.concat([Buffer.from("_"), CRLF]);
}

function _error(code: string, message: string): Buffer {
  return Buffer.concat([Buffer.from(`-${`${code} ${message}`.trim()}`), CRLF]);
}

/** A RESP response to `aws.request`. */
export class Reply {
  readonly frame: Buffer;

  constructor(frame: Buffer) {
    this.frame = frame;
  }

  /** Return the encoded RESP frame. */
  toBytes(): Buffer {
    return this.frame;
  }

  /** A synchronous success reply: `*3` array of `[:status, $headers, $body]`. */
  static ok(
    status = 200,
    headers = "content-type:application/x-amz-json-1.0",
    body: Buffer = Buffer.from('{"Item": {}}'),
  ): Reply {
    return new Reply(_array([_int(status), _bulk(Buffer.from(headers)), _bulk(body)]));
  }

  /** A RESP error reply `-<CODE> <message>`. */
  static error(code = "ERR", message = "mock error reply"): Reply {
    return new Reply(_error(code, message));
  }

  /** The immediate request-id simple string that precedes a deferred push. */
  static requestId(id: number | bigint): Reply {
    return new Reply(_simple(Buffer.from(String(id), "ascii")));
  }

  /** An immediate request-id response followed by its aws.response push. */
  static asyncResponse(id: number | bigint, reply = Reply.ok()): Reply {
    return new Reply(
      Buffer.concat([
        _simple(Buffer.from(String(id), "ascii")),
        _push([
          _bulk(Buffer.from("aws.response", "ascii")),
          _bulk(Buffer.from(String(id), "ascii")),
          reply.toBytes(),
        ]),
      ]),
    );
  }
}

/** The decoded view of one `aws.request` the cache received. */
export interface ReceivedRequest {
  method: Buffer;
  url: Buffer;
  headers: Buffer;
  body: Buffer;
  metadata: Buffer;
  rawArgs: Buffer[];
}

/** Options that mirror the Python `MockCache` constructor. */
export interface MockCacheOptions {
  config?: Record<string, unknown>;
  tls?: boolean;
  helloResponse?: Reply;
  authResponse?: Reply;
  responseStatus?: number;
  responseHeaders?: string;
  responseBody?: Buffer;
  response?: Reply;
  responseFn?: ReplyFactory;
  failure?: Failure;
  failureCommand?: string; // which command triggers `failure` (e.g. "hello")
  failureAfterReply?: boolean; // reply normally first, then apply `failure`
  // reply for a no-arg aws.config; undefined = RESP3 null ("unchanged"). Mutable between cycles.
  noArgConfigReply?: Record<string, unknown>;
}

// The certificate and key are checked-in fixtures used by the TLS server.
const here = dirname(fileURLToPath(import.meta.url));

/** A fake ElastiCacheIntegrations cache server. */
export class MockCache {
  /**
   * Accepts credentials on `HELLO 3 AUTH`, serves a canned config, and answers each
   * `aws.request`
   * with `responseFn(request)`, a fixed `response`, or the default built from
   * `responseStatus`/`responseHeaders`/`responseBody`.
   *
   * Records everything received (`receivedCommands`, `cacheCommandLog`,
   * `requests`). `failure: "hang"` never replies; `failure: "drop"` closes the
   * connection instead of replying. `failureAfterReply` sends the normal reply
   * first and then misbehaves. `failureCommand` selects the command that
   * triggers it (default `aws.request`; use `"hello"` to break the handshake).
   *
   * Plaintext by default; `tls: true` serves TLS using the checked-in fixtures.
   */
  readonly tls: boolean;
  readonly responseStatus: number;
  readonly responseHeaders: string;
  readonly responseBody: Buffer;

  // Recording, for test assertions.
  readonly receivedCommands: Buffer[][] = [];
  readonly cacheCommandLog: string[] = [];
  readonly requests: ReceivedRequest[] = [];
  /** One entry per `aws.config`: the parsed overrides object, or `null` for the no-arg form. */
  readonly configOverrides: (Record<string, unknown> | null)[] = [];
  /**
   * Reply for a no-argument `aws.config`; `undefined` means the RESP3 null ("unchanged").
   * Mutable between refresh cycles so a test can simulate a cache-side config change.
   */
  noArgConfigReply?: Record<string, unknown>;
  /** Raw no-argument config frame override for transport/protocol failure tests. */
  noArgConfigFrame?: Buffer;
  readonly host = "127.0.0.1";
  port?: number;
  certPath?: string;
  keyPath?: string;

  // Contract-engine state.
  private config: Record<string, unknown>;
  private authResponse?: Reply;
  private readonly helloResponse?: Reply;
  private readonly response?: Reply;
  private readonly responseFn?: ReplyFactory;
  private readonly failure?: Failure;
  private readonly failureCommand: string;
  private readonly failureAfterReply: boolean;

  // Server state.
  private server?: NetServer | TlsServer;
  private sockets = new Set<Socket>();

  constructor(options: MockCacheOptions = {}) {
    // Callers usually override only `predicates`; the rest of a valid config
    // (notably the required `refresh_interval`) is filled in here.
    this.config = { version: "1.0", refresh_interval: 60, predicates: [], ...options.config };
    this.tls = options.tls ?? false;
    this.helloResponse = options.helloResponse;
    this.authResponse = options.authResponse;
    this.responseStatus = options.responseStatus ?? 200;
    this.responseHeaders = options.responseHeaders ?? "content-type:application/x-amz-json-1.0";
    this.responseBody = options.responseBody ?? Buffer.from('{"Item": {}}');
    this.response = options.response;
    this.responseFn = options.responseFn;
    if (options.failure !== undefined && options.failure !== "hang" && options.failure !== "drop") {
      throw new Error(`Unsupported failure mode: ${options.failure}`);
    }
    this.failure = options.failure;
    this.failureCommand = (options.failureCommand ?? REQUEST_COMMAND).toLowerCase();
    this.failureAfterReply = options.failureAfterReply ?? false;
    this.noArgConfigReply = options.noArgConfigReply;
  }

  /** Return the host and active ephemeral port. */
  get endpoint(): string {
    return `${this.host}:${this.port}`;
  }

  /**
   * Raw arg list (command name excluded) of the most recent `aws.request`.
   * Returns `[method, url, headers, body, metadata]`.
   */
  get lastRequestArgs(): Buffer[] | undefined {
    return this.requests.at(-1)?.rawArgs;
  }

  /** The most recent decoded `aws.request` record, or `undefined`. */
  get lastRequest(): ReceivedRequest | undefined {
    return this.requests.at(-1);
  }

  /** Change the config returned by subsequent connection handshakes. */
  setConfig(config: Record<string, unknown>): void {
    this.config = config;
  }

  /** Change the AUTH reply used by subsequent attempts. */
  setAuthResponse(reply?: Reply): void {
    this.authResponse = reply;
  }

  /**
   * Turn one decoded RESP command into the RESP reply frame.
   * Pure: no socket, no threads.
   *
   * Validates command shape, records the command, parses a `aws.request`,
   * and selects the reply.
   */
  handle(command: Buffer[]): Buffer {
    this.receivedCommands.push(command);
    const name = command[0]?.toString("utf8").toLowerCase() ?? "";
    const args = command.slice(1);

    if (name === "hello") {
      this.cacheCommandLog.push(name);
      if (this.helloResponse !== undefined) return this.helloResponse.toBytes();
      if (
        args.length < 1 ||
        (args[0]!.toString("ascii") !== "2" && args[0]!.toString("ascii") !== "3")
      ) {
        return _error("NOPROTO", "unsupported protocol version");
      }
      const auth = args.slice(1);
      if (
        auth.length !== 0 &&
        (auth.length !== 3 || auth[0]!.toString("ascii").toUpperCase() !== "AUTH")
      ) {
        return _error("ERR", "syntax error");
      }
      const protocol = Number(args[0]!.toString("ascii"));
      const fields: Array<[Buffer, Buffer]> = [
        [_bulk(Buffer.from("server")), _bulk(Buffer.from("valkey"))],
        [_bulk(Buffer.from("version")), _bulk(Buffer.from("8.0.0"))],
        [_bulk(Buffer.from("proto")), _int(protocol)],
        [_bulk(Buffer.from("id")), _int(1)],
        [_bulk(Buffer.from("mode")), _bulk(Buffer.from("cluster"))],
        [_bulk(Buffer.from("role")), _bulk(Buffer.from("primary"))],
        [_bulk(Buffer.from("modules")), _array([])],
      ];
      return protocol === 3 ? _map(fields) : _array(fields.flat());
    }
    if (name === "auth") {
      this.cacheCommandLog.push(name);
      if (args.length !== 2) return _error("ERR", `wrong number of arguments for AUTH`);
      return this.authResponse?.toBytes() ?? _simple(Buffer.from("OK"));
    }
    if (name === CONFIG_COMMAND) {
      this.cacheCommandLog.push(name);
      if (args.length > 1) {
        return _error("CONFIG_INVALID", `expected 0 or 1 args, got ${args.length}`);
      }
      if (args.length === 0) {
        this.configOverrides.push(null);
        if (this.noArgConfigFrame !== undefined) return this.noArgConfigFrame;
        if (this.noArgConfigReply === undefined) return _null();
        return _bulk(Buffer.from(JSON.stringify(this.noArgConfigReply)));
      }
      let overrides: unknown;
      try {
        overrides = JSON.parse(args[0]!.toString("utf8"));
      } catch {
        return _error("CONFIG_INVALID", "overrides must be a JSON object");
      }
      this.configOverrides.push(overrides as Record<string, unknown>);
      return _bulk(Buffer.from(JSON.stringify(this.config)));
    }
    if (name === REQUEST_COMMAND) {
      this.cacheCommandLog.push(name);
      if (args.length < 5) {
        return _error("ERR", `malformed aws.request: ${args.length} args (need >= 5)`);
      }
      const parsed = this.parseRequest(args);
      this.requests.push(parsed);
      return this.replyFor(parsed).toBytes();
    }
    return _error("ERR", "unknown command");
  }

  /** Decode the 5 mandatory args. */
  private parseRequest(args: Buffer[]): ReceivedRequest {
    const [method, url, headers, body, metadata] = args;
    return {
      method: method!,
      url: url!,
      headers: headers!,
      body: body!,
      metadata: metadata!,
      rawArgs: [...args],
    };
  }

  /** Pick the reply for a parsed request: callback, fixed override, then default. */
  private replyFor(request: ReceivedRequest): Reply {
    if (this.responseFn) return this.responseFn(request);
    if (this.response) return this.response;
    return Reply.ok(this.responseStatus, this.responseHeaders, this.responseBody);
  }

  /** Start the server on an ephemeral 127.0.0.1 port, plaintext unless `tls`. */
  async start(): Promise<void> {
    if (this.server) return;
    const server = this.tls
      ? createTlsServer(
          {
            cert: readFileSync(join(here, "fixtures/tls/localhost.crt")),
            key: readFileSync(join(here, "fixtures/tls/localhost.key")),
          },
          (socket) => this.handleConnection(socket),
        )
      : createServer((socket) => this.handleConnection(socket));
    this.server = server;
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, this.host, () => {
        server.off("error", reject);
        const address = server.address();
        if (!address || typeof address === "string")
          return reject(new Error("mock server did not bind"));
        this.port = address.port;
        resolve();
      });
    });
  }

  /** Stop accepting, close active sockets, and stop the test server. Idempotent. */
  async stop(): Promise<void> {
    for (const socket of this.sockets) socket.destroy();
    this.sockets.clear();
    const server = this.server;
    this.server = undefined;
    if (!server) return;
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  // RESP command reader: client-to-cache traffic is always an array of bulk strings.
  private handleConnection(socket: Socket): void {
    this.sockets.add(socket);
    let pending = Buffer.alloc(0);
    let hung = false;
    socket.on("close", () => this.sockets.delete(socket));
    socket.on("data", (chunk: Buffer) => {
      if (hung) return;
      pending = Buffer.concat([pending, chunk]);
      while (pending.length) {
        let decoded: ReturnType<typeof decodeResp>;
        try {
          decoded = decodeResp(pending);
        } catch (error) {
          if (error instanceof IncompleteFrame) return;
          socket.destroy();
          return;
        }
        const [command, next] = decoded;
        pending = pending.subarray(next);
        if (!Array.isArray(command) || !command.every(Buffer.isBuffer)) continue;
        const args = command as Buffer[];
        const name = args[0]?.toString("utf8").toLowerCase() ?? "";
        if (name === this.failureCommand && (this.failure === "hang" || this.failure === "drop")) {
          if (this.failureAfterReply) socket.write(this.handle(args));
          else {
            this.receivedCommands.push(args);
            this.cacheCommandLog.push(name);
          }
          if (this.failure === "drop") socket.destroy();
          else hung = true;
          return;
        }
        socket.write(this.handle(args));
      }
    });
  }

  /** Send a deferred response push to the active client without interleaving writes. */
  sendPush(
    requestId: number | bigint,
    reply = Reply.ok(),
    kind = Buffer.from("aws.response", "ascii"),
    kindEncoding: "bulk" | "simple" = "bulk",
  ): void {
    const socket = this.onlySocket();
    const encodedKind = kindEncoding === "simple" ? _simple(kind) : _bulk(kind);
    socket.write(
      _push([encodedKind, _bulk(Buffer.from(String(requestId), "ascii")), reply.toBytes()]),
    );
  }

  private onlySocket(): Socket {
    const socket = this.sockets.values().next().value as Socket | undefined;
    if (socket === undefined) {
      throw new Error("mock cache has no active client socket");
    }
    return socket;
  }
}

/** Controllable TCP relay used to exercise idle disconnect and reconnect behavior. */
export class MockTcpRelay {
  readonly host = "127.0.0.1";
  port?: number;
  acceptedConnections = 0;

  private server?: NetServer;
  private forwarding = true;
  private readonly sockets = new Set<Socket>();

  constructor(
    private readonly targetHost: string,
    private readonly targetPort: number,
  ) {}

  async start(): Promise<void> {
    const server = createServer((client) => this.accept(client));
    this.server = server;
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, this.host, () => {
        server.off("error", reject);
        const address = server.address();
        if (!address || typeof address === "string") {
          reject(new Error("mock relay did not bind"));
          return;
        }
        this.port = address.port;
        resolve();
      });
    });
  }

  disconnectClients(): void {
    for (const socket of this.sockets) socket.destroy();
    this.sockets.clear();
  }

  disconnectAndBlock(): void {
    this.forwarding = false;
    this.disconnectClients();
  }

  allowConnections(): void {
    this.forwarding = true;
  }

  async stop(): Promise<void> {
    this.disconnectClients();
    const server = this.server;
    this.server = undefined;
    if (!server) return;
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  private accept(client: Socket): void {
    this.acceptedConnections++;
    if (!this.forwarding) {
      client.destroy();
      return;
    }

    const upstream = connectTcp({ host: this.targetHost, port: this.targetPort });
    this.track(client);
    this.track(upstream);
    client.pipe(upstream);
    upstream.pipe(client);
    const closePair = () => {
      client.destroy();
      upstream.destroy();
    };
    client.once("error", closePair);
    upstream.once("error", closePair);
    client.once("close", closePair);
    upstream.once("close", closePair);
  }

  private track(socket: Socket): void {
    this.sockets.add(socket);
    socket.once("close", () => this.sockets.delete(socket));
  }
}
