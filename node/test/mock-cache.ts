/**
 * Test doubles for the Photon client suite - all in one place.
 *
 * This module holds the authored mock the suite uses:
 *
 * - Reply stands in for the Photon cache response types.
 * - MockCache models the client-to-cache wire contract: the two commands
 *   `photon.config` and `photon.request`, synchronous success replies, and
 *   error replies. Its pure `handle` method is the contract engine: it
 *   validates command shape, parses `TTL`/`NTTL`, and picks a reply without
 *   doing I/O. The socket server wraps it so a real RESP client can talk to
 *   the mock over plaintext or TLS.
 */
import { readFileSync } from "node:fs";
import { createServer, type Server as NetServer, type Socket } from "node:net";
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

function _push(values: Buffer[]): Buffer {
  return Buffer.concat([Buffer.from(`>${values.length}`), CRLF, ...values]);
}

function _error(code: string, message: string): Buffer {
  return Buffer.concat([Buffer.from(`-${`${code} ${message}`.trim()}`), CRLF]);
}

/** A RESP response to `photon.request`. */
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

  /** An immediate request-id response followed by its photon.response push. */
  static asyncResponse(id: number | bigint, reply = Reply.ok()): Reply {
    return new Reply(
      Buffer.concat([
        _simple(Buffer.from(String(id), "ascii")),
        _push([_bulk(Buffer.from("photon.response", "ascii")), _int(id), reply.toBytes()]),
      ]),
    );
  }
}

/** The decoded view of one `photon.request` the cache received. */
export interface ReceivedRequest {
  method: Buffer;
  url: Buffer;
  headers: Buffer;
  body: Buffer;
  metadata: Buffer;
  ttl?: number | bigint;
  nttl?: number | bigint;
  rawArgs: Buffer[];
}

/** Options that mirror the Python `MockCache` constructor. */
export interface MockCacheOptions {
  config?: Record<string, unknown>;
  tls?: boolean;
  authResponse?: Reply;
  responseStatus?: number;
  responseHeaders?: string;
  responseBody?: Buffer;
  response?: Reply;
  responseFn?: ReplyFactory;
  failure?: Failure;
  failureCommand?: string; // which command triggers `failure` (e.g. "auth")
  failureAfterReply?: boolean; // reply normally first, then apply `failure`
}

// The certificate and key are checked-in fixtures used by the TLS server.
const here = dirname(fileURLToPath(import.meta.url));

/** A fake Photon cache server. */
export class MockCache {
  /**
   * Accepts any AUTH, serves a canned config, and answers each `photon.request`
   * with `responseFn(request)`, a fixed `response`, or the default built from
   * `responseStatus`/`responseHeaders`/`responseBody`.
   *
   * Records everything received (`receivedCommands`, `photonCommandLog`,
   * `requests`). `failure: "hang"` never replies; `failure: "drop"` closes the
   * connection instead of replying. `failureAfterReply` sends the normal reply
   * first and then misbehaves. `failureCommand` selects the command that
   * triggers it (default `photon.request`; use `"auth"` to break the handshake).
   *
   * Plaintext by default; `tls: true` serves TLS using the checked-in fixtures.
   */
  readonly tls: boolean;
  readonly responseStatus: number;
  readonly responseHeaders: string;
  readonly responseBody: Buffer;

  // Recording, for test assertions.
  readonly receivedCommands: Buffer[][] = [];
  readonly photonCommandLog: string[] = [];
  readonly requests: ReceivedRequest[] = [];
  readonly host = "127.0.0.1";
  port?: number;
  certPath?: string;
  keyPath?: string;

  // Contract-engine state.
  private readonly config: Record<string, unknown>;
  private readonly authResponse?: Reply;
  private readonly response?: Reply;
  private readonly responseFn?: ReplyFactory;
  private readonly failure?: Failure;
  private readonly failureCommand: string;
  private readonly failureAfterReply: boolean;

  // Server state.
  private server?: NetServer | TlsServer;
  private sockets = new Set<Socket>();

