package net.zaminmc.rebellion.api;

/**
 * Thrown when a required Rebellion service is not registered — typically because
 * the providing module failed to enable, is missing, or shut down.
 */
public final class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
