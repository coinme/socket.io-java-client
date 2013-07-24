package io.socket;

/**
 * The Class SocketIOException.
 */
public class SocketIOException extends Exception {

	/** The Constant serialVersionUID. */
	private static final long serialVersionUID = 4965561569568761814L;

	/**
	 * Instantiates a new SocketIOException.
	 * 
	 * @param message
	 *            the message
	 */
	public SocketIOException(String message) {
		super(message);
	}

	/**
	 * Instantiates a new SocketIOException.
	 * 
	 * @param ex
	 *            the exception.
	 */
	public SocketIOException(String message, Exception ex) {
		super(message, ex);
	}
}
