import { connect as connectTcp, type Socket } from "node:net";
import {
  connect as connectTls,
  type ConnectionOptions as TlsConnectionOptions,
  type TLSSocket,
} from "node:tls";

import {
  PhotonConnectionLost,
  PhotonConnectionError,
  PhotonError,
  PhotonProtocolError,
  PhotonTimeoutError,
} from "./errors.js";
import { PUSH_KIND_RESPONSE, REQUEST_COMMAND } from "./protocol.js";
import {
  decodeResp,
  IncompleteFrame,
  RespError,
  RespPush,
  encodeResp,
  STRICT_UTF8,
  type RespValue,
} from "./resp.js";

const DEFAULT_READ_SIZE = 64 * 1024;
const DEFAULT_CONNECT_TIMEOUT = 1_000; // TCP connect and TLS handshake.
const DEFAULT_REQUEST_TIMEOUT = 5_000;
const DEFAULT_CONFIG_TIMEOUT = 2_000; // Initialization commands: HELLO and photon.config.
const DEFAULT_MAX_RESPONSE_SIZE = 16 * 1024 * 1024;
const RESP3_PROTOCOL = 3;
const HELLO_PROTO_KEY = Buffer.from("proto", "ascii");

function validatePositiveInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new PhotonError(`${name} must be a positive integer`);
  }
  return value;
}

/** An async byte source used by `SocketReader`, including a Node TCP/TLS socket. */
export type SocketChunkSource = AsyncIterable<Buffer>;

/** Read one complete RESP frame at a time from a byte stream. */
export class SocketReader {
  private readonly iterator: AsyncIterator<Buffer>;
  private readonly readSize: number;
  private readonly maxResponseSize: number;
  private buffer = Buffer.alloc(0);
  private pendingChunk: Buffer<ArrayBufferLike> = Buffer.alloc(0);

  constructor(
    source: SocketChunkSource,
    readSize = DEFAULT_READ_SIZE,
    maxResponseSize = DEFAULT_MAX_RESPONSE_SIZE,
  ) {
    this.iterator = source[Symbol.asyncIterator]();
    this.readSize = validatePositiveInteger(readSize, "readSize");
    this.maxResponseSize = validatePositiveInteger(maxResponseSize, "maxResponseSize");
  }

  /** Accumulate chunks until `decodeResp` can return one frame, retaining trailing bytes. */
  async readFrame(): Promise<RespValue> {
    while (true) {
      try {
        const [value, consumed] = decodeResp(this.buffer);
        if (consumed > this.maxResponseSize) {
          throw new PhotonProtocolError(
            `maximum response size of ${this.maxResponseSize} bytes exceeded`,
          );
        }
        this.buffer = this.buffer.subarray(consumed);
        return value;
      } catch (error) {
        if (!(error instanceof IncompleteFrame)) throw error;
        this.ensureWithinLimit();
      }

      const chunk = await this.readChunk();
      this.buffer = Buffer.concat([this.buffer, chunk]);
    }
  }

  private async readChunk(): Promise<Buffer> {
    while (this.pendingChunk.length === 0) {
      const next = await this.iterator.next();
      if (next.done) {
        throw new PhotonConnectionLost("connection closed by peer");
      }
      if (!Buffer.isBuffer(next.value)) {
        throw new PhotonProtocolError("socket yielded a non-buffer chunk");
      }
      if (next.value.length === 0) {
        throw new PhotonConnectionLost("connection closed by peer");
      }
      this.pendingChunk = next.value;
    }

    // Limit each append to one byte beyond the configured frame bound. A source
    // chunk may contain several valid frames, so the source chunk itself cannot
    // be rejected solely because its aggregate size exceeds the bound.
    const chunk = this.pendingChunk.subarray(0, Math.min(this.readSize, this.maxResponseSize + 1));
    this.pendingChunk = this.pendingChunk.subarray(chunk.length);
    return chunk;
  }

  /** Expose the configured read size for diagnostics and test doubles. */
  get chunkSize(): number {
    return this.readSize;
  }

  private ensureWithinLimit(): void {
    if (this.buffer.length > this.maxResponseSize) {
      throw new PhotonProtocolError(
        `maximum response size of ${this.maxResponseSize} bytes exceeded`,
      );
    }
  }
}

