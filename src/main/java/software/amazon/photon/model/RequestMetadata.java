package software.amazon.photon.model;

import java.util.Objects;

/**
 * Metadata about the originating request.
 */
public class RequestMetadata {

    private final String service;
    private final String operation;

    public RequestMetadata(String service, String operation) {
        this.service = service;
        this.operation = operation;
    }

    public String getService() {
        return service;
    }

    public String getOperation() {
        return operation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
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
