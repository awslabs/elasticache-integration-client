import { buildCacheAuthProvider } from "./auth.js";
import { PhotonConnector, type PhotonConnectorOptions } from "./connection.js";
import { PhotonConfigError, PhotonError } from "./errors.js";
import { PhotonHandle, type PhotonCompatibleClient } from "./middleware.js";
import {
  DEFAULT_CONFIG_STALENESS_THRESHOLD_MS,
  parseEndpoint,
  PhotonSession,
  type ReconnectPolicyOptions,
} from "./session.js";

export interface PhotonClientOptions<TConfig extends object> {
  /** Cache endpoint as host:port. */
  endpoint: string;
  /** ElastiCache Serverless cache name, distinct from the endpoint. */
  cacheName: string;
  /** AWS region; resolved from standard AWS configuration when omitted. */
  region?: string;
  /** ElastiCache IAM user id; defaults to default.iam-user. */
  userId?: string;
  /**
   * Seconds after token issuance when IAM refresh begins in the background.
   * Applies only to the managed provider; a connector authProvider owns its configuration.
   *
   * @see https://github.com/aws/developer-toolkit-elasticache/tree/release-2/node#caching-and-background-refresh
   */
  iamRefreshAfterSeconds?: number;
  /** AWS SDK v3 client configuration. */
  clientConfig?: Omit<TConfig, "region">;
  /** Node-specific Photon transport configuration. */
  connectorOptions?: PhotonConnectorOptions;
  /**
   * Seconds that disconnected requests may fall back to origin per outage.
   * `null`, `undefined`, or a negative value means indefinitely; zero disables fallback.
   */
  fallbackDuration?: number | null;
  /** Advanced reconnection and fallback configuration. */
  reconnectPolicy?: ReconnectPolicyOptions;
  /** Overrides sent by the photon.config handshake. */
  overrides?: Readonly<Record<string, unknown>>;
  /**
   * Positive whole seconds before soft config refresh failures replace the current connector and
   * enter normal outage recovery. Defaults to 3600 (one hour).
   */
  configStalenessThreshold?: number;
}

interface InitializeOptions {
  cacheName: string;
  region?: string;
  userId?: string;
  iamRefreshAfterSeconds?: number;
  connectorOptions?: PhotonConnectorOptions;
  fallbackDuration?: number | null;
  reconnectPolicy?: ReconnectPolicyOptions;
  overrides: Readonly<Record<string, unknown>>;
  configStalenessThresholdMs: number;
}

/**
 * Validate and convert the public seconds-based staleness option to milliseconds.
 *
 * Runs before transport initialization so a bad value fails fast instead of after a connection
 * and IAM authentication have already succeeded.
 */
function resolveConfigStalenessThresholdMs(thresholdSeconds: number | undefined): number {
  if (thresholdSeconds === undefined) {
    return DEFAULT_CONFIG_STALENESS_THRESHOLD_MS;
  }
  if (
    !Number.isInteger(thresholdSeconds) ||
    thresholdSeconds <= 0 ||
    !Number.isSafeInteger(thresholdSeconds * 1000)
  ) {
    throw new PhotonError(
      `Invalid configStalenessThreshold: must be a positive whole number of seconds, got ${thresholdSeconds}`,
    );
  }
  return thresholdSeconds * 1000;
}

/**
 * Bring up a PhotonSession for an SDK client and install interception.
 *
 * The session completes IAM authentication and the photon.config handshake
 * before middleware is installed, so the first intercepted request sees a
 * ready session.
 */
async function initialize<TClient extends PhotonCompatibleClient>(
  sdkClient: TClient,
  service: string,
  endpoint: string,
  options: InitializeOptions,
): Promise<PhotonHandle> {
  const [host, port] = parseEndpoint(endpoint);
  // A caller-supplied provider replaces token generation rather than competing with it.
  const managedAuthProvider =
    options.connectorOptions?.authProvider === undefined
      ? await buildCacheAuthProvider({
          cacheName: options.cacheName,
          ...(options.userId === undefined ? {} : { userId: options.userId }),
          ...(options.region === undefined ? {} : { region: options.region }),
          ...(options.iamRefreshAfterSeconds === undefined
            ? {}
            : { refreshAfterSeconds: options.iamRefreshAfterSeconds }),
          credentials: sdkClient.config.credentials,
        })
      : undefined;
  const authProvider = options.connectorOptions?.authProvider ?? managedAuthProvider;
  const connectorFactory = Object.assign(
    () =>
      new PhotonConnector(host, port, {
        ...options.connectorOptions,
        ...(authProvider === undefined ? {} : { authProvider }),
      }),
    managedAuthProvider === undefined ? {} : { close: () => managedAuthProvider.close() },
  );
  const reconnectPolicy = {
    ...options.reconnectPolicy,
    ...(options.fallbackDuration === undefined
      ? {}
      : { fallbackDurationMs: fallbackDurationMs(options.fallbackDuration) }),
    configStalenessThresholdMs: options.configStalenessThresholdMs,
  };
  const session = new PhotonSession(connectorFactory, options.overrides, reconnectPolicy);
  try {
    await session.open();
    const handle = new PhotonHandle(sdkClient, session, service);
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
    throw new PhotonConfigError(
      "fallbackDuration must be a whole number of seconds, null, or undefined",
    );
  }
  return milliseconds;
}

/**
 * Create an AWS SDK v3 client for `service` wired up with Photon.
 *
 * Returns the ordinary SDK client with an identical API. Calling destroy() on
 * it disconnects Photon before destroying the SDK client as usual.
 *
 * `ClientCtor` and `clientConfig` are Node-specific because AWS SDK v3 has no
 * runtime service-name factory.
 */
export async function client<TConfig extends object, TClient extends PhotonCompatibleClient>(
  service: string,
  ClientCtor: new (config: TConfig) => TClient,
  options: PhotonClientOptions<TConfig>,
): Promise<TClient> {
  if (!service.trim()) throw new PhotonError("service must not be blank");
  const {
    endpoint,
    cacheName,
    region,
    userId,
    iamRefreshAfterSeconds,
    clientConfig,
    connectorOptions,
    fallbackDuration,
    reconnectPolicy,
    overrides = {},
    configStalenessThreshold,
  } = options;
  const configStalenessThresholdMs = resolveConfigStalenessThresholdMs(configStalenessThreshold);
  const sdkClient = new ClientCtor({
    ...clientConfig,
    ...(region === undefined ? {} : { region }),
  } as TConfig);

  try {
    await initialize(sdkClient, service, endpoint, {
      cacheName,
      ...(region === undefined ? {} : { region }),
      ...(userId === undefined ? {} : { userId }),
      ...(iamRefreshAfterSeconds === undefined ? {} : { iamRefreshAfterSeconds }),
      ...(connectorOptions === undefined ? {} : { connectorOptions }),
      ...(fallbackDuration === undefined ? {} : { fallbackDuration }),
      ...(reconnectPolicy === undefined ? {} : { reconnectPolicy }),
      overrides,
      configStalenessThresholdMs,
    });
  } catch (error) {
    try {
      sdkClient.destroy();
    } catch {
      // Preserve the initialization error.
    }
    throw error;
  }
  return sdkClient;
}
