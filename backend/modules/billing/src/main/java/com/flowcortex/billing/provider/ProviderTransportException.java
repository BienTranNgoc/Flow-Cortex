package com.flowcortex.billing.provider;

/**
 * A provider call whose result is unknown: it may or may not have taken effect. It carries no verdict, so a
 * caller must keep the request id and query with it rather than retry under a new one.
 */
public class ProviderTransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What went wrong on the wire. */
    public enum Kind {
        TIMEOUT("timeout"),
        NETWORK("network"),
        HTTP_STATUS("http_status"),
        MALFORMED_RESPONSE("malformed_response");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final Kind kind;
    private final String provider;
    private final String endpoint;
    private final Integer status;

    public ProviderTransportException(Kind kind, String provider, String endpoint, Integer status) {
        this(kind, provider, endpoint, status, null);
    }

    public ProviderTransportException(Kind kind, String provider, String endpoint, Integer status, Throwable cause) {
        super("provider call unresolved: provider=" + provider + " endpoint=" + endpoint + " kind=" + kind.value()
                + (status == null ? "" : " status=" + status)
                + "; the operation may have taken effect, query it with the same request id", cause);
        this.kind = kind;
        this.provider = provider;
        this.endpoint = endpoint;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    public String provider() {
        return provider;
    }

    public String endpoint() {
        return endpoint;
    }

    /** The HTTP status when the provider answered, else null. */
    public Integer status() {
        return status;
    }
}
