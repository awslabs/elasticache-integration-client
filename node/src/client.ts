import { buildCacheAuthProvider } from "./auth.js";
import { PhotonConnection, type PhotonConnectionOptions } from "./connection.js";
import { PhotonError } from "./errors.js";
import { PhotonHandle, type PhotonCompatibleClient } from "./middleware.js";
import { parseEndpoint, PhotonSession } from "./session.js";

export interface PhotonClientOptions<TConfig extends object> {
  /** Cache endpoint as host:port. */
  endpoint: string;
  /** ElastiCache Serverless cache name, distinct from the endpoint. */
  cacheName: string;
  /** AWS region; resolved from standard AWS configuration when omitted. */
  region?: string;
  /** ElastiCache IAM user id; defaults to default.iam-user. */
  userId?: string;
  /** AWS SDK v3 client configuration. */
  clientConfig?: Omit<TConfig, "region">;
  /** Node-specific Photon transport configuration. */
  connectionOptions?: Omit<PhotonConnectionOptions, "authProvider">;
  /** @deprecated Use `connectionOptions`. */
  connectorOptions?: Omit<PhotonConnectionOptions, "authProvider">;
  /** Overrides sent by the photon.config handshake. */
  overrides?: Readonly<Record<string, unknown>>;
}

interface InitializeOptions {
  cacheName: string;
  region?: string;
  userId?: string;
  connectionOptions?: Omit<PhotonConnectionOptions, "authProvider">;
  overrides: Readonly<Record<string, unknown>>;
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
  const authProvider = await buildCacheAuthProvider({
    cacheName: options.cacheName,
    ...(options.userId === undefined ? {} : { userId: options.userId }),
    ...(options.region === undefined ? {} : { region: options.region }),
    credentials: sdkClient.config.credentials,
  });
  const connection = new PhotonConnection(host, port, {
    ...options.connectionOptions,
    authProvider,
  });
  const session = new PhotonSession(connection, options.overrides);
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
    clientConfig,
    connectionOptions,
    connectorOptions,
    overrides = {},
  } = options;
  const resolvedConnectionOptions = connectionOptions ?? connectorOptions;
  const sdkClient = new ClientCtor({
    ...clientConfig,
    ...(region === undefined ? {} : { region }),
  } as TConfig);

  try {
    await initialize(sdkClient, service, endpoint, {
      cacheName,
      ...(region === undefined ? {} : { region }),
      ...(userId === undefined ? {} : { userId }),
      ...(resolvedConnectionOptions === undefined
        ? {}
        : { connectionOptions: resolvedConnectionOptions }),
      overrides,
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
