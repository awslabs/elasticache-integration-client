/**
 * IAM authentication for the Photon client.
 *
 * Three layers:
 * 1. AWS credential source: the caller's SDK credential provider chain.
 * 2. Token minter: `generateIamAuthToken`, which SigV4-presigns an
 *    `elasticache:connect` request.
 * 3. Provider: `IamAuthTokenProvider`, which supplies `(userId, token)`
 *    credentials to the connector.
 *
 * The connector calls the provider at connect/authentication time. The
 * provider re-resolves credentials and mints a fresh token on every call.
 */
import { fromNodeProviderChain } from "@aws-sdk/credential-providers";
import { formatUrl } from "@aws-sdk/core/util";
import {
  NODE_REGION_CONFIG_FILE_OPTIONS,
  NODE_REGION_CONFIG_OPTIONS,
} from "@smithy/config-resolver";
import { CredentialsProviderError } from "@smithy/core/config";
import { HttpRequest } from "@smithy/core/protocols";
import { Hash } from "@smithy/core/serde";
import { loadConfig } from "@smithy/node-config-provider";
import { SignatureV4 } from "@smithy/signature-v4";
import type { AwsCredentialIdentityProvider, Provider } from "@smithy/types";

import { PhotonError } from "./errors.js";

// --- constants ---------------------------------------------------------------

/** SigV4 presign expiry: the maximum accepted by ElastiCache (15 minutes). */
export const TOKEN_EXPIRY_SECONDS = 900;

/** Default ElastiCache IAM user for Public Endpoint caches. */
export const DEFAULT_USER_ID = "default.iam-user";

const NO_CREDENTIALS_MESSAGE = "No AWS credentials available for IAM auth token generation";
const NO_CREDENTIALS_PROVIDER_ERROR = "Could not load credentials from any providers";

// --- credential source -------------------------------------------------------

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

// --- parameter resolution ----------------------------------------------------

/**
 * Check required auth parameters and apply the default IAM user.
 *
 * Cache-name format validation is intentionally not performed here: cache
 * names are validated upstream, and ElastiCache is the authority on what it
 * accepts. This function only enforces required arguments and fills defaults.
 *
 * @returns Resolved cache name, user ID, and region.
 * @throws `PhotonError` when `cacheName` or `region` is missing.
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

// --- token minting -----------------------------------------------------------

let defaultRegionProvider: Provider<string> | undefined;

async function resolveDefaultRegion(): Promise<string> {
  defaultRegionProvider ??= loadConfig(NODE_REGION_CONFIG_OPTIONS, NODE_REGION_CONFIG_FILE_OPTIONS);
  return defaultRegionProvider();
}

async function resolveRegion(explicitRegion?: string): Promise<string> {
  if (explicitRegion) return explicitRegion;

  try {
    const region = await resolveDefaultRegion();
    if (!region) throw new PhotonError("region is required for IAM authentication.");
    return region;
  } catch (cause) {
    if (cause instanceof PhotonError) throw cause;
    throw new PhotonError("region is required for IAM authentication.", { cause });
  }
}

function isMissingCredentialsError(error: unknown): boolean {
  // The default chain has no distinct exhaustion error type or code. Other fatal
  // credential configuration errors also use `tryNextLink: false`.
  return (
    error instanceof CredentialsProviderError &&
    !error.tryNextLink &&
    error.message === NO_CREDENTIALS_PROVIDER_ERROR
  );
}

/**
 * Generate a short-lived IAM auth token for an ElastiCache Serverless cache.
 *
 * The token is a SigV4-presigned URL with its scheme stripped, which the
 * cache validates as a password. Credentials are refreshed on every call so
 * rotated credentials are picked up automatically.
 *
 * @returns A scheme-free presigned token string.
 * @throws `PhotonError` when credentials are unavailable or signing fails.
 */
export async function generateIamAuthToken({
  cacheName,
  userId,
  region,
  credentials,
  expiresIn = TOKEN_EXPIRY_SECONDS,
}: GenerateIamAuthTokenOptions): Promise<string> {
  const refreshedCredentials = () => credentials({ forceRefresh: true });

  try {
    const signer = new SignatureV4({
      credentials: refreshedCredentials,
      region,
      service: "elasticache",
      sha256: Hash.bind(null, "sha256"),
    });

    const signed = await signer.presign(
      new HttpRequest({
        protocol: "http:",
        hostname: cacheName,
        method: "GET",
        path: "/",
        query: {
          Action: "connect",
          User: userId,
          ResourceType: "ServerlessCache",
        },
      }),
      { expiresIn },
    );

    // Convert signed request into URL, and remove the leading http://
    return formatUrl(signed).replace(/^http:\/\//, "");
  } catch (error) {
    if (isMissingCredentialsError(error)) {
      throw new PhotonError(NO_CREDENTIALS_MESSAGE, { cause: error });
    }
    if (error instanceof PhotonError && error.message === NO_CREDENTIALS_MESSAGE) {
      throw error;
    }
    throw new PhotonError("Failed to generate IAM auth token", { cause: error });
  }
}

// --- provider composition ----------------------------------------------------

/**
 * Credential provider that returns a fresh `(userId, token)` pair per call.
 *
 * Each call re-resolves credentials and mints a fresh token.
 */
export class IamAuthTokenProvider {
  private readonly cacheName: string;
  private readonly userId: string;
  private readonly region: string;
  private readonly credentials: CredentialsProvider;

  constructor(options: IamAuthTokenProviderOptions) {
    this.cacheName = options.cacheName;
    this.userId = options.userId;
    this.region = options.region;
    this.credentials = options.credentials;
  }

  /** Generate a fresh `(userId, token)` pair. */
  async getAuth(): Promise<[string, string]> {
    const token = await generateIamAuthToken({
      cacheName: this.cacheName,
      userId: this.userId,
      region: this.region,
      credentials: this.credentials,
    });
    return [this.userId, token];
  }

  toString(): string {
    return `IamAuthTokenProvider(cacheName=${this.cacheName}, userId=${this.userId}, region=${this.region})`;
  }
}

/** Assemble a ready-to-use authentication provider for a cache connector. */
export async function buildCacheAuthProvider(
  input: AuthParamsInput & { credentials?: CredentialsProvider },
): Promise<() => Promise<[string, string]>> {
  const region = await resolveRegion(input.region);
  const params = resolveAuthParams({ ...input, region });
  const credentials = input.credentials ?? fromNodeProviderChain();
  const provider = new IamAuthTokenProvider({ ...params, credentials });
  return provider.getAuth.bind(provider);
}