interface PendingRequest {
  readonly promise: Promise<RespValue>;
  resolve: (value: RespValue) => void;
  reject: (reason: unknown) => void;
  settled: boolean;
  writeCompleted: boolean;
  deferredResponseAllowed: boolean;
}

/** Thread-safe-in-spirit FIFO for promises registered by concurrent callers. */
export class PendingRequests {
  private readonly queue: PendingRequest[] = [];
  private readonly asyncResponses = new Map<string, PendingRequest>();

  constructor(private readonly maxPending = 100) {
    validatePositiveInteger(maxPending, "maxPending");
  }

  registerNewRequest(deferredResponseAllowed = true): PendingRequest {
    if (this.queue.length + this.asyncResponses.size >= this.maxPending) {
      throw new PhotonError(`maximum pending requests of ${this.maxPending} reached`);
    }

    let resolveRequest!: (value: RespValue) => void;
    let rejectRequest!: (reason: unknown) => void;
    const promise = new Promise<RespValue>((resolve, reject) => {
      resolveRequest = resolve;
      rejectRequest = reject;
    });
    const request: PendingRequest = {
      promise,
      resolve: resolveRequest,
      reject: rejectRequest,
      settled: false,
      writeCompleted: false,
      deferredResponseAllowed,
    };
    this.queue.push(request);
    return request;
  }

  resolveOldestRequest(value: RespValue): void {
    const request = this.queue.shift();
    if (request === undefined || request.settled) return;
    request.settled = true;
    request.resolve(value);
  }

  moveNextToAsyncResponses(requestId: string): boolean {
    if (this.queue.length === 0) {
      throw new PhotonProtocolError(
        `received async response id without a pending FIFO request: ${requestId}`,
      );
    }
    if (!this.queue[0]!.deferredResponseAllowed) {
      return false;
    }
    if (this.asyncResponses.has(requestId)) {
      throw new PhotonProtocolError(`duplicate async response id: ${requestId}`);
    }
    this.asyncResponses.set(requestId, this.queue.shift()!);
    return true;
  }

  resolveAsync(requestId: string, value: RespValue): boolean {
    const request = this.asyncResponses.get(requestId);
    if (request === undefined) return false;
    this.asyncResponses.delete(requestId);
    if (request.settled) return true;
    request.settled = true;
    request.resolve(value);
    return true;
  }

  removeFromAsync(request: PendingRequest): boolean {
    for (const [requestId, candidate] of this.asyncResponses) {
      if (candidate === request) {
        this.asyncResponses.delete(requestId);
        return true;
      }
    }
    return false;
  }

  failAll(error: unknown): void {
    const requests = this.queue.splice(0);
    requests.push(...this.asyncResponses.values());
    this.asyncResponses.clear();
    for (const request of requests) {
      if (request.settled) continue;
      request.settled = true;
      request.reject(error);
    }
  }
}

interface SentRequest {
  readonly pending: PendingRequest;
  readonly response: Promise<RespValue>;
}

export interface PhotonConnectorOptions {
  /** Use TLS with hostname verification. Defaults to `true`. */
  tls?: boolean;
  /** TCP/TLS establishment timeout in milliseconds. */
  connectTimeout?: number;
  /** Per-request response timeout in milliseconds. Defaults to 5000. */
  requestTimeout?: number;
  /** Timeout for HELLO and photon.config initialization commands in milliseconds. */
  configTimeout?: number;
  /** PEM certificate authority data passed to Node's TLS client. */
  caCerts?: string | Buffer | Array<string | Buffer>;
  /**
   * Accept the server's certificate without verifying it. Local and test endpoints only.
   *
   * Distinct from `tls: false`, which drops TLS entirely: this keeps the encrypted channel
   * and waives only the identity check, which is what a self-signed endpoint needs. SNI is
   * still sent, so a server that routes on it is unaffected.
   *
   * Prefer `caCerts` whenever the endpoint's CA can be supplied; reach for this only when it
   * cannot, such as a certificate minted per test run for a name that is not the one dialled.
   */
  insecureSkipVerify?: boolean;
  /** Maximum size requested from a socket read. */
  readSize?: number;
  /** Maximum size of one RESP response frame in bytes. Defaults to 16 MiB. */
  maxResponseSize?: number;
  /**
   * Maximum number of requests still awaiting a reply, including deferred
   * requests awaiting their async `photon.response` push. Defaults to 100.
   */
  maxPending?: number;
  /** Provider for the `(userId, token)` credentials carried by the HELLO handshake. */
  authProvider?: AuthProvider;
}

