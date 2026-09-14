package dev.jvmd.core;

/** Implements 12.3: structured protocol and capability failures. */
public final class RpcException extends RuntimeException {
    private final int code;
    private final Object data;
    public RpcException(int code, String message, Object data) {
        super(message); this.code = code; this.data = data;
    }
    public int code() { return code; }
    public Object data() { return data; }
    public static RpcException invalid(String message) {
        return new RpcException(-32602, message, null);
    }
}
