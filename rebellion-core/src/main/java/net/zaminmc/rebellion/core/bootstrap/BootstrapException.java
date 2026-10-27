package net.zaminmc.rebellion.core.bootstrap;

/**
 * Fatal, actionable bootstrap failure. The plugin logs the message and disables
 * itself; dependent feature modules refuse to enable because the shared services
 * were never registered.
 */
public final class BootstrapException extends Exception {

    public BootstrapException(String message) {
        super(message);
    }

    public BootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
