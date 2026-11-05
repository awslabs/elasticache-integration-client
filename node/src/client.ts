import { readFileSync } from "node:fs";

import type { AwsCredentialIdentity } from "@smithy/types";

import { buildCacheAuthProvider } from "./auth.js";
import {
  DEFAULT_CONNECTION_TIMEOUT,
  DEFAULT_REQUEST_TIMEOUT,
  ElastiCacheIntegrationsConnector,
  type ElastiCacheIntegrationsConnectorOptions,
} from "./connection.js";
import { ElastiCacheIntegrationsConfigError } from "./errors.js";
import {
  ElastiCacheIntegrationsHandle,
  installedHandle,
  type ElastiCacheIntegrationsCompatibleClient,
} from "./middleware.js";
import { stringifyConfig } from "./protocol.js";
import {
  DEFAULT_STALENESS_DURATION_MS,
  parseEndpoint,
  ElastiCacheIntegrationsSession,
} from "./session.js";

/**
 * Configuration for a ElastiCacheIntegrations client. All fields are optional and use defaults
 * when omitted. install() rejects a value that is not usable with ElastiCacheIntegrationsConfigError.
 */
export interface ElastiCacheIntegrationsClientConfiguration {
  /**
   * Maximum time, in milliseconds, to wait for each ElastiCacheIntegrations request response.
   * Defaults to 5000.
   */
  cacheRequestTimeout?: number;
  /**
   * How long, in milliseconds, ElastiCacheIntegrations tries to connect at startup, and how long a
   * request waits for reconnection after a connection is lost. Defaults to 5000.
   */
  connectionTimeout?: number;
  /**
   * How long, in seconds, routed requests may use the origin service after an outage's
   * reconnect wait ends without a connection. The window is shared by requests during one
   * outage. A later outage gets a new window after recovery.
   *
   * - `undefined` (the default), `null`, or any negative value: allow fallback for the rest of
   *   the outage.
   * - `N > 0`: allow fallback for N seconds. Later requests raise
   *   `ElastiCacheIntegrationsCacheUnavailable`.
   * - `0`: disable fallback. Requests raise as soon as the reconnect wait ends.
   */
  fallbackDuration?: number | null;
  /**
   * Optional settings that change what ElastiCacheIntegrations caches: which tables and operations
   * to cache, and the cache entry lifetimes. Sent through the internal `aws.config` command.
   *
   * `cached_tables` and `cached_operations` are allowlists. Each one empty or absent means
   * everything, so keeping a table out of the cache means naming the tables that should be cached
   * rather than the one that should not. The lifetimes (`item_ttl`, `item_negative_ttl`,
   * `query_ttl`, `scan_ttl`) and `item_write_behavior` apply connection-wide, or per table under
   * `table_overrides`.
   *
   * The object is sent as given. A field name the proxy does not recognise is ignored, so a typo
   * or a stale key is dropped without an error.
   */
  cachePolicyOverrides?: Readonly<Record<string, unknown>>;
  /**
   * How long, in seconds, ElastiCacheIntegrations may use a cache policy without confirmation
   * from the cache. After this time, ElastiCacheIntegrations reconnects to get a current policy.
   * Defaults to 3600 seconds and must be a positive whole number.
   */
  cachePolicyTimeout?: number;
  /**
   * How long, in seconds, after a token is issued before the IAM toolkit signs a replacement in
   * the background, while callers keep using the current token. Must be greater than 0 and less
   * than 880. When omitted, the toolkit default of 300 seconds is used.
   *
   * @see https://github.com/aws/developer-toolkit-elasticache/tree/main/node#caching-and-background-refresh
   */
  iamTokenRefreshDelay?: number;
}

interface ElastiCacheIntegrationsInstallOptions<TClient extends InstallableClient> {
  /** AWS SDK v3 client to install ElastiCacheIntegrations into. Its region, credentials, and service are used. */
  client: TClient;
  /** Cache endpoint as host:port. */
  endpoint: string;
  /** ElastiCache Serverless cache name, distinct from the endpoint. */
  cacheName: string;
  /** ElastiCache IAM user id; defaults to default.iam-user. */
  userId?: string;
  /** Verify the cache's TLS certificate. Defaults to `true`; `false` is for self-signed test rigs only. */
  verify?: boolean;
  /**
   * Path of a PEM CA bundle file to verify against instead of the system trust store. install()
   * reads it once, before connecting.
   */
  caCerts?: string;
  /** How the cache behaves and its optional cache policy overrides; omitted means all defaults. */
  clientConfiguration?: ElastiCacheIntegrationsClientConfiguration | null;
}