/**
 * A callback or provider object that supplies credentials for `HELLO 3 AUTH`.
 *
 * The object form is the Node equivalent of Python's callable provider class.
 */
export type AuthProvider =
  | ((() => [string, string] | Promise<[string, string]>) & {
      refreshAuth?: () => [string, string] | Promise<[string, string]>;
    })
  | {
      getAuth: () => [string, string] | Promise<[string, string]>;
      refreshAuth?: () => [string, string] | Promise<[string, string]>;
    };

class PhotonHandshakeRejected extends PhotonConnectionError {}

type ConnectedSocket = Socket | TLSSocket;

let warnedInsecureSkipVerify = false;

/**
 * Announce once per process that certificate verification is off.
 *
 * Emitted from the handshake that actually waives verification, not from construction: a
 * connector built with `tls: false`, or one that never connects, must not consume the latch and
 * leave the first genuinely unverified handshake silent.
 *
 * A silent opt-out is the dangerous one: this is the only signal that separates a test rig
 * from a production misconfiguration, so it is emitted rather than logged and carries its own
 * name for a consumer that filters warnings.
 */
function warnInsecureSkipVerify(): void {
  if (warnedInsecureSkipVerify) return;
  warnedInsecureSkipVerify = true;
  process.emitWarning(
    "Photon TLS certificate verification is disabled (insecureSkipVerify). " +
      "Expected only against a local or test endpoint.",
    "PhotonInsecureTlsWarning",
  );
}

/**
 * A single Photon TCP/TLS connection with serialized writes and FIFO/request-ID responses.
 *
 * Positional and write timeouts close the connection; deferred request-ID timeouts remove only
 * that request because a late push cannot be mis-correlated with a later FIFO response.
 */
export class PhotonConnector {
  private readonly host: string;
  private readonly port: number;
  private readonly useTls: boolean;
  private readonly connectTimeout: number;
  private readonly requestTimeout: number;
  private readonly configTimeout: number;
  private readonly caCerts: PhotonConnectorOptions["caCerts"];
  private readonly insecureSkipVerify: boolean;
  private readonly readSize: number;
  private readonly maxResponseSize: number;
  private readonly authProvider: PhotonConnectorOptions["authProvider"];

  private socket: ConnectedSocket | undefined;
  private openingSocket: ConnectedSocket | undefined;
  // Kept while the reader is active so close() can still await it after destroy().
  private readerTask: Promise<void> | undefined;
  private readonly pending: PendingRequests;
  private connecting = false;
  private dead = false;
  private closed = false;
  private disconnectNotified = false;

  /** Called once when this connector ends, including deliberate close. */
  onDisconnect: (() => void) | undefined;

  constructor(host: string, port: number, options: PhotonConnectorOptions = {}) {
    this.host = host;
    this.port = port;
    this.useTls = options.tls ?? true;
    this.connectTimeout = options.connectTimeout ?? DEFAULT_CONNECT_TIMEOUT;
    this.requestTimeout = options.requestTimeout ?? DEFAULT_REQUEST_TIMEOUT;
    this.configTimeout = options.configTimeout ?? DEFAULT_CONFIG_TIMEOUT;
    this.caCerts = options.caCerts;
    this.insecureSkipVerify = options.insecureSkipVerify ?? false;
    this.readSize = validatePositiveInteger(options.readSize ?? DEFAULT_READ_SIZE, "readSize");
    this.maxResponseSize = validatePositiveInteger(
      options.maxResponseSize ?? DEFAULT_MAX_RESPONSE_SIZE,
      "maxResponseSize",
    );
    this.pending = new PendingRequests(options.maxPending ?? 100);
    this.authProvider = options.authProvider;
  }

