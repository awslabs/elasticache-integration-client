/** Base class for all Photon errors; an optional originating `cause` is passed to native `Error`. */
export class PhotonError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = new.target.name;
  }
}

/** Connection bring-up failed (DNS, TCP connect, or TLS handshake). */
export class PhotonConnectionError extends PhotonError {}

/** A RESP frame was malformed or could not be decoded. */
export class PhotonProtocolError extends PhotonError {}

/** The socket died mid-flight; pending requests are failed. */
export class PhotonConnectionLostError extends PhotonError {}

/** A request exceeded its per-request timeout (named to avoid shadowing the builtin). */
export class PhotonTimeoutError extends PhotonError {}
