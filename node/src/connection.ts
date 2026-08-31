import { connect as connectTcp, type Socket } from "node:net";
import {
  connect as connectTls,
  type ConnectionOptions as TlsConnectionOptions,
  type TLSSocket,
} from "node:tls";

import {
  PhotonConnectionLostError,
  PhotonConnectionError,
  PhotonError,
  PhotonProtocolError,
  PhotonTimeoutError,
} from "./errors.js";
import { decodeResp, IncompleteFrameError, RespError, encodeResp, type RespValue } from "./resp.js";

const DEFAULT_READ_SIZE = 64 * 1024;
const DEFAULT_CONNECT_TIMEOUT = 1_000; // TCP connect and TLS handshake.
const DEFAULT_REQUEST_TIMEOUT = 100; // Covers the slowest legitimate cache miss (proxy -> DDB -> proxy).
const DEFAULT_CONFIG_TIMEOUT = 2_000; // Initialization commands: AUTH and photon.config.
const DEFAULT_MAX_RESPONSE_SIZE = 16 * 1024 * 1024;

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
        if (!(error instanceof IncompleteFrameError)) throw error;
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
        throw new PhotonConnectionLostError("connection closed by peer");
      }
      if (!Buffer.isBuffer(next.value)) {
        throw new PhotonProtocolError("socket yielded a non-buffer chunk");
      }
      if (next.value.length === 0) {
        throw new PhotonConnectionLostError("connection closed by peer");
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
  resolve: (value: RespValue) => void;
  reject: (reason: unknown) => void;
  settled: boolean;
}

/** Thread-safe-in-spirit FIFO for promises registered by concurrent callers. */
export class PendingRequests {
  private readonly queue: PendingRequest[] = [];

  constructor(private readonly maxPendingRequests = 100) {
    validatePositiveInteger(maxPendingRequests, "maxPendingRequests");
  }

  register(): Promise<RespValue> {
    if (this.queue.length >= this.maxPendingRequests) {
      throw new PhotonError(`maximum pending requests of ${this.maxPendingRequests} reached`);
    }
    return new Promise<RespValue>((resolve, reject) => {
      this.queue.push({ resolve, reject, settled: false });
    });
  }

  resolveNext(value: RespValue): void {
    const request = this.queue.shift();
    if (request === undefined || request.settled) return;
    request.settled = true;
    request.resolve(value);
  }

  failAll(error: unknown): void {
    const requests = this.queue.splice(0);
    for (const request of requests) {
      if (request.settled) continue;
      request.settled = true;
      request.reject(error);
    }
  }
}

export interface PhotonConnectionOptions {
  /** Use TLS with hostname verification. Defaults to `true`. */
  tls?: boolean;
  /** TCP/TLS establishment timeout in milliseconds. */
  connectTimeout?: number;
  /** Per-request response timeout in milliseconds. */
  requestTimeout?: number;
  /** Timeout for AUTH and photon.config initialization commands in milliseconds. */
  configTimeout?: number;
  /** PEM certificate authority data passed to Node's TLS client. */
  caCerts?: string | Buffer | Array<string | Buffer>;
  /** Maximum size requested from a socket read. */
  readSize?: number;
  /** Maximum size of one RESP response frame in bytes. Defaults to 16 MiB. */
  maxResponseSize?: number;
  /** Maximum number of registered requests. Defaults to 100. */
  maxPendingRequests?: number;
  /** Provider for the `(userId, token)` credentials used by `AUTH`. */
  authProvider?: AuthProvider;
}

/** @deprecated Use `PhotonConnectionOptions`. */
export type PhotonConnectorOptions = PhotonConnectionOptions;

/**
 * A callback or provider object that supplies credentials for `AUTH`.
 *
 * The object form is the Node equivalent of Python's callable provider class.
 */
export type AuthProvider =
  | (() => [string, string] | Promise<[string, string]>)
  | { getAuth: () => [string, string] | Promise<[string, string]> };

type ConnectedSocket = Socket | TLSSocket;