const INSTALL_OPTIONS: ReadonlySet<string> = new Set([
  "client",
  "endpoint",
  "cacheName",
  "userId",
  "verify",
  "caCerts",
  "clientConfiguration",
]);

/** setTimeout fires after 1 ms for any delay above this, so longer timeouts are rejected. */
const MAX_TIMER_DELAY_MS = 2 ** 31 - 1;

const CLIENT_CONFIGURATION_FIELDS: ReadonlySet<string> = new Set([
  "cacheRequestTimeout",
  "connectionTimeout",
  "fallbackDuration",
  "cachePolicyOverrides",
  "cachePolicyTimeout",
  "iamTokenRefreshDelay",
]);

/** The part of an AWS SDK v3 client that install() reads. */
type InstallableClient = ElastiCacheIntegrationsCompatibleClient & {
  config: { region: () => Promise<string>; serviceId: string };
};

interface InitializeOptions {
  cacheName: string;
  region: string;
  resolvedCredentials: AwsCredentialIdentity;
  userId?: string;
  iamTokenRefreshDelay?: number;
  connectorOptions: ElastiCacheIntegrationsConnectorOptions;
  fallbackDurationMs?: number | null;
  connectionTimeoutMs: number;
  cachePolicyOverrides: Readonly<Record<string, unknown>>;
  stalenessDurationMs: number;
}

/**
 * Upper bound for iamTokenRefreshDelay, mirroring the toolkit's 20-second safety margin on the
 * 15-minute token. The toolkit re-validates; this is the user-facing check.
 */
const MAX_IAM_TOKEN_REFRESH_DELAY = 880;

interface ResolvedClientConfiguration {
  requestTimeoutMs: number;
  connectionTimeoutMs: number;
  fallbackDurationMs?: number | null;
  cachePolicyOverrides: Readonly<Record<string, unknown>>;
  stalenessDurationMs: number;
  iamTokenRefreshDelay?: number;
}

/**
 * Validate the public seconds-based configuration and convert it to the milliseconds the
 * transport uses.
 *
 * Runs before transport initialization so a bad value fails fast instead of after a connection
 * and the authenticated RESP3 handshake has already succeeded.
 */
function resolveClientConfiguration(
  configuration: ElastiCacheIntegrationsClientConfiguration | null | undefined,
): ResolvedClientConfiguration {
  configuration ??= {};
  if (typeof configuration !== "object" || Array.isArray(configuration)) {
    throw new ElastiCacheIntegrationsConfigError(
      `clientConfiguration must be an object, null, or undefined, got ${typeName(configuration)}`,
    );
  }
  for (const field of Object.keys(configuration)) {
    if (!CLIENT_CONFIGURATION_FIELDS.has(field)) {
      throw new ElastiCacheIntegrationsConfigError(
        `clientConfiguration got an unexpected field '${field}'`,
      );
    }
  }
  const {
    cacheRequestTimeout = DEFAULT_REQUEST_TIMEOUT,
    connectionTimeout = DEFAULT_CONNECTION_TIMEOUT,
    fallbackDuration,
    cachePolicyOverrides = {},
    cachePolicyTimeout,
    iamTokenRefreshDelay,
  } = configuration;
  const requestTimeoutMs = timerMs("cacheRequestTimeout", cacheRequestTimeout);
  const connectionTimeoutMs = timerMs("connectionTimeout", connectionTimeout);
  if (
    typeof cachePolicyOverrides !== "object" ||
    cachePolicyOverrides === null ||
    Array.isArray(cachePolicyOverrides)
  ) {
    throw new ElastiCacheIntegrationsConfigError(
      "cachePolicyOverrides must be an object or undefined",
    );
  }
  if (
    iamTokenRefreshDelay !== undefined &&
    (typeof iamTokenRefreshDelay !== "number" ||
      !Number.isFinite(iamTokenRefreshDelay) ||
      !(iamTokenRefreshDelay > 0 && iamTokenRefreshDelay < MAX_IAM_TOKEN_REFRESH_DELAY))
  ) {
    throw new ElastiCacheIntegrationsConfigError(
      "iamTokenRefreshDelay must be a number of seconds greater than 0 and less " +
        `than ${MAX_IAM_TOKEN_REFRESH_DELAY}, or undefined to use the toolkit default`,
    );
  }
  let copiedOverrides: Readonly<Record<string, unknown>>;
  try {
    // Copied through the same serializer the aws.config handshake uses, so a value it would
    // reject fails here, before connecting, and later changes to the caller's object have no
    // effect on later handshakes.
    copiedOverrides = JSON.parse(stringifyConfig(cachePolicyOverrides)) as Record<string, unknown>;
  } catch (error) {
    throw new ElastiCacheIntegrationsConfigError("cachePolicyOverrides must be JSON-serializable", {
      cause: error,
    });
  }
  return {
    requestTimeoutMs,
    connectionTimeoutMs,
    ...(fallbackDuration === undefined
      ? {}
      : { fallbackDurationMs: fallbackDurationMs(fallbackDuration) }),
    cachePolicyOverrides: copiedOverrides,
    stalenessDurationMs: stalenessDurationMs(cachePolicyTimeout),
    ...(iamTokenRefreshDelay === undefined ? {} : { iamTokenRefreshDelay }),
  };
}

