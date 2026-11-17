// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.model;

import java.util.Locale;
import java.util.Objects;

/** Service-agnostic request metadata. */
public final class RequestMetadata {

    private final String service;
    private final String operation;

    /**
     * Create request metadata.
     *
     * @param service the AWS service name, stored in lower case, or {@code null}
     * @param operation the SDK operation name, or {@code null}
     */
    public RequestMetadata(String service, String operation) {
        this.service = service != null ? service.toLowerCase(Locale.ROOT) : null;
        this.operation = operation;
    }

    /**
     * Return the AWS service name.
     *
     * @return the lower-case service name, or {@code null}
     */
    public String getService() {
        return service;
    }

    /**
     * Return the SDK operation name.
     *
     * @return the operation name, or {@code null}
     */
    public String getOperation() {
        return operation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RequestMetadata)) return false;
        RequestMetadata that = (RequestMetadata) o;
        return Objects.equals(service, that.service) && Objects.equals(operation, that.operation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(service, operation);
    }

    @Override
    public String toString() {
        return "RequestMetadata{service='" + service + "', operation='" + operation + "'}";
    }
}