  /** Open the socket, perform TLS when enabled, and start the background reader. */
  async connect(): Promise<void> {
    if (this.closed) {
      throw new PhotonError("PhotonConnector used after close()");
    }
    if (this.socket !== undefined) {
      throw new PhotonError("PhotonConnector is already connected");
    }
    if (this.connecting) {
      throw new PhotonError("PhotonConnector is already connecting");
    }

    this.connecting = true;
    let socket: ConnectedSocket | undefined;
    try {
      socket = await this.openTcp();
      this.openingSocket = socket;
      if (this.useTls) {
        socket = await this.openTls(socket);
        this.openingSocket = socket;
      }
      socket.setNoDelay(true);
      socket.setTimeout(0);
      if (this.closed) {
        socket.destroy();
        throw new PhotonConnectionError("PhotonConnector closed during connect()");
      }
      this.socket = socket;
      this.openingSocket = undefined;
      this.dead = false;
      const reader = new SocketReader(socket, this.readSize, this.maxResponseSize);
      const readerTask = this.readerLoop(reader);
      this.readerTask = readerTask;
      // destroy() is synchronous, so release the handle when the reader settles even
      // when callers never follow it with close().
      void readerTask.then(
        () => this.releaseReaderTask(readerTask), // normal reader shutdown
        () => this.releaseReaderTask(readerTask), // unexpected reader failure
      );
    } catch (error) {
      socket?.destroy();
      if (error instanceof PhotonConnectionError) throw error;
      throw new PhotonConnectionError(`Failed to connect to ${this.host}:${this.port}`, {
        cause: error,
      });
    } finally {
      this.openingSocket = undefined;
      this.connecting = false;
    }
  }

  /** Negotiate RESP3 and authenticate in one `HELLO 3 AUTH <user> <token>` exchange. */
  async handshake(): Promise<void> {
    if (this.authProvider === undefined) {
      throw new PhotonConnectionError("PhotonConnector requires an auth provider to handshake");
    }

    let credentials: [string, string];
    try {
      credentials = await (typeof this.authProvider === "function"
        ? this.authProvider()
        : this.authProvider.getAuth());
    } catch (error) {
      throw new PhotonError("AUTH provider failed", { cause: error });
    }

    try {
      const reply = await this.execute(
        "HELLO",
        Buffer.from("3", "ascii"),
        Buffer.from("AUTH", "ascii"),
        Buffer.from(credentials[0], "utf8"),
        Buffer.from(credentials[1], "utf8"),
      );
      this.verifyHelloReply(reply);
    } catch (error) {
      if (error instanceof PhotonTimeoutError) {
        await this.close();
        throw new PhotonConnectionError("HELLO 3 timed out — connection closed", { cause: error });
      }
      if (error instanceof PhotonConnectionLost) {
        await this.close();
        throw new PhotonConnectionError("HELLO 3 failed: connection lost", { cause: error });
      }
      if (error instanceof PhotonHandshakeRejected) {
        try {
          await this.authProvider.refreshAuth?.();
        } catch {
          // Preserve the cache's HELLO rejection; the next attempt retries token refresh.
        }
      }
      throw error;
    }
  }

  /** Immediately stop reading, fail pending requests, and destroy the socket. */
  destroy(): void {
    if (this.closed) return;
    this.closed = true;
    this.dead = true;
    this.pending.failAll(new PhotonConnectionLost("connection closed"));
    const socket = this.socket;
    try {
      this.openingSocket?.destroy();
      socket?.destroy();
    } finally {
      this.openingSocket = undefined;
      this.socket = undefined;
      this.notifyDisconnect();
    }
  }

  /** Stop reading, fail pending requests, and destroy the socket. Safe to call twice. */
  async close(): Promise<void> {
    const readerTask = this.readerTask;
    this.destroy();
    try {
      await readerTask;
    } finally {
      this.releaseReaderTask(readerTask);
    }
  }

