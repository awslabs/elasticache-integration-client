/**
 * IAM authentication for the Photon client.
 *
 * Token validation, region resolution, credential resolution, and SigV4
 * signing are delegated to @aws/developer-toolkit-elasticache. This adapter
 * keeps Photon's connector-facing `(userId, token)` provider contract and
 * error taxonomy.
 */
import { fromNodeProviderChain } from "@aws-sdk/credential-providers";
import type {
  ElastiCacheIAMAuthTokenProvider as ToolkitAuthTokenProvider,
  TokenGeneratorDependencies,
} from "@aws/developer-toolkit-elasticache";
import type { AwsCredentialIdentityProvider } from "@smithy/types";

import { PhotonError } from "./errors.js";
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

/**
 * Check required auth parameters and apply the default IAM user.
 *
 * Detailed cache-name and user-id validation is owned by the ElastiCache
 * toolkit when the provider is initialized.
 */
export function resolveAuthParams(input: AuthParamsInput): ResolvedAuthParams {
  if (!input.cacheName) {
    throw new PhotonError("cacheName is required for IAM authentication.");
  }
  if (!input.region) {
    throw new PhotonError("region is required for IAM authentication.");
  }

  return {
    cacheName: input.cacheName,
    userId: input.userId || DEFAULT_USER_ID,
    region: input.region,
  };
}

function toolkitDependencies(credentials: CredentialsProvider): TokenGeneratorDependencies {
  return {
    credentialProvider: () => credentials({ forceRefresh: true }),
  };
}

function toPhotonError(error: unknown, toolkit: ToolkitModule): PhotonError {
  if (
    error instanceof toolkit.ConfigurationError &&
    error.message.startsWith("No AWS credentials")
  ) {
    return new PhotonError(NO_CREDENTIALS_MESSAGE, { cause: error });
  }
  if (error instanceof toolkit.ToolkitInputError) {
    return new PhotonError(error.message, { cause: error });
  }
  return new PhotonError("Failed to generate IAM auth token", { cause: error });
}

/**
 * Generate a short-lived IAM auth token for an ElastiCache Serverless cache.
 *
 * The toolkit owns token construction and validation. Photon forces its SDK
 * credential provider to refresh for every token request.
 */
export async function generateIamAuthToken({
  cacheName,
  userId,
  region,
  credentials,
  expiresIn = TOKEN_EXPIRY_SECONDS,
}: GenerateIamAuthTokenOptions): Promise<string> {
  if (expiresIn !== TOKEN_EXPIRY_SECONDS) {
    throw new PhotonError(`expiresIn must be ${TOKEN_EXPIRY_SECONDS} seconds`);
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
    throw toPhotonError(error, toolkit);
  }
}

/** Credential provider that returns a fresh `(userId, token)` pair per call. */
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
      throw toPhotonError(error, toolkit);
    }
  }

  /** Resolve configuration before the connector attempts to open. */
  async ready(): Promise<void> {
    await this.initialize();
  }

  /** Generate a fresh `(userId, token)` pair. */
  async getAuth(): Promise<[string, string]> {
    const provider = await this.initialize();
    try {
      return [provider.userId, await provider.getToken()];
    } catch (error) {
      throw toPhotonError(error, await loadToolkit());
    }
  }

  toString(): string {
    const { cacheName, userId, region } = this.options;
    return `IamAuthTokenProvider(cacheName=${cacheName}, userId=${userId}, region=${region})`;
  }
}

/** Assemble a ready-to-use authentication provider for a cache connector. */
export async function buildCacheAuthProvider(
  input: AuthParamsInput & { credentials?: CredentialsProvider },
): Promise<() => Promise<[string, string]>> {
  const toolkit = await loadToolkit();
  const credentials = input.credentials ?? fromNodeProviderChain();

  try {
    const provider = await toolkit.ElastiCacheIAMAuthTokenProvider.create(
      {
        ...(input.cacheName === undefined ? {} : { serverlessCacheName: input.cacheName }),
        userId: input.userId || DEFAULT_USER_ID,
        ...(input.region === undefined ? {} : { region: input.region }),
      },
      toolkitDependencies(credentials),
    );
    return async () => {
      try {
        return [provider.userId, await provider.getToken()];
      } catch (error) {
        throw toPhotonError(error, toolkit);
      }
    };
  } catch (error) {
    throw toPhotonError(error, toolkit);
  }
}
