/** Base class for all ElastiCacheIntegrations errors; an optional originating `cause` is passed to native `Error`. */
export class ElastiCacheIntegrationsError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = new.target.name;
  }
}

/** Connection bring-up failed (DNS, TCP connect, or TLS handshake). */
export class ElastiCacheIntegrationsConnectionError extends ElastiCacheIntegrationsError {}

/** Invalid ElastiCacheIntegrations client or reconnect configuration. */
export class ElastiCacheIntegrationsConfigError extends ElastiCacheIntegrationsError {}

/** The cache rejected the `aws.config` overrides (`CONFIG_INVALID`). */
export class ElastiCacheIntegrationsOverridesRejected extends ElastiCacheIntegrationsError {}

/** The resolved config carried a predicate this client cannot evaluate. */
export class ElastiCacheIntegrationsConfigUnevaluable extends ElastiCacheIntegrationsError {}

/** A RESP frame was malformed or could not be decoded. */
export class ElastiCacheIntegrationsProtocolError extends ElastiCacheIntegrationsError {}

/** ElastiCacheIntegrations explicitly rejected a request with a RESP error reply. */
export class ElastiCacheIntegrationsRejectedError extends ElastiCacheIntegrationsError {
  constructor(
    readonly code: string,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
  }
}

/** The socket died mid-flight; pending requests are failed. */
export class ElastiCacheIntegrationsConnectionLost extends ElastiCacheIntegrationsError {}

/** A request exceeded its per-request timeout (named to avoid shadowing the builtin). */
export class ElastiCacheIntegrationsTimeoutError extends ElastiCacheIntegrationsError {}

/** A request exhausted its admission budget before any bytes were sent to the cache. */
export class ElastiCacheIntegrationsRequestNotSent extends ElastiCacheIntegrationsError {}

/** A background re-authentication was rejected or returned an invalid reply. */
export class ElastiCacheIntegrationsAuthError extends ElastiCacheIntegrationsError {}

/**
 * ElastiCacheIntegrations could not serve a request from the cache and origin fallback is not allowed.
 *
 * This sits outside the ElastiCacheIntegrationsError hierarchy so middleware cannot mistake it
 * for a failure that is safe to pass directly to origin. Smithy's retry
 * strategy may retry it, matching Python's retryable transport error.
 */
export class ElastiCacheIntegrationsCacheUnavailable extends Error {
  readonly $retryable = {};
  readonly endpointUrl: string;

  constructor(endpointUrl: string, reason?: string) {
    const message =
      "ElastiCacheIntegrations could not serve the request from the cache, and origin fallback is not allowed";
    super(reason === undefined ? `${message}.` : `${message}: ${reason}`);
    this.name = new.target.name;
    this.endpointUrl = endpointUrl;
  }
}

/**
 * ElastiCacheIntegrations could not complete the request to the origin service.
 *
 * A call without the cache can fail the same way, so this is not an
 * ElastiCacheIntegrationsError, and Smithy's retry strategy may retry it.
 */
export class ElastiCacheIntegrationsOriginError extends Error {
  readonly $retryable = {};
  readonly endpointUrl: string;

  constructor(endpointUrl: string, options?: ErrorOptions) {
    super(
      `ElastiCacheIntegrations could not complete the origin request to endpoint "${endpointUrl}".`,
      options,
    );
    this.name = new.target.name;
    this.endpointUrl = endpointUrl;
  }
}