/** Validate a timeout: a positive whole number of milliseconds that setTimeout can hold. */
function timerMs(name: string, milliseconds: unknown): number {
  if (
    !Number.isInteger(milliseconds) ||
    (milliseconds as number) <= 0 ||
    (milliseconds as number) > MAX_TIMER_DELAY_MS
  ) {
    throw new ElastiCacheIntegrationsConfigError(
      `${name} must be a positive whole number of milliseconds, at most ${MAX_TIMER_DELAY_MS}`,
    );
  }
  return milliseconds as number;
}

function stalenessDurationMs(seconds: number | undefined): number {
  if (seconds === undefined) return DEFAULT_STALENESS_DURATION_MS;
  if (
    typeof seconds !== "number" ||
    !Number.isSafeInteger(seconds * 1_000) ||
    !Number.isInteger(seconds) ||
    seconds <= 0
  ) {
    throw new ElastiCacheIntegrationsConfigError(
      "cachePolicyTimeout must be a positive whole number of seconds",
    );
  }
  return seconds * 1_000;
}

/**
 * Bring up a ElastiCacheIntegrationsSession for an SDK client and install interception.
 *
 * The session completes the authenticated HELLO 3 and aws.config handshakes
 * before middleware is installed, so the first intercepted request sees a
 * ready session.
 */
async function initialize<TClient extends ElastiCacheIntegrationsCompatibleClient>(
  sdkClient: TClient,
  service: string,
  endpoint: string,
  options: InitializeOptions,
): Promise<ElastiCacheIntegrationsHandle> {
  const [host, port] = parseEndpoint(endpoint);
  const authProvider = await buildCacheAuthProvider({
    cacheName: options.cacheName,
    ...(options.userId === undefined ? {} : { userId: options.userId }),
    region: options.region,
    ...(options.iamTokenRefreshDelay === undefined
      ? {}
      : { refreshAfterSeconds: options.iamTokenRefreshDelay }),
    credentials: sdkClient.config.credentials,
    resolvedCredentials: options.resolvedCredentials,
  });
  const connectorFactory = Object.assign(
    () =>
      new ElastiCacheIntegrationsConnector(host, port, {
        ...options.connectorOptions,
        authProvider,
      }),
    { close: () => authProvider.close() },
  );
  let session: ElastiCacheIntegrationsSession;
  try {
    const reconnectPolicy = {
      ...(options.fallbackDurationMs === undefined
        ? {}
        : { fallbackDurationMs: options.fallbackDurationMs }),
      connectionTimeoutMs: options.connectionTimeoutMs,
      stalenessDurationMs: options.stalenessDurationMs,
    };
    session = new ElastiCacheIntegrationsSession(
      connectorFactory,
      options.cachePolicyOverrides,
      reconnectPolicy,
    );
  } catch (error) {
    // The session owns the auth provider only once it exists.
    authProvider.close();
    throw error;
  }
  try {
    await session.open();
    const handle = new ElastiCacheIntegrationsHandle(sdkClient, session, service);
    return handle;
  } catch (error) {
    try {
      session.destroy();
    } catch {
      // Preserve the initialization error.
    }
    throw error;
  }
}

function fallbackDurationMs(value: number | null): number | null {
  if (value === null) return value;
  const milliseconds = typeof value === "number" ? value * 1_000 : NaN;
  if (!Number.isSafeInteger(milliseconds) || milliseconds % 1_000 !== 0) {
    throw new ElastiCacheIntegrationsConfigError(
      "fallbackDuration must be a whole number of seconds, null, or undefined",
    );
  }
  return milliseconds;
}

/** Whether `value` has the parts of an AWS SDK v3 client that install() uses. */
function isSdkClient(value: unknown): value is InstallableClient {
  const candidate = value as Partial<InstallableClient> | null;
  return (
    typeof candidate === "object" &&
    candidate !== null &&
    typeof candidate.destroy === "function" &&
    typeof candidate.middlewareStack?.add === "function" &&
    typeof candidate.config?.serviceId === "string" &&
    typeof candidate.config.region === "function"
  );
}

