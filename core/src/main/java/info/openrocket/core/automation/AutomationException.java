package info.openrocket.core.automation;

/**
 * Error raised by an automation command. The message is reported to the caller as-is,
 * so it should describe what was wrong with the request and how to fix it.
 */
public class AutomationException extends RuntimeException {
	private final String code;

	public AutomationException(String message) {
		this("error", message, null);
	}

	public AutomationException(String message, Throwable cause) {
		this("error", message, cause);
	}

	public AutomationException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static AutomationException notFound(String what) {
		return new AutomationException("not_found", what, null);
	}

	public static AutomationException badParam(String message) {
		return new AutomationException("invalid_params", message, null);
	}
}
