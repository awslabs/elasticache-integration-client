/** Base class for all Photon errors; an optional originating `cause` is passed to native `Error`. */
export class PhotonError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = new.target.name;
  }
}

/** Connection bring-up failed (DNS, TCP connect, or TLS handshake). */
export class PhotonConnectionError extends PhotonError {}

/** Invalid Photon client or reconnect configuration. */
export class PhotonConfigError extends PhotonError {}

/** A RESP frame was malformed or could not be decoded. */
export class PhotonProtocolError extends PhotonError {}

/** The socket died mid-flight; pending requests are failed. */
export class PhotonConnectionLost extends PhotonError {}

/** A request exceeded its per-request timeout (named to avoid shadowing the builtin). */
export class PhotonTimeoutError extends PhotonError {}

/**
 * Photon has no cache connection and the configured origin-fallback window expired.
 *
 * This sits outside the PhotonError hierarchy so middleware cannot mistake it
 * for a failure that is safe to pass directly to origin. Smithy's retry
 * strategy may retry it, matching Python's retryable transport error.
 */
export class PhotonCacheUnavailable extends Error {
  readonly $retryable = {};
  readonly endpointUrl: string;

  constructor(endpointUrl: string) {
    super(
      `Photon has no connection to the cache, so this request to "${endpointUrl}" could not be served from it.`,
    );
    this.name = new.target.name;
    this.endpointUrl = endpointUrl;
  }
}