/**
 * A single Photon TCP/TLS connection with serialized writes and FIFO responses.
 *
 * Timeouts close the connection because accepting a late response would make FIFO
 * correlation unsafe for every request sent afterward.
 */
export class PhotonConnection {
  private readonly host: string;
  private readonly port: number;
  private readonly useTls: boolean;
  private readonly connectTimeout: number;
  private readonly requestTimeout: number;
  private readonly configTimeout: number;
  private readonly caCerts: PhotonConnectionOptions["caCerts"];
  private readonly readSize: number;
  private readonly maxResponseSize: number;
  private readonly authProvider: PhotonConnectionOptions["authProvider"];

  private socket: ConnectedSocket | undefined;
  // Kept while the reader is active so close() can still await it after destroy().
  private readerTask: Promise<void> | undefined;
  private readonly pending: PendingRequests;
  private connecting = false;
  private dead = false;
  private closed = false;

  constructor(host: string, port: number, options: PhotonConnectionOptions = {}) {
    this.host = host;
    this.port = port;
    this.useTls = options.tls ?? true;
    this.connectTimeout = options.connectTimeout ?? DEFAULT_CONNECT_TIMEOUT;
    this.requestTimeout = options.requestTimeout ?? DEFAULT_REQUEST_TIMEOUT;
    this.configTimeout = options.configTimeout ?? DEFAULT_CONFIG_TIMEOUT;
    this.caCerts = options.caCerts;
    this.readSize = validatePositiveInteger(options.readSize ?? DEFAULT_READ_SIZE, "readSize");
    this.maxResponseSize = validatePositiveInteger(
      options.maxResponseSize ?? DEFAULT_MAX_RESPONSE_SIZE,
      "maxResponseSize",
    );
    this.pending = new PendingRequests(options.maxPendingRequests ?? 100);
    this.authProvider = options.authProvider;
  }

  /** Open the socket, perform TLS when enabled, and start the background reader. */
  async connect(): Promise<void> {
    if (this.closed) {
      throw new PhotonError("PhotonConnection used after close()");
    }
    if (this.socket !== undefined) {
      throw new PhotonError("PhotonConnection is already connected");
    }
    if (this.connecting) {
      throw new PhotonError("PhotonConnection is already connecting");
    }

    this.connecting = true;
    let socket: ConnectedSocket | undefined;
    try {
      socket = await this.openTcp();
      if (this.useTls) {
        socket = await this.openTls(socket);
      }
      socket.setNoDelay(true);
      socket.setTimeout(0);
      if (this.closed) {
        socket.destroy();
        throw new PhotonConnectionError("PhotonConnection closed during connect()");
      }
      this.socket = socket;
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
      this.connecting = false;
    }
  }