  /** Send one command and resolve with its raw RESP reply. */
  async execute(command: string, ...args: Buffer[]): Promise<RespValue> {
    if (this.socket === undefined) {
      if (this.closed) {
        throw new PhotonConnectionLost("connection is closed");
      }
      throw new PhotonError("PhotonConnector used before connect()");
    }
    if (this.dead) {
      throw new PhotonConnectionLost("connection is closed");
    }

    let sent: SentRequest | undefined;
    try {
      sent = this.send(command, ...args);
      return await this.waitForResponse(sent.response, this.timeoutFor(command));
    } catch (error) {
      if (error instanceof PhotonTimeoutError) {
        // Once the request has moved out of the FIFO, a late response cannot be matched to
        // another caller. Remove that map entry and preserve the connection.
        if (
          sent !== undefined &&
          sent.pending.writeCompleted &&
          this.pending.removeFromAsync(sent.pending)
        ) {
          const asyncTimeout = new PhotonTimeoutError(
            `async request timed out after ${this.timeoutFor(command)}ms`,
          );
          sent.pending.settled = true;
          sent.pending.reject(asyncTimeout);
          throw asyncTimeout;
        }

        // A positional or partially-written request still makes a late reply unsafe.
        await this.close();
      }
      throw error;
    }
  }

  /** Whether this connector has failed or been closed and must be replaced. */
  get isDead(): boolean {
    return this.dead || this.closed;
  }

  /** Whether this connector currently has a usable socket. */
  get isReady(): boolean {
    return this.socket !== undefined && !this.dead && !this.closed;
  }

  private send(command: string, ...args: Buffer[]): SentRequest {
    if (this.dead || this.socket === undefined) {
      throw new PhotonConnectionLost("connection is closed");
    }

    const frame = encodeResp(command, ...args);
    const response = this.pending.registerNewRequest(command.toLowerCase() === REQUEST_COMMAND);
    const socket = this.socket;
    if (socket === undefined) {
      throw new PhotonConnectionLost("connection is closed");
    }

    return {
      pending: response,
      response: this.writeAndWait(socket, frame, response),
    };
  }

  /**
   * Complete the frame write and await its FIFO-correlated response.
   *
   * A failure before the write completes may leave a partial frame on the stream,
   * so the connection must be closed to protect response correlation. Once the
   * write phase completes, response failures propagate through the normal path.
   */
  private async writeAndWait(
    socket: ConnectedSocket,
    frame: Buffer,
    pending: PendingRequest,
  ): Promise<RespValue> {
    // This is the local write/drain boundary, not an acknowledgement from the server.
    let writeCompleted = false;
    try {
      // Node queues socket writes synchronously in call order, so registration and
      // write remain atomic within this event-loop turn without an async lock.
      if (!socket.write(frame)) {
        // Backpressure is reported asynchronously through `drain`; keep waiting bounded.
        await this.waitForDrain(socket);
      }
      // Errors after this point are response/connection failures, not send failures.
      writeCompleted = true;
      pending.writeCompleted = true;
      return await pending.promise;
    } catch (error) {
      if (writeCompleted) throw error;
      // The frame may be partially written. Reusing the stream could mis-correlate a late
      // response, so close the connection and fail every pending request.
      const failure =
        error instanceof PhotonTimeoutError || error instanceof PhotonConnectionLost
          ? error
          : new PhotonConnectionLost("send failed", { cause: error });
      // `close()` rejects this response too; consume that rejection because the send error
      // is the failure reported to the caller.
      void pending.promise.catch(() => undefined);
      await this.close();
      throw failure;
    }
  }

  /**
   * Select the response timeout for a command.
   *
   * HELLO and photon.config run during connection initialization and use the
   * larger config timeout. Send/drain waits remain bounded by requestTimeout.
   */
  private timeoutFor(command: string): number {
    // Bring-up commands get a larger response budget; send-side waits stay on requestTimeout.
    return command === "HELLO" || command === "photon.config"
      ? this.configTimeout
      : this.requestTimeout;
  }