function readCaCerts(path: string): Buffer {
  if (typeof path !== "string") {
    throw new ElastiCacheIntegrationsConfigError(
      `caCerts must be the path of a CA bundle file, got ${typeName(path)}`,
    );
  }
  try {
    return readFileSync(path);
  } catch (error) {
    throw new ElastiCacheIntegrationsConfigError(
      `caCerts must be the path of a readable CA bundle file, got ${String(path)}`,
      { cause: error },
    );
  }
}

function typeName(value: unknown): string {
  if (value === null) return "null";
  if (typeof value !== "object") return typeof value;
  return value.constructor?.name ?? "Object";
}

/**
 * Install ElastiCacheIntegrations into an application's AWS SDK v3 client.
 *
 * The client keeps its identical API and starts serving eligible requests from the cache once the
 * cache connection, authentication, and aws.config exchange complete. Calling destroy() on it
 * disconnects the cache before destroying the client as usual. If installation fails,
 * ElastiCacheIntegrations releases what it opened and the client stays usable without the cache.
 */
export async function install<TClient extends InstallableClient>(
  options: ElastiCacheIntegrationsInstallOptions<TClient>,
): Promise<void> {
  // An unknown key is usually a removed or misspelled option; ignoring it would silently apply a
  // default, so it fails like an unexpected keyword argument to the Python install().
  for (const name of Object.keys(options)) {
    if (!INSTALL_OPTIONS.has(name)) {
      throw new ElastiCacheIntegrationsConfigError(`install() got an unexpected option '${name}'`);
    }
  }
  const {
    client: sdkClient,
    endpoint,
    cacheName,
    userId,
    verify = true,
    caCerts,
    clientConfiguration,
  } = options;
  if (typeof verify !== "boolean") {
    throw new ElastiCacheIntegrationsConfigError(
      `verify must be a boolean, got ${typeName(verify)}`,
    );
  }
  if (!isSdkClient(sdkClient)) {
    throw new ElastiCacheIntegrationsConfigError(
      `client must be an AWS SDK v3 client, got ${typeName(sdkClient)}. Create one with ` +
        "new DynamoDBClient(...) or another @aws-sdk client constructor",
    );
  }
  if (installedHandle(sdkClient)) {
    throw new ElastiCacheIntegrationsConfigError(
      "This AWS SDK client already has ElastiCache Integrations installed; install once per client.",
    );
  }
  const configuration = resolveClientConfiguration(clientConfiguration);
  const caCertsPem = caCerts === undefined ? undefined : readCaCerts(caCerts);
  // botocore names a service by lowercasing its service id and hyphenating spaces; the proxy's
  // predicates match that name, so Node derives it the same way.
  const service = sdkClient.config.serviceId.toLowerCase().replace(/ /g, "-");
  let region: string | undefined;
  let regionError: unknown;
  try {
    region = await sdkClient.config.region();
  } catch (error) {
    regionError = error;
  }
  if (!region) {
    throw new ElastiCacheIntegrationsConfigError(
      "The AWS SDK client has no region, so the cache's IAM token cannot be signed. " +
        "Create the client with a region.",
      regionError === undefined ? undefined : { cause: regionError },
    );
  }
  // Resolve once now, so a client with no usable credentials fails here, as it does in Python.
  // The IAM toolkit would retry the failure past the connection timeout and hide the SDK's error.
  // A client with no credential provider fails the same way, with the TypeError as the cause.
  // The first cache token is signed with these credentials, so they are resolved only once.
  let resolvedCredentials: AwsCredentialIdentity;
  try {
    resolvedCredentials = await sdkClient.config.credentials();
  } catch (error) {
    throw new ElastiCacheIntegrationsConfigError(
      "This AWS SDK client carries no credentials, so the cache cannot be authenticated.",
      { cause: error },
    );
  }
  await initialize(sdkClient, service, endpoint, {
    cacheName,
    region,
    resolvedCredentials,
    ...(userId === undefined ? {} : { userId }),
    ...(configuration.iamTokenRefreshDelay === undefined
      ? {}
      : { iamTokenRefreshDelay: configuration.iamTokenRefreshDelay }),
    connectorOptions: {
      requestTimeout: configuration.requestTimeoutMs,
      connectionTimeout: configuration.connectionTimeoutMs,
      verify,
      ...(caCertsPem === undefined ? {} : { caCerts: caCertsPem }),
    },
    ...(configuration.fallbackDurationMs === undefined
      ? {}
      : { fallbackDurationMs: configuration.fallbackDurationMs }),
    connectionTimeoutMs: configuration.connectionTimeoutMs,
    cachePolicyOverrides: configuration.cachePolicyOverrides,
    stalenessDurationMs: configuration.stalenessDurationMs,
  });
}
