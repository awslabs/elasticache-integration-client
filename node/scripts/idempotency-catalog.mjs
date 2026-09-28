/**
 * Validate and return the shared idempotency catalog.
 *
 * @param {unknown} value parsed JSON
 * @returns {Record<string, string[]>}
 */
export function validateIdempotencyCatalog(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError("Idempotency catalog must be a top-level object");
  }

  for (const [service, operations] of Object.entries(value)) {
    if (service.length === 0 || service !== service.toLowerCase()) {
      throw new TypeError(`Idempotency catalog service key must be lowercase: ${service}`);
    }
    if (!Array.isArray(operations)) {
      throw new TypeError(`Idempotency catalog entry for ${service} must be an array`);
    }

    const uniqueOperations = new Set();
    for (const operation of operations) {
      if (typeof operation !== "string" || operation.length === 0) {
        throw new TypeError(
          `Idempotency catalog operations for ${service} must be non-empty strings`,
        );
      }
      if (uniqueOperations.has(operation)) {
        throw new TypeError(
          `Idempotency catalog operation ${operation} is duplicated for ${service}`,
        );
      }
      uniqueOperations.add(operation);
    }
  }

  return /** @type {Record<string, string[]>} */ (value);
}
