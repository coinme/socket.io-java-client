package io.socket;

import org.json.JSONArray;

/**
 * The Interface IOAcknowledge.
 */
public interface IOAcknowledge {
	
	/**
	 * Acknowledges a socket.io message.
	 *
	 * @param args may be all types which can be serialized by {@link JSONArray#put(Object)}
	 */
	void ack(Object... args);
}
