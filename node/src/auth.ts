/**
 * IAM authentication for the ElastiCacheIntegrations client.
 *
 * Token validation, region resolution, credential resolution, and SigV4
 * signing are delegated to @aws/developer-toolkit-elasticache. This adapter
 * keeps ElastiCacheIntegrations's connector-facing `(userId, token)` provider contract and
 * error taxonomy.
 */
import type {
  ElastiCacheIAMAuthTokenProvider as ToolkitAuthTokenProvider,
  TokenGeneratorDependencies,
} from "@aws/developer-toolkit-elasticache";
import type { AwsCredentialIdentity, AwsCredentialIdentityProvider } from "@smithy/types";

import { ElastiCacheIntegrationsError } from "./errors.js";
import { loadToolkit, type ToolkitModule } from "./toolkit-loader.js";

/** SigV4 presign expiry used by the ElastiCache toolkit. */
export const TOKEN_EXPIRY_SECONDS = 900;

/** Default ElastiCache IAM user for Public Endpoint caches. */
export const DEFAULT_USER_ID = "default.iam-user";

const NO_CREDENTIALS_MESSAGE = "No AWS credentials available for IAM auth token generation";

export type CredentialsProvider = AwsCredentialIdentityProvider;

export interface AuthParamsInput {
  cacheName?: string;
  userId?: string;
  region?: string;
}

export interface ResolvedAuthParams {
  cacheName: string;
  userId: string;
  region: string;
}

export interface GenerateIamAuthTokenOptions extends ResolvedAuthParams {
  credentials: CredentialsProvider;
  expiresIn?: number;
}

export interface IamAuthTokenProviderOptions extends ResolvedAuthParams {
  credentials: CredentialsProvider;
}

/** Connector-compatible IAM provider backed by the toolkit token manager. */
export interface CacheAuthProvider {
  (): Promise<[string, string]>;
  refreshAuth(): Promise<[string, string]>;
  close(): void;
}

/**
 * Check required auth parameters and apply the default IAM user.
 *
 * Detailed cache-name and user-id validation is owned by the ElastiCache
 * toolkit when the provider is initialized.
 */
export function resolveAuthParams(input: AuthParamsInput): ResolvedAuthParams {
  if (!input.cacheName) {
    throw new ElastiCacheIntegrationsError("cacheName is required for IAM authentication.");
  }
  if (!input.region) {
    throw new ElastiCacheIntegrationsError("region is required for IAM authentication.");
  }

  return {
    cacheName: input.cacheName,
    userId: input.userId || DEFAULT_USER_ID,
    region: input.region,
  };
}

function toolkitDependencies(
  credentials: CredentialsProvider,
  resolvedCredentials?: AwsCredentialIdentity,
): TokenGeneratorDependencies {
  let first = resolvedCredentials;
  return {
    credentialProvider: () => {
      if (first !== undefined) {
        const identity = first;
        first = undefined;
        return Promise.resolve(identity);
      }
      return credentials();
    },
  };
}

function toCacheError(error: unknown, toolkit: ToolkitModule): ElastiCacheIntegrationsError {
  if (
    error instanceof toolkit.ConfigurationError &&
    error.message.startsWith("No AWS credentials")
  ) {
    return new ElastiCacheIntegrationsError(NO_CREDENTIALS_MESSAGE, { cause: error });
  }
  if (error instanceof toolkit.ToolkitInputError) {
    return new ElastiCacheIntegrationsError(error.message, { cause: error });
  }
  return new ElastiCacheIntegrationsError("Failed to generate IAM auth token", { cause: error });
}

/**
 * Generate a short-lived IAM auth token for an ElastiCache Serverless cache.
 *
 * The toolkit owns token construction and validation. Credentials come from the SDK client's
 * credential provider, which reuses its cached identity until the SDK refreshes it.
 */