  /**
   * Wait for Node's writable buffer to drain within the request send budget.
   *
   * A drain, socket error, or close settles the wait; all listeners are removed
   * on either outcome so a late event cannot affect a later request.
   */
  private async waitForDrain(socket: ConnectedSocket): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => {
        finishReject(new PhotonTimeoutError(`send timed out after ${this.requestTimeout}ms`));
      }, this.requestTimeout);

      const cleanup = () => {
        clearTimeout(timer);
        socket.removeListener("drain", onDrain);
        socket.removeListener("error", onError);
        socket.removeListener("close", onClose);
      };
      const finishResolve = () => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve();
      };
      const finishReject = (error: Error) => {
        if (settled) return;
        settled = true;
        cleanup();
        reject(error);
      };
      const onDrain = () => finishResolve();
      const onError = (error: Error) =>
        finishReject(new PhotonConnectionLost("send failed", { cause: error }));
      const onClose = () => finishReject(new PhotonConnectionLost("connection closed"));

      socket.once("drain", onDrain);
      socket.once("error", onError);
      socket.once("close", onClose);
    });
  }

  private async openTcp(): Promise<Socket> {
    return new Promise<Socket>((resolve, reject) => {
      const socket = connectTcp({ host: this.host, port: this.port });
      this.openingSocket = socket;
      let settled = false;
      const timer = setTimeout(() => {
        const error = new PhotonConnectionError(
          `Timed out connecting to ${this.host}:${this.port}`,
        );
        socket.destroy();
        finish(error);
      }, this.connectTimeout);

      const finish = (value: Socket | Error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        socket.removeListener("connect", onConnect);
        socket.removeListener("error", onError);
        socket.removeListener("close", onClose);
        socket.removeListener("timeout", onTimeout);
        if (value instanceof Error) reject(value);
        else resolve(value);
      };
      const onConnect = () => finish(socket);
      const onError = (error: Error) => {
        const wrapped = new PhotonConnectionError(
          `Failed to connect to ${this.host}:${this.port}`,
          { cause: error },
        );
        socket.destroy();
        finish(wrapped);
      };
      const onClose = () => {
        finish(
          new PhotonConnectionError(
            this.closed
              ? "PhotonConnector closed during connect()"
              : `Connection closed while connecting to ${this.host}:${this.port}`,
          ),
        );
      };
      const onTimeout = () => {
        const error = new PhotonConnectionError(
          `Timed out connecting to ${this.host}:${this.port}`,
        );
        socket.destroy();
        finish(error);
      };

      socket.once("connect", onConnect);
      socket.once("error", onError);
      socket.once("close", onClose);
      socket.once("timeout", onTimeout);
    });
  }

  private async openTls(socket: Socket): Promise<TLSSocket> {
    return new Promise<TLSSocket>((resolve, reject) => {
      const options: TlsConnectionOptions = {
        socket,
        servername: this.host,
        minVersion: "TLSv1.2",
        rejectUnauthorized: !this.insecureSkipVerify,
      };
      if (this.caCerts !== undefined) options.ca = this.caCerts;
      if (this.insecureSkipVerify) warnInsecureSkipVerify();

      const secured = connectTls(options);
      this.openingSocket = secured;
      let settled = false;
      const timer = setTimeout(() => {
        const error = new PhotonConnectionError(
          `Timed out during TLS handshake to ${this.host}:${this.port}`,
        );
        secured.destroy();
        finish(error);
      }, this.connectTimeout);

      const finish = (value: TLSSocket | Error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        secured.removeListener("secureConnect", onSecureConnect);
        secured.removeListener("error", onError);
        secured.removeListener("close", onClose);
        if (value instanceof Error) reject(value);
        else resolve(value);
      };
      const onSecureConnect = () => finish(secured);
      const onError = (error: Error) => {
        const wrapped = new PhotonConnectionError(
          `TLS handshake failed to ${this.host}:${this.port}`,
          { cause: error },
        );
        secured.destroy();
        finish(wrapped);
      };
      const onClose = () => {
        finish(
          new PhotonConnectionError(
            this.closed
              ? "PhotonConnector closed during connect()"
              : `TLS connection closed during handshake to ${this.host}:${this.port}`,
          ),
        );
      };

      secured.once("secureConnect", onSecureConnect);
      secured.once("error", onError);
      secured.once("close", onClose);
    });
  }

  private async readerLoop(reader: SocketReader): Promise<void> {
    try {
      while (!this.dead) {
        const value = await reader.readFrame();
        if (value instanceof RespPush) {
          this.handlePush(value.items);
        } else if (
          typeof value === "string" &&
          value !== "OK" &&
          this.pending.moveNextToAsyncResponses(value)
        ) {
          continue;
        } else {
          this.pending.resolveOldestRequest(value);
        }
      }
    } catch (error) {
      if (this.dead) return;
      const failure =
        error instanceof PhotonConnectionLost
          ? error
          : error instanceof PhotonProtocolError
            ? new PhotonConnectionLost("reply stream corrupt", { cause: error })
            : new PhotonConnectionLost("socket read failed", { cause: error });
      this.markDead(failure);
    }
  }

  private handlePush(items: RespValue[]): void {
    const [kind] = items;
    if (items.length === 0 || (!Buffer.isBuffer(kind) && typeof kind !== "string")) {
      throw new PhotonProtocolError("Malformed RESP push payload");
    }

    const kindMatches = Buffer.isBuffer(kind)
      ? kind.equals(PUSH_KIND_RESPONSE)
      : kind === PUSH_KIND_RESPONSE.toString("ascii");
    if (!kindMatches) {
      throw new PhotonProtocolError(`Unknown RESP push kind: ${String(kind)}`);
    }
    if (items.length !== 3) {
      throw new PhotonProtocolError(
        `Malformed ${PUSH_KIND_RESPONSE.toString("ascii")} push payload`,
      );
    }

    const requestId = items[1];
    const response = items[2]!;
    if (!Buffer.isBuffer(requestId)) {
      throw new PhotonProtocolError(
        `Malformed ${PUSH_KIND_RESPONSE.toString("ascii")} push request id`,
      );
    }
    // Decode with the same strict UTF-8 codec used for the simple-string request
    // id that seeded the FIFO->async correlation key, so the two keys always
    // agree; a looser or differently-lossy decoding could mis-correlate a
    // deferred response with a different in-flight request.
    let requestIdKey: string;
    try {
      requestIdKey = STRICT_UTF8.decode(requestId);
    } catch {
      throw new PhotonProtocolError(
        `Malformed ${PUSH_KIND_RESPONSE.toString("ascii")} push request id`,
      );
    }
    this.pending.resolveAsync(requestIdKey, response);
  }

  private releaseReaderTask(readerTask: Promise<void> | undefined): void {
    // Do not let an older completion clear a newer reader task.
    if (this.readerTask === readerTask) {
      this.readerTask = undefined;
    }
  }

  private markDead(error: Error): void {
    if (this.dead) return;
    this.dead = true;
    this.pending.failAll(error);
    this.socket?.destroy();
    this.notifyDisconnect();
  }

  private notifyDisconnect(): void {
    if (this.disconnectNotified) return;
    this.disconnectNotified = true;
    try {
      this.onDisconnect?.();
    } catch {
      // Owner notification must not escape the socket reader or teardown path.
    }
  }

  private verifyHelloReply(reply: RespValue): void {
    if (reply instanceof RespError) {
      this.markDead(new PhotonConnectionLost("HELLO 3 rejected"));
      throw new PhotonHandshakeRejected(`HELLO 3 rejected: ${reply.code}`);
    }
    if (!(reply instanceof Map)) {
      const error = new PhotonConnectionLost("unexpected HELLO 3 reply shape");
      this.markDead(error);
      throw new PhotonConnectionError(
        `HELLO 3 failed: expected a map reply, got ${describeRespType(reply)}`,
      );
    }

    let protocol: RespValue | undefined;
    for (const [key, value] of reply) {
      if (Buffer.isBuffer(key) && key.equals(HELLO_PROTO_KEY)) {
        protocol = value;
        break;
      }
    }
    if (protocol !== RESP3_PROTOCOL) {
      const error = new PhotonConnectionLost("HELLO 3 reply did not confirm RESP3");
      this.markDead(error);
      throw new PhotonConnectionError(
        `HELLO 3 failed: reply did not report proto ${RESP3_PROTOCOL}`,
      );
    }
  }

  private async waitForResponse(response: Promise<RespValue>, timeout: number): Promise<RespValue> {
    return new Promise<RespValue>((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new PhotonTimeoutError(`request timed out after ${timeout}ms`));
      }, timeout);

      response.then(
        (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        (error: unknown) => {
          clearTimeout(timer);
          reject(error);
        },
      );
    });
  }
}

function describeRespType(value: RespValue): string {
  if (value === null) return "null";
  if (Buffer.isBuffer(value)) return "bulk string";
  if (Array.isArray(value)) return "array";
  if (value instanceof Map) return "map";
  if (value instanceof RespPush) return "push";
  if (value instanceof RespError) return "error";
  return typeof value;
}
