package io.socket;

/**
 * The Interface IOAcknowledge.
 */
public interface IOAcknowledge {
	
	/**
	 * Acknowledges a socket.io message.
	 *
	 * @param args may be all types which can be serialized by {@link com.google.gson.JsonArray#add(com.google.gson.JsonElement)}
	 */
	void ack(Object... args);
}
