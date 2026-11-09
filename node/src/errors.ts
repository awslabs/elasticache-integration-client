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

/** The proxy rejected the `aws.config` overrides (`CONFIG_INVALID`). */
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
 * ElastiCacheIntegrations has no cache connection and the configured origin-fallback window expired.
 *
 * This sits outside the ElastiCacheIntegrationsError hierarchy so middleware cannot mistake it
 * for a failure that is safe to pass directly to origin. Smithy's retry
 * strategy may retry it, matching Python's retryable transport error.
 */
export class ElastiCacheIntegrationsCacheUnavailable extends Error {
  readonly $retryable = {};
  readonly endpointUrl: string;

  constructor(endpointUrl: string) {
    super(
      `ElastiCacheIntegrations has no connection to the cache, so the request to endpoint "${endpointUrl}" could not be served from it.`,
    );
    this.name = new.target.name;
    this.endpointUrl = endpointUrl;
  }
}
