import { buildCacheAuthProvider } from "./auth.js";
import { PhotonConnector, type PhotonConnectorOptions } from "./connection.js";
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
  /** Pre-vended IAM AUTH token used verbatim instead of minting one from the SDK credential chain. */
  authCredentials?: { userId: string; token: string };
  /** AWS SDK v3 client configuration. */
  clientConfig?: Omit<TConfig, "region">;
  /** Node-specific Photon transport configuration. */
  connectorOptions?: Omit<PhotonConnectorOptions, "authProvider">;
  /** Overrides sent by the photon.config handshake. */
  overrides?: Readonly<Record<string, unknown>>;
}

interface InitializeOptions {
  cacheName: string;
  region?: string;
  userId?: string;
  authCredentials?: { userId: string; token: string };
  connectorOptions?: Omit<PhotonConnectorOptions, "authProvider">;
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
  const injected = options.authCredentials;
  const authProvider =
    injected !== undefined
      ? (): [string, string] => [injected.userId, injected.token]
      : await buildCacheAuthProvider({
          cacheName: options.cacheName,
          ...(options.userId === undefined ? {} : { userId: options.userId }),
          ...(options.region === undefined ? {} : { region: options.region }),
          credentials: sdkClient.config.credentials,
        });
  const connector = new PhotonConnector(host, port, {
    ...options.connectorOptions,
    authProvider,
  });
  const session = new PhotonSession(connector, options.overrides);
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
    authCredentials,
    clientConfig,
    connectorOptions,
    overrides = {},
  } = options;
  const sdkClient = new ClientCtor({
    ...clientConfig,
    ...(region === undefined ? {} : { region }),
  } as TConfig);

  try {
    await initialize(sdkClient, service, endpoint, {
      cacheName,
      ...(region === undefined ? {} : { region }),
      ...(userId === undefined ? {} : { userId }),
      ...(authCredentials === undefined ? {} : { authCredentials }),
      ...(connectorOptions === undefined ? {} : { connectorOptions }),
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
