import { buildCacheAuthProvider } from "./auth.js";
import {
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
import {
  DEFAULT_STALENESS_DURATION_MS,
  parseEndpoint,
  ElastiCacheIntegrationsSession,
  type ReconnectPolicyOptions,
} from "./session.js";

/**
 * Configuration for a ElastiCacheIntegrations client. All fields are optional and use defaults
 * when omitted. install() rejects a value that is not usable with ElastiCacheIntegrationsConfigError.
 */
export interface ElastiCacheIntegrationsClientConfiguration {
  /**
   * Maximum time, in seconds, to wait for each ElastiCacheIntegrations request response.
   * Defaults to 5 seconds.
   */
  cacheRequestTimeout?: number;
  /**
   * How long, in seconds, requests may be served directly by the origin service while
   * ElastiCacheIntegrations has no connection to the cache. `null`, `undefined` (the default), or
   * any negative value means indefinitely; `0` means never.
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
   * than 880. When omitted, the toolkit default of 300 seconds is used. Applies only to the managed provider; a connector authProvider owns its
   * configuration.
   *
   * @see https://github.com/aws/developer-toolkit-elasticache/tree/release-2/node#caching-and-background-refresh
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
  /** CA bundle (PEM) to verify against instead of the system trust store. */
  caCerts?: string | Buffer | Array<string | Buffer>;
  /** How the cache behaves and its optional cache policy overrides; omitted means all defaults. */
  clientConfiguration?: ElastiCacheIntegrationsClientConfiguration;
  /**
   * Node-specific ElastiCacheIntegrations transport configuration. The request timeout and TLS
   * verification are set through `clientConfiguration.cacheRequestTimeout`, `verify`, and `caCerts`.
   */
  connectorOptions?: Omit<
    ElastiCacheIntegrationsConnectorOptions,
    "requestTimeout" | "verify" | "caCerts"
  >;
  /** Advanced reconnection and fallback configuration. */
  reconnectPolicy?: ReconnectPolicyOptions;
}

/** The part of an AWS SDK v3 client that install() reads. */
type InstallableClient = ElastiCacheIntegrationsCompatibleClient & {
  config: { region: () => Promise<string>; serviceId: string };
};

interface InitializeOptions {
  cacheName: string;
  region: string;
  userId?: string;
  iamTokenRefreshDelay?: number;
  connectorOptions: ElastiCacheIntegrationsConnectorOptions;
  fallbackDurationMs?: number | null;
  reconnectPolicy?: ReconnectPolicyOptions;
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
  configuration: ElastiCacheIntegrationsClientConfiguration,
): ResolvedClientConfiguration {
  const {
    cacheRequestTimeout = DEFAULT_REQUEST_TIMEOUT / 1_000,
    fallbackDuration,
    cachePolicyOverrides = {},
    cachePolicyTimeout,
    iamTokenRefreshDelay,
  } = configuration;
  if (
    typeof cacheRequestTimeout !== "number" ||
    !Number.isFinite(cacheRequestTimeout) ||
    cacheRequestTimeout <= 0
  ) {
    throw new ElastiCacheIntegrationsConfigError(
      "cacheRequestTimeout must be a positive number of seconds",
    );
  }
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
    // Copied so a caller mutating its object after install() cannot change later handshakes.
    copiedOverrides = structuredClone(cachePolicyOverrides);
  } catch (error) {
    throw new ElastiCacheIntegrationsConfigError("cachePolicyOverrides must be JSON-serializable", {
      cause: error,
    });
  }
  return {
    // A sub-millisecond timeout still waits at least one millisecond.
    requestTimeoutMs: Math.max(1, Math.round(cacheRequestTimeout * 1_000)),
    ...(fallbackDuration === undefined
      ? {}
      : { fallbackDurationMs: fallbackDurationMs(fallbackDuration) }),
    cachePolicyOverrides: copiedOverrides,
    stalenessDurationMs: stalenessDurationMs(cachePolicyTimeout),
    ...(iamTokenRefreshDelay === undefined ? {} : { iamTokenRefreshDelay }),
  };
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
  // A caller-supplied provider replaces token generation rather than competing with it.
  const managedAuthProvider =
    options.connectorOptions.authProvider === undefined
      ? await buildCacheAuthProvider({
          cacheName: options.cacheName,
          ...(options.userId === undefined ? {} : { userId: options.userId }),
          region: options.region,
          ...(options.iamTokenRefreshDelay === undefined
            ? {}
            : { refreshAfterSeconds: options.iamTokenRefreshDelay }),
          credentials: sdkClient.config.credentials,
        })
      : undefined;
  const authProvider = options.connectorOptions.authProvider ?? managedAuthProvider;
  const connectorFactory = Object.assign(
    () =>
      new ElastiCacheIntegrationsConnector(host, port, {
        ...options.connectorOptions,
        ...(authProvider === undefined ? {} : { authProvider }),
      }),
    managedAuthProvider === undefined ? {} : { close: () => managedAuthProvider.close() },
  );
  let session: ElastiCacheIntegrationsSession;
  try {
    const reconnectPolicy = {
      ...options.reconnectPolicy,
      ...(options.fallbackDurationMs === undefined
        ? {}
        : { fallbackDurationMs: options.fallbackDurationMs }),
      stalenessDurationMs: options.stalenessDurationMs,
    };
    session = new ElastiCacheIntegrationsSession(
      connectorFactory,
      options.cachePolicyOverrides,
      reconnectPolicy,
    );
  } catch (error) {
    // The session owns the managed provider only once it exists.
    managedAuthProvider?.close();
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
  const {
    client: sdkClient,
    endpoint,
    cacheName,
    userId,
    verify = true,
    caCerts,
    clientConfiguration = {},
    connectorOptions,
    reconnectPolicy,
  } = options;
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
  if (typeof sdkClient.config.credentials !== "function") {
    throw new ElastiCacheIntegrationsConfigError(
      "This AWS SDK client has no credential provider, so the cache cannot be authenticated.",
    );
  }
  await initialize(sdkClient, service, endpoint, {
    cacheName,
    region,
    ...(userId === undefined ? {} : { userId }),
    ...(configuration.iamTokenRefreshDelay === undefined
      ? {}
      : { iamTokenRefreshDelay: configuration.iamTokenRefreshDelay }),
    connectorOptions: {
      ...connectorOptions,
      requestTimeout: configuration.requestTimeoutMs,
      verify,
      ...(caCerts === undefined ? {} : { caCerts }),
    },
    ...(configuration.fallbackDurationMs === undefined
      ? {}
      : { fallbackDurationMs: configuration.fallbackDurationMs }),
    ...(reconnectPolicy === undefined ? {} : { reconnectPolicy }),
    cachePolicyOverrides: configuration.cachePolicyOverrides,
    stalenessDurationMs: configuration.stalenessDurationMs,
  });
}
