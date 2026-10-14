import { buildCacheAuthProvider } from "./auth.js";
import {
  ElastiCacheIntegrationsConnector,
  type ElastiCacheIntegrationsConnectorOptions,
} from "./connection.js";
import { ElastiCacheIntegrationsConfigError } from "./errors.js";
import {
  ElastiCacheIntegrationsHandle,
  type ElastiCacheIntegrationsCompatibleClient,
} from "./middleware.js";
import {
  DEFAULT_STALENESS_DURATION_MS,
  parseEndpoint,
  ElastiCacheIntegrationsSession,
  type ReconnectPolicyOptions,
} from "./session.js";

export interface ElastiCacheIntegrationsClientOptions<TClient extends InstallableClient> {
  /** AWS SDK v3 client to install ElastiCacheIntegrations into. Its region, credentials, and service are used. */
  client: TClient;
  /** Cache endpoint as host:port. */
  endpoint: string;
  /** ElastiCache Serverless cache name, distinct from the endpoint. */
  cacheName: string;
  /** ElastiCache IAM user id; defaults to default.iam-user. */
  userId?: string;
  /**
   * Seconds after token issuance when IAM refresh begins in the background.
   * Applies only to the managed provider; a connector authProvider owns its configuration.
   *
   * @see https://github.com/aws/developer-toolkit-elasticache/tree/release-2/node#caching-and-background-refresh
   */
  iamRefreshAfterSeconds?: number;
  /** Node-specific ElastiCacheIntegrations transport configuration. */
  connectorOptions?: ElastiCacheIntegrationsConnectorOptions;
  /**
   * Seconds that disconnected requests may fall back to origin per outage.
   * `null`, `undefined`, or a negative value means indefinitely; zero disables fallback.
   */
  fallbackDuration?: number | null;
  /** Advanced reconnection and fallback configuration. */
  reconnectPolicy?: ReconnectPolicyOptions;
  /**
   * Overrides sent in the `aws.config` handshake.
   *
   * `cached_tables` and `cached_operations` are allowlists — each empty or absent means
   * everything — so keeping a table out of the cache means naming the tables that should be
   * cached rather than the one that should not. The lifetimes (`item_ttl`, `item_negative_ttl`,
   * `query_ttl`, `scan_ttl`) and `item_write_behavior` apply connection-wide, or per table under
   * `table_overrides`.
   *
   * The object is sent as given. A field name the proxy does not recognise is ignored, so a typo
   * or a stale key is dropped without an error.
   */
  overrides?: Readonly<Record<string, unknown>>;
  /**
   * How long, in seconds, the resolved config stays trusted while refreshes are failing. Past it
   * the connection is dropped so a new one re-resolves the config. Defaults to one hour; must be a
   * positive whole number of seconds.
   */
  stalenessDuration?: number;
}

/** The part of an AWS SDK v3 client that install() reads. */
type InstallableClient = ElastiCacheIntegrationsCompatibleClient & {
  config: { region: () => Promise<string>; serviceId: string };
};

interface InitializeOptions {
  cacheName: string;
  region: string;
  userId?: string;
  iamRefreshAfterSeconds?: number;
  connectorOptions?: ElastiCacheIntegrationsConnectorOptions;
  fallbackDuration?: number | null;
  reconnectPolicy?: ReconnectPolicyOptions;
  overrides: Readonly<Record<string, unknown>>;
  stalenessDurationMs: number;
}

/**
 * Validate and convert the public seconds-based staleness window to milliseconds.
 *
 * Runs before transport initialization so a bad value fails fast instead of after a connection
 * and the authenticated RESP3 handshake has already succeeded.
 */
function stalenessDurationMs(seconds: number | undefined): number {
  if (seconds === undefined) return DEFAULT_STALENESS_DURATION_MS;
  if (!Number.isSafeInteger(seconds * 1_000) || !Number.isInteger(seconds) || seconds <= 0) {
    throw new ElastiCacheIntegrationsConfigError(
      "stalenessDuration must be a positive whole number of seconds",
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
    options.connectorOptions?.authProvider === undefined
      ? await buildCacheAuthProvider({
          cacheName: options.cacheName,
          ...(options.userId === undefined ? {} : { userId: options.userId }),
          region: options.region,
          ...(options.iamRefreshAfterSeconds === undefined
            ? {}
            : { refreshAfterSeconds: options.iamRefreshAfterSeconds }),
          credentials: sdkClient.config.credentials,
        })
      : undefined;
  const authProvider = options.connectorOptions?.authProvider ?? managedAuthProvider;
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
      ...(options.fallbackDuration === undefined
        ? {}
        : { fallbackDurationMs: fallbackDurationMs(options.fallbackDuration) }),
      stalenessDurationMs: options.stalenessDurationMs,
    };
    session = new ElastiCacheIntegrationsSession(
      connectorFactory,
      options.overrides,
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
  const milliseconds = value * 1_000;
  if (!Number.isSafeInteger(milliseconds) || milliseconds % 1_000 !== 0) {
    throw new ElastiCacheIntegrationsConfigError(
      "fallbackDuration must be a whole number of seconds, null, or undefined",
    );
  }
  return milliseconds;
}

/**
 * Install ElastiCacheIntegrations into an AWS SDK v3 client the application already built.
 *
 * Returns the same client once the cache connection, authentication, and aws.config exchange
 * complete. Calling destroy() on it disconnects ElastiCacheIntegrations before destroying the SDK
 * client as usual. If installation fails, ElastiCacheIntegrations releases what it opened and
 * leaves the client untouched.
 */
export async function install<TClient extends InstallableClient>(
  options: ElastiCacheIntegrationsClientOptions<TClient>,
): Promise<TClient> {
  const {
    client: sdkClient,
    endpoint,
    cacheName,
    userId,
    iamRefreshAfterSeconds,
    connectorOptions,
    fallbackDuration,
    reconnectPolicy,
    overrides = {},
    stalenessDuration,
  } = options;
  const resolvedStalenessDurationMs = stalenessDurationMs(stalenessDuration);
  // botocore names a service by lowercasing its service id and hyphenating spaces; the proxy's
  // predicates match that name, so Node derives it the same way.
  const service = sdkClient.config.serviceId.toLowerCase().replace(/ /g, "-");
  let region: string;
  try {
    region = await sdkClient.config.region();
  } catch (error) {
    throw new ElastiCacheIntegrationsConfigError(
      "Could not resolve the AWS region of the SDK client",
      { cause: error },
    );
  }
  await initialize(sdkClient, service, endpoint, {
    cacheName,
    region,
    ...(userId === undefined ? {} : { userId }),
    ...(iamRefreshAfterSeconds === undefined ? {} : { iamRefreshAfterSeconds }),
    ...(connectorOptions === undefined ? {} : { connectorOptions }),
    ...(fallbackDuration === undefined ? {} : { fallbackDuration }),
    ...(reconnectPolicy === undefined ? {} : { reconnectPolicy }),
    overrides,
    stalenessDurationMs: resolvedStalenessDurationMs,
  });
  return sdkClient;
}