export async function generateIamAuthToken({
  cacheName,
  userId,
  region,
  credentials,
  expiresIn = TOKEN_EXPIRY_SECONDS,
}: GenerateIamAuthTokenOptions): Promise<string> {
  if (expiresIn !== TOKEN_EXPIRY_SECONDS) {
    throw new ElastiCacheIntegrationsError(`expiresIn must be ${TOKEN_EXPIRY_SECONDS} seconds`);
  }

  const toolkit = await loadToolkit();
  try {
    return await toolkit.generateIamAuthToken(
      {
        serverlessCacheName: cacheName,
        userId,
        region,
      },
      toolkitDependencies(credentials),
    );
  } catch (error) {
    throw toCacheError(error, toolkit);
  }
}

/** Credential provider that signs a fresh IAM token for every call. */
export class IamAuthTokenProvider {
  private readonly options: IamAuthTokenProviderOptions;
  private providerPromise: Promise<ToolkitAuthTokenProvider> | undefined;

  constructor(options: IamAuthTokenProviderOptions) {
    this.options = options;
  }

  private initialize(): Promise<ToolkitAuthTokenProvider> {
    this.providerPromise ??= this.createProvider();
    return this.providerPromise;
  }

  private async createProvider(): Promise<ToolkitAuthTokenProvider> {
    const toolkit = await loadToolkit();
    try {
      return await toolkit.ElastiCacheIAMAuthTokenProvider.create(
        {
          serverlessCacheName: this.options.cacheName,
          userId: this.options.userId,
          region: this.options.region,
        },
        toolkitDependencies(this.options.credentials),
      );
    } catch (error) {
      throw toCacheError(error, toolkit);
    }
  }

  /** Resolve configuration before the connector attempts to open. */
  async ready(): Promise<void> {
    await this.initialize();
  }

  /** Return the normalized user id and a newly signed token. */
  async getAuth(): Promise<[string, string]> {
    const provider = await this.initialize();
    try {
      return [provider.userId, await provider.getToken()];
    } catch (error) {
      throw toCacheError(error, await loadToolkit());
    }
  }

  toString(): string {
    const { cacheName, userId, region } = this.options;
    return `IamAuthTokenProvider(cacheName=${cacheName}, userId=${userId}, region=${region})`;
  }
}

/**
 * Assemble a ready-to-use authentication provider for a cache connector.
 *
 * Signs with `credentials`, the provider behind the application's own client, so the cache is
 * authenticated as the same principal and rotation is preserved: the toolkit re-reads them for
 * every token it signs. `resolvedCredentials`, when given, signs the first token instead, so
 * credentials the caller has just resolved are not resolved a second time.
 */
export async function buildCacheAuthProvider(
  input: AuthParamsInput & {
    credentials: CredentialsProvider;
    resolvedCredentials?: AwsCredentialIdentity;
    refreshAfterSeconds?: number;
  },
): Promise<CacheAuthProvider> {
  const toolkit = await loadToolkit();
  const { credentials, resolvedCredentials } = input;

  try {
    const manager = await toolkit.ElastiCacheIAMAuthTokenManager.create(
      {
        ...(input.cacheName === undefined ? {} : { serverlessCacheName: input.cacheName }),
        userId: input.userId || DEFAULT_USER_ID,
        ...(input.region === undefined ? {} : { region: input.region }),
        ...(input.refreshAfterSeconds === undefined
          ? {}
          : { refreshAfterSeconds: input.refreshAfterSeconds }),
      },
      toolkitDependencies(credentials, resolvedCredentials),
    );
    const getAuth = async (): Promise<[string, string]> => {
      try {
        return await manager.getCredentials();
      } catch (error) {
        throw toCacheError(error, toolkit);
      }
    };
    return Object.assign(getAuth, {
      refreshAuth: async (): Promise<[string, string]> => {
        try {
          return [manager.userId, await manager.refreshToken()];
        } catch (error) {
          throw toCacheError(error, toolkit);
        }
      },
      close: () => manager.close(),
    });
  } catch (error) {
    throw toCacheError(error, toolkit);
  }
}