  constructor(options: MockCacheOptions = {}) {
    this.config =
      options.config && Object.keys(options.config).length > 0
        ? options.config
        : { version: "v1", refresh_interval: 60, predicates: [] };
    this.tls = options.tls ?? false;
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
  }

  /** Return the host and active ephemeral port. */
  get endpoint(): string {
    return `${this.host}:${this.port}`;
  }

  /**
   * Raw arg list (command name excluded) of the most recent `photon.request`.
   * Returns `[method, url, headers, body, metadata, ...optional TTL/NTTL]`.
   */
  get lastRequestArgs(): Buffer[] | undefined {
    return this.requests.at(-1)?.rawArgs;
  }

  /** The most recent decoded `photon.request` record, or `undefined`. */
  get lastRequest(): ReceivedRequest | undefined {
    return this.requests.at(-1);
  }

  /**
   * Turn one decoded RESP command into the RESP reply frame.
   * Pure: no socket, no threads.
   *
   * Validates command shape, records the command, parses a `photon.request`
   * including optional `TTL`/`NTTL`, and selects the reply.
   */
  handle(command: Buffer[]): Buffer {
    this.receivedCommands.push(command);
    const name = command[0]?.toString("utf8").toLowerCase() ?? "";
    const args = command.slice(1);

    if (name === "auth") {
      this.photonCommandLog.push(name);
      return this.authResponse?.toBytes() ?? Buffer.concat([Buffer.from("+OK"), CRLF]);
    }
    if (name === CONFIG_COMMAND) {
      this.photonCommandLog.push(name);
      if (args.length !== 1) return _error("CONFIG_INVALID", `expected 1 arg, got ${args.length}`);
      return _bulk(Buffer.from(JSON.stringify(this.config)));
    }
    if (name === REQUEST_COMMAND) {
      this.photonCommandLog.push(name);
      if (args.length < 5) {
        return _error("ERR", `malformed photon.request: ${args.length} args (need >= 5)`);
      }
      const parsed = this.parseRequest(args);
      this.requests.push(parsed);
      return this.replyFor(parsed).toBytes();
    }
    return _error("ERR", "unknown command");
  }

  /** Decode the 5 mandatory args plus optional `TTL <n>` / `NTTL <n>` keyword args. */
  private parseRequest(args: Buffer[]): ReceivedRequest {
    const [method, url, headers, body, metadata] = args;
    const request: ReceivedRequest = {
      method: method!,
      url: url!,
      headers: headers!,
      body: body!,
      metadata: metadata!,
      rawArgs: [...args],
    };
    for (let index = 5; index < args.length - 1; index += 1) {
      const keyword = args[index]!.toString("ascii").toUpperCase();
      if (keyword === "TTL" || keyword === "NTTL") index += 1;
      else continue;

      const value = parseCacheInteger(args[index]!);
      if (keyword === "TTL") request.ttl = value;
      if (keyword === "NTTL") request.nttl = value;
    }
    return request;
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
            this.photonCommandLog.push(name);
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
    kind = Buffer.from("photon.response", "ascii"),
  ): void {
    const socket = this.onlySocket();
    socket.write(_push([_bulk(kind), _int(requestId), reply.toBytes()]));
  }

  private onlySocket(): Socket {
    const socket = this.sockets.values().next().value as Socket | undefined;
    if (socket === undefined) {
      throw new Error("mock cache has no active client socket");
    }
    return socket;
  }
}

/** Parse a cache-protocol decimal integer from command bytes. */
function parseCacheInteger(value: Buffer): number | bigint {
  const text = value.toString("latin1");
  // TTL/NTTL values are sent as canonical decimal bytes. An optional sign and
  // decimal digits are accepted; for example, "+30" is valid, while "1.5",
  // "0x10", "1_000", and surrounding whitespace are rejected.
  if (!/^[+-]?\d+$/.test(text)) {
    throw new Error(`Invalid integer: ${value.toString("utf8")}`);
  }

  const integer = BigInt(text);
  const number = Number(integer);
  return Number.isSafeInteger(number) ? number : integer;
}