  /** Send AUTH with credentials from the injected provider, if configured. */
  async authenticate(): Promise<void> {
    if (this.authProvider === undefined) return;

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
        "AUTH",
        Buffer.from(credentials[0], "utf8"),
        Buffer.from(credentials[1], "utf8"),
      );
      this.verifyAuthReply(reply);
    } catch (error) {
      if (error instanceof PhotonTimeoutError) {
        await this.close();
        throw new PhotonConnectionError("IAM AUTH timed out", { cause: error });
      }
      throw error;
    }
  }

  /** Immediately stop reading, fail pending requests, and destroy the socket. */
  destroy(): void {
    if (this.closed) return;
    this.closed = true;
    this.dead = true;
    this.pending.failAll(new PhotonConnectionLostError("connection closed"));
    const socket = this.socket;
    try {
      socket?.destroy();
    } finally {
      this.socket = undefined;
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
        throw new PhotonConnectionLostError("connection is closed");
      }
      throw new PhotonError("PhotonConnection used before connect()");
    }
    if (this.dead) {
      throw new PhotonConnectionLostError("connection is closed");
    }

    try {
      const response = this.send(command, ...args);
      return await this.waitForResponse(response, this.timeoutFor(command));
    } catch (error) {
      if (error instanceof PhotonTimeoutError) {
        // FIFO correlation cannot safely discard a late reply, so one timeout resets the
        // connection and fails every other in-flight request as well.
        await this.close();
      }
      throw error;
    }
  }

  private send(command: string, ...args: Buffer[]): Promise<RespValue> {
    if (this.dead || this.socket === undefined) {
      throw new PhotonConnectionLostError("connection is closed");
    }

    const frame = encodeResp(command, ...args);
    const response = this.pending.register();
    const socket = this.socket;
    if (socket === undefined) {
      throw new PhotonConnectionLostError("connection is closed");
    }

    return this.writeAndWait(socket, frame, response);
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
    response: Promise<RespValue>,
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
      return await response;
    } catch (error) {
      if (writeCompleted) throw error;
      // The frame may be partially written. Reusing the stream could mis-correlate a late
      // response, so close the connection and fail every pending request.
      const failure =
        error instanceof PhotonTimeoutError || error instanceof PhotonConnectionLostError
          ? error
          : new PhotonConnectionLostError("send failed", { cause: error });
      // `close()` rejects this response too; consume that rejection because the send error
      // is the failure reported to the caller.
      void response.catch(() => undefined);
      await this.close();
      throw failure;
    }
  }

  /**
   * Select the response timeout for a command.
   *
   * AUTH and photon.config run during connection initialization and use the
   * larger config timeout. Send/drain waits remain bounded by requestTimeout.
   */
  private timeoutFor(command: string): number {
    // Bring-up commands get a larger response budget; send-side waits stay on requestTimeout.
    return command === "AUTH" || command === "photon.config"
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
        finishReject(new PhotonConnectionLostError("send failed", { cause: error }));
      const onClose = () => finishReject(new PhotonConnectionLostError("connection closed"));

      socket.once("drain", onDrain);
      socket.once("error", onError);
      socket.once("close", onClose);
    });
  }

  private async openTcp(): Promise<Socket> {
    return new Promise<Socket>((resolve, reject) => {
      const socket = connectTcp({ host: this.host, port: this.port });
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
      const onTimeout = () => {
        const error = new PhotonConnectionError(
          `Timed out connecting to ${this.host}:${this.port}`,
        );
        socket.destroy();
        finish(error);
      };

      socket.once("connect", onConnect);
      socket.once("error", onError);
      socket.once("timeout", onTimeout);
    });
  }

  private async openTls(socket: Socket): Promise<TLSSocket> {
    return new Promise<TLSSocket>((resolve, reject) => {
      const options: TlsConnectionOptions = {
        socket,
        servername: this.host,
        minVersion: "TLSv1.2",
        rejectUnauthorized: true,
      };
      if (this.caCerts !== undefined) options.ca = this.caCerts;

      const secured = connectTls(options);
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

      secured.once("secureConnect", onSecureConnect);
      secured.once("error", onError);
    });
  }

  private async readerLoop(reader: SocketReader): Promise<void> {
    try {
      while (!this.dead) {
        const value = await reader.readFrame();
        this.pending.resolveNext(value);
      }
    } catch (error) {
      if (this.dead) return;
      const failure =
        error instanceof PhotonConnectionLostError || error instanceof PhotonProtocolError
          ? error
          : new PhotonConnectionLostError("socket read failed", { cause: error });
      this.markDead(failure);
    }
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
  }

  private verifyAuthReply(reply: RespValue): void {
    if (reply instanceof RespError) {
      this.markDead(new PhotonConnectionLostError("AUTH rejected"));
      throw new PhotonConnectionError(`IAM AUTH rejected: ${reply.code}`);
    }
    if (!Buffer.isBuffer(reply) || !reply.equals(Buffer.from("OK"))) {
      const error = new PhotonConnectionLostError("unexpected AUTH reply");
      this.markDead(error);
      throw new PhotonConnectionError(`IAM AUTH failed: unexpected reply ${String(reply)}`);
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

/** @deprecated Use `PhotonConnection`. */
export const PhotonConnector = PhotonConnection;
/** @deprecated Use `PhotonConnection`. */
export type PhotonConnector = PhotonConnection;
