package io.socket;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
* The Class IOConnection.
*/
public class IOConnection implements IOCallback {
	/** Debug LOGGER */
	static final Logger LOGGER = LoggerFactory.getLogger(IOConnection.class);

	public static final String FRAME_DELIMITER = "\ufffd";

	/** The Constant STATE_INIT. */
	public static final int STATE_INIT = 0;

	/** The Constant STATE_HANDSHAKE. */
    public static final int STATE_HANDSHAKE = 1;

	/** The Constant STATE_CONNECTING. */
    public static final int STATE_CONNECTING = 2;

	/** The Constant STATE_READY. */
    public static final int STATE_READY = 3;

	/** The Constant STATE_INTERRUPTED. */
    public static final int STATE_INTERRUPTED = 4;

	/** The Constant STATE_INVALID. */
    public static final int STATE_INVALID = 6;

	/** The state. */
	private int state = STATE_INIT;

	/** Socket.io path. */
	public static final String SOCKET_IO_1 = "/socket.io/1/";

	/** The SSL socket factory for HTTPS connections */
	private static SSLContext sslContext = null;

	/** All available connections. */
	private static HashMap<String, List<IOConnection>> connections = new HashMap<String, List<IOConnection>>();

    private static final JsonParser JSON_PARSER = new JsonParser();

	/** The url for this connection. */
	private URL url;

	/** The transport for this connection. */
	private IOTransport transport;

	/** The connection timeout. */
	private int connectTimeout = 10000;

	/** The session id of this connection. */
	private String sessionId;

	/** The heartbeat timeout. Set by the server */
	private long heartbeatTimeout;

	/** The closing timeout. Set By the server */
	private long closingTimeout;

	/** The protocols supported by the server. */
	private List<String> protocols;

	/** The output buffer used to cache messages while (re-)connecting. */
	private ConcurrentLinkedQueue<String> outputBuffer = new ConcurrentLinkedQueue<String>();

	/** The sockets of this connection. */
	private HashMap<String, SocketIO> sockets = new HashMap<String, SocketIO>();

	/** Custom Request headers used while handshaking */
	private Properties headers;

    /** Custom gson serialization */
    private Gson gson;

	/**
	 * The first socket to be connected. the socket.io server does not send a
	 * connected response to this one.
	 */
	private SocketIO firstSocket = null;

	/** The reconnect timer. IOConnect waits a second before trying to reconnect */
	final private Timer backgroundTimer = new Timer("backgroundTimer");

	/** A String representation of {@link #url}. */
	private String urlStr;

	/**
	 * The last occurred exception, which will be given to the user if
	 * IOConnection gives up.
	 */
	private Exception lastException;

	/** The next ID to use. */
	private int nextId = 1;

	/** Acknowledges. */
	HashMap<Integer, IOAcknowledge> acknowledge = new HashMap<Integer, IOAcknowledge>();

	/** true if there's already a keepalive in {@link #outputBuffer}. */
	private boolean keepAliveInQueue;

	/**
	 * The heartbeat timeout task. Only null before connection has been
	 * initialised.
	 */
	private HearbeatTimeoutTask heartbeatTimeoutTask;

	/**
	 * The Class HearbeatTimeoutTask. Handles dropping this IOConnection if no
	 * heartbeat is received within life time.
	 */
	private class HearbeatTimeoutTask extends TimerTask {

		/*
		 * (non-Javadoc)
		 *
		 * @see java.util.TimerTask#run()
		 */
		@Override
		public void run() {
			error(new SocketIOException(
					"Timeout Error. No heartbeat from server within life time of the socket. closing.",
					lastException));
		}
	}

	/** The reconnect task. Null if no reconnection is in progress. */
	private ReconnectTask reconnectTask = null;

	/**
	 * The Class ReconnectTask. Handles reconnect attempts
	 */
	private class ReconnectTask extends TimerTask {

		/*
		 * (non-Javadoc)
		 *
		 * @see java.util.TimerTask#run()
		 */
		@Override
		public void run() {
			connectTransport();
			if (!keepAliveInQueue) {
				sendPlain("2::");
				keepAliveInQueue = true;
			}
		}
	}

	/**
	 * The Class ConnectThread. Handles connecting to the server with an
     *
     * TODO: This is a thread boundary. We should wakelock around here. Not sure.
     *
	 * {@link IOTransport}
	 */
	private class ConnectThread extends Thread {
		/**
		 * Instantiates a new thread for handshaking/connecting.
		 */
		public ConnectThread() {
			super("ConnectThread");
		}

		/**
		 * Tries handshaking if necessary and connects with corresponding
		 * transport afterwards.
		 */
		@Override
		public void run() {
            if (isConnected()) {
                LOGGER.warn("Was already connected, not attempting to connect.");
                return;
            }

			if (IOConnection.this.getState() == STATE_INIT) {
				handshake();
            }

			connectTransport();
		}

	}

	/**
	 * Set the socket factory used for SSL connections.
	 *
	 * @param sslContext
	 */
	public static void setSslContext(SSLContext sslContext) {
		IOConnection.sslContext = sslContext;
	}

	/**
	 * Get the socket factory used for SSL connections.
	 *
	 * @return socketFactory
	 */
	public static SSLContext getSslContext() {
		return sslContext;
	}

	/**
	 * Creates a new connection or returns the corresponding one.
	 *
	 * @param origin
	 *            the origin
	 * @param socket
	 *            the socket
	 * @return a IOConnection object
	 */
	static public IOConnection register(String origin, SocketIO socket, Gson gson) {
		List<IOConnection> list = connections.get(origin);
		if (list == null) {
			list = Collections.synchronizedList(new LinkedList<IOConnection>());
			connections.put(origin, list);
		} else {
			synchronized (list) {
				for (IOConnection connection : list) {
					if (connection.register(socket))
						return connection;
				}
			}
		}

		IOConnection connection = new IOConnection(origin, socket, gson);
		list.add(connection);
		return connection;
	}

	/**
	 * Connects a socket to the IOConnection.
	 *
	 * @param socket
	 *            the socket to be connected
	 * @return true, if successfully registered on this transport, otherwise
	 *         false.
	 */
	public synchronized boolean register(SocketIO socket) {
		String namespace = socket.getNamespace();
		if (sockets.containsKey(namespace))
			return false;
		sockets.put(namespace, socket);
		socket.setHeaders(headers);
		IOMessage connect = new IOMessage(IOMessage.TYPE_CONNECT,
				socket.getNamespace(), "");
		sendPlain(connect.toString());
		return true;
	}

	/**
	 * Disconnect a socket from the IOConnection. Shuts down this IOConnection
	 * if no further connections are available for this IOConnection.
	 *
	 * @param socket
	 *            the socket to be shut down
	 */
	public synchronized void unregister(SocketIO socket) {
		sendPlain("0::" + socket.getNamespace());
		sockets.remove(socket.getNamespace());
		socket.getCallback().onDisconnect();

		if (sockets.size() == 0) {
			cleanup();
		}
	}

	/**
	 * Handshake.
	 *
	 */
	private void handshake() {
		String response;
		URLConnection connection;
		try {
			setState(STATE_HANDSHAKE);
            String connectUrl = IOConnection.this.url.toString() + SOCKET_IO_1;
            LOGGER.debug(this.hashCode() + ": " + connectUrl);

			connection = new URL(connectUrl).openConnection();
			if (connection instanceof HttpsURLConnection) {
				((HttpsURLConnection) connection)
						.setSSLSocketFactory(sslContext.getSocketFactory());
			}
			connection.setConnectTimeout(connectTimeout);
			connection.setReadTimeout(connectTimeout);

			/* Setting the request headers */
			for (Entry<Object, Object> entry : headers.entrySet()) {
				connection.setRequestProperty((String) entry.getKey(),
						(String) entry.getValue());
			}

			InputStream stream = connection.getInputStream();
			Scanner in = new Scanner(stream);
			response = in.nextLine();
			String[] data = response.split(":");
            this.onSessionId(data[0]);

            LOGGER.debug(this.hashCode() + ": " + response);

			heartbeatTimeout = Long.parseLong(data[1]) * 1000;
			closingTimeout = Long.parseLong(data[2]) * 1000;

            LOGGER.debug(String.format("Setting heartbeat timeout to %ds, close timeout to %ss.", heartbeatTimeout / 1000, closingTimeout / 1000));

			protocols = Arrays.asList(data[3].split(","));
		} catch (Exception e) {
			error(new SocketIOException("Error while handshaking", e));
		}
	}

	/**
	 * Connect transport.
	 */
	private synchronized void connectTransport() {
		if (getState() == STATE_INVALID)
			return;
		setState(STATE_CONNECTING);
		if (protocols.contains(WebsocketTransport.TRANSPORT_NAME))
			transport = WebsocketTransport.create(url, this);
		else if (protocols.contains(XhrTransport.TRANSPORT_NAME))
			transport = XhrTransport.create(url, this);
		else {
			error(new SocketIOException(
					"Server supports no available transports. You should reconfigure the server to support an available transport"));
			return;
		}
		transport.connect();
	}

	/**
	 * Creates a new {@link IOAcknowledge} instance which sends its arguments
	 * back to the server.
	 *
	 * @param message
	 *            the message
	 * @return an {@link IOAcknowledge} instance, may be <code>null</code> if
	 *         server doesn't request one.
	 */
	private IOAcknowledge remoteAcknowledge(IOMessage message) {
		String _id = message.getId();
		if (_id.equals(""))
			return null;
		else if (_id.endsWith("+") == false)
			_id = _id + "+";
		final String id = _id;
		final String endPoint = message.getEndpoint();
		return new IOAcknowledge() {
			@Override
			public void ack(Object... args) {
//				JsonArray array = new JsonArray();
//				for (Object o : args) {
//					try {
//						array.add(o == null ? JsonNull.INSTANCE : gson.toJsonTree(o));
//					} catch (Exception e) {
//						error(new SocketIOException(
//								"You can only put values in IOAcknowledge.ack() which can be handled by JSONArray.put()",
//								e));
//					}
//				}
				IOMessage ackMsg = new IOMessage(IOMessage.TYPE_ACK, endPoint, id + gson.toJson(args));
//				IOMessage ackMsg = new IOMessage(IOMessage.TYPE_ACK, endPoint, id + array.toString());

				sendPlain(ackMsg.toString());
			}
		};
	}

	/**
	 * adds an {@link IOAcknowledge} to an {@link IOMessage}.
	 *
	 * @param message
	 *            the {@link IOMessage}
	 * @param ack
	 *            the {@link IOAcknowledge}
	 */
	private void synthesizeAck(IOMessage message, IOAcknowledge ack) {
		if (ack != null) {
			int id = nextId++;
			acknowledge.put(id, ack);
			message.setId(id + "+");
		}
	}

	/**
	 * Instantiates a new IOConnection.
	 *
	 * @param url
	 *            the URL
	 * @param socket
	 *            the socket
	 */
	private IOConnection(String url, SocketIO socket, Gson gson) {
		try {
			this.url = new URL(url);
			this.urlStr = url;
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
        this.gson = gson;

		firstSocket = socket;
		headers = socket.getHeaders();
		sockets.put(socket.getNamespace(), socket);
		new ConnectThread().start();
	}

	/**
	 * Cleanup. IOConnection is not usable after this calling this.
	 */
	private synchronized void cleanup() {
        LOGGER.info(this.hashCode() + ": " + "Cleanup begin.");

		setState(STATE_INVALID);
		if (transport != null)
			transport.disconnect();
		sockets.clear();
		synchronized (connections) {
			List<IOConnection> con = connections.get(urlStr);
			if (con != null && con.size() > 1)
				con.remove(this);
			else
				connections.remove(urlStr);
		}
		LOGGER.info(this.hashCode() + ": " + "Cleanup complete.");
		backgroundTimer.cancel();
	}

	/**
	 * Populates an error to the connected {@link IOCallback}s and shuts down.
	 *
	 * @param e
	 *            an exception
	 */
	private void error(SocketIOException e) {
		for (SocketIO socket : sockets.values()) {
			socket.getCallback().onError(e);
		}
		cleanup();
	}

	/**
	 * Sends a plain message to the {@link IOTransport}.
	 *
	 * @param text
	 *            the Text to be send.
	 */
	private synchronized void sendPlain(String text) {
		if (getState() == STATE_READY)
			try {
				LOGGER.info(this.hashCode() + ": " + "> " + text);
				transport.send(text);
			} catch (Exception e) {
				LOGGER.info(this.hashCode() + ": " + "IOEx: sendPlain" + e);
				outputBuffer.add(text);
			}
		else {
			outputBuffer.add(text);
		}
	}

	/**
	 * Invalidates an {@link IOTransport}, used for forced reconnecting.
	 */
	private void invalidateTransport() {
		if (transport != null)
			transport.invalidate();
		transport = null;
	}

	/**
	 * Reset timeout.
	 */
	private synchronized void resetTimeout() {
		if (heartbeatTimeoutTask != null) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Cancelling existing heartbeatTimeoutTask.");
            }

			heartbeatTimeoutTask.cancel();
		}

		if (getState() != STATE_INVALID) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Scheduling heartbeatTimeoutTask for " + new Date(System.currentTimeMillis() + closingTimeout + heartbeatTimeout));
            }

            heartbeatTimeoutTask = new HearbeatTimeoutTask();
			backgroundTimer.schedule(heartbeatTimeoutTask, closingTimeout
					+ heartbeatTimeout);
		}
	}

	/**
	 * finds the corresponding callback object to an incoming message. Returns a
	 * dummy callback if no corresponding callback can be found
	 *
	 * @param message
	 *            the message
	 * @return the iO callback
	 * @throws SocketIOException
	 */
	private IOCallback findCallback(IOMessage message) throws SocketIOException {
		if ("".equals(message.getEndpoint()))
			return this;
		SocketIO socket = sockets.get(message.getEndpoint());
		if (socket == null) {
			throw new SocketIOException(this.hashCode() + ": " + "Cannot find socket for '"
					+ message.getEndpoint() + "'");
		}
		return socket.getCallback();
	}

	/**
	 * Transport connected.
	 *
	 * {@link IOTransport} calls this when a connection is established.
	 */
	public synchronized void transportConnected() {
		setState(STATE_READY);
		if (reconnectTask != null) {
			reconnectTask.cancel();
			reconnectTask = null;
		}
		resetTimeout();
		if (transport.canSendBulk()) {
			ConcurrentLinkedQueue<String> outputBuffer = this.outputBuffer;
			this.outputBuffer = new ConcurrentLinkedQueue<String>();
			try {
				// DEBUG
				String[] texts = outputBuffer.toArray(new String[outputBuffer
						.size()]);
				LOGGER.info("Bulk start:");
				for (String text : texts) {
					LOGGER.info(this.hashCode() + ": " + "> " + text);
				}
				LOGGER.info("Bulk end");
				// DEBUG END
				transport.sendBulk(texts);
			} catch (IOException e) {
				this.outputBuffer = outputBuffer;
			}
		} else {
			String text;
			while ((text = outputBuffer.poll()) != null)
				sendPlain(text);
		}
		this.keepAliveInQueue = false;
	}

	/**
	 * Transport disconnected.
	 *
	 * {@link IOTransport} calls this when a connection has been shut down.
	 */
	public void transportDisconnected() {
		this.lastException = null;
		setState(STATE_INTERRUPTED);
//		reconnect();
	}

	/**
	 * Transport error.
	 *
	 * @param error
	 *            the error {@link IOTransport} calls this, when an exception
	 *            has occurred and the transport is not usable anymore.
	 */
	public void transportError(Exception error) {
		this.lastException = error;
		setState(STATE_INTERRUPTED);
//		reconnect();
	}

	/**
	 * {@link IOTransport} should call this function if it does not support
	 * framing. If it does, transportMessage should be used
	 *
	 * @param text
	 *            the text
	 */
	public void transportData(String text) {
		if (!text.startsWith(FRAME_DELIMITER)) {
			transportMessage(text);
			return;
		}

		Iterator<String> fragments = Arrays.asList(text.split(FRAME_DELIMITER))
				.listIterator(1);
		while (fragments.hasNext()) {
			int length = Integer.parseInt(fragments.next());
			String string = (String) fragments.next();
			// Potential BUG: it is not defined if length is in bytes or
			// characters. Assuming characters.

			if (length != string.length()) {
				error(new SocketIOException("Garbage from server: " + text));
				return;
			}

			transportMessage(string);
		}
	}

	/**
	 * Transport message. {@link IOTransport} calls this, when a message has
	 * been received.
	 *
	 * @param text
	 *            the text
	 */
	public void transportMessage(String text) {
		LOGGER.info(this.hashCode() + ": " + "< " + text);
		IOMessage message;
		try {
			message = new IOMessage(text);
		} catch (Exception e) {
			error(new SocketIOException("Garbage from server: " + text, e));
			return;
		}
		resetTimeout();
		switch (message.getType()) {
		case IOMessage.TYPE_DISCONNECT:
			try {
				findCallback(message).onDisconnect();
			} catch (Exception e) {
				error(new SocketIOException(
						"Exception was thrown in onDisconnect()", e));
			}
			break;
		case IOMessage.TYPE_CONNECT:
			try {
				if (firstSocket != null && "".equals(message.getEndpoint())) {
					if (firstSocket.getNamespace().equals("")) {
						firstSocket.getCallback().onConnect();
					} else {
						IOMessage connect = new IOMessage(
								IOMessage.TYPE_CONNECT,
								firstSocket.getNamespace(), "");
						sendPlain(connect.toString());
					}
				} else {
					findCallback(message).onConnect();
				}
				firstSocket = null;
			} catch (Exception e) {
				error(new SocketIOException(
						"Exception was thrown in onConnect()", e));
			}
			break;
		case IOMessage.TYPE_HEARTBEAT:
			sendPlain("2::");
			break;
		case IOMessage.TYPE_MESSAGE:
			try {
				findCallback(message)
                        .onMessage(message.getData(), remoteAcknowledge(message));
			} catch (Exception e) {
				error(new SocketIOException(
						"Exception was thrown in onMessage(String).\n"
								+ "Message was: " + message.toString(), e));
			}
			break;
		case IOMessage.TYPE_JSON_MESSAGE:
				JsonElement obj = null;
				if (!message.getData().trim().equals("null")) {
                    obj = JSON_PARSER.parse(message.getData());
                }

				try {
					findCallback(message)
                            .onMessage(obj, remoteAcknowledge(message));
				} catch (Exception e) {
					error(new SocketIOException(
							"Exception was thrown in onMessage(JsonObject).\n"
									+ "Message was: " + message.toString(), e));
				}
//				LOGGER.warn("Malformated JSON received");
			break;
		case IOMessage.TYPE_EVENT:
			try {
                JsonElement event = JSON_PARSER.parse(message.getData());
				Object[] argsArray;

                if (event instanceof JsonObject) {
                    JsonObject object = (JsonObject)event;

                    if (object.has("args")) {
                        JsonArray args = object.getAsJsonArray("args");
                        argsArray = new Object[args.size()];
                        for (int i = 0; i < args.size(); i++) {
                            JsonElement e = args.get(i);
                            if (e != null && !e.isJsonNull()) {
                                argsArray[i] = args.get(i);
                            }
                        }
                    } else {
                        argsArray = new Object[0];
                    }

                    String eventName = object.get("name").getAsString();
                    try {
                        findCallback(message).on(eventName,
                                remoteAcknowledge(message), argsArray);
                    } catch (Exception e) {
                        error(new SocketIOException(
                                "Exception was thrown in on(String, JsonObject[]).\n"
                                        + "Message was: " + message.toString(), e));
                    }
                }
			} catch (Exception e) {
				LOGGER.warn("Malformated JSON received");
			}
			break;

		case IOMessage.TYPE_ACK:
			String[] data = message.getData().split("\\+", 2);
			if (data.length == 2) {
				try {
					int id = Integer.parseInt(data[0]);
					IOAcknowledge ack = acknowledge.get(id);
					if (ack == null) {
						LOGGER.warn("Received unknown ack packet");
                    } else {
						JsonArray array = (JsonArray) JSON_PARSER.parse(data[1]);
						Object[] args = new Object[array.size()];
						for (int i = 0; i < args.length; i++) {
							args[i] = array.get(i);
						}
						ack.ack(args);
					}
				} catch (NumberFormatException e) {
					LOGGER.warn("Received malformated Acknowledge! This is potentially filling up the acknowledges!");
				} catch (Exception e) {
					LOGGER.warn("Received malformated Acknowledge data!");
				}
			} else if (data.length == 1) {
				sendPlain("6:::" + data[0]);
			}
			break;
		case IOMessage.TYPE_ERROR:
			try {
				findCallback(message).onError(
						new SocketIOException(message.getData()));
			} catch (SocketIOException e) {
				error(e);
			}
			if (message.getData().endsWith("+0")) {
				// We are advised to disconnect
				cleanup();
			}
			break;
		case IOMessage.TYPE_NOOP:
			break;
		default:
			LOGGER.warn("Unknown type received" + message.getType());
			break;
		}
	}

	/**
	 * forces a reconnect. This had become useful on some android devices which
	 * do not shut down TCP-connections when switching from HSDPA to Wifi
	 */
	public synchronized void reconnect() {
		if (getState() != STATE_INVALID) {
			invalidateTransport();
			setState(STATE_INTERRUPTED);
			if (reconnectTask != null) {
				reconnectTask.cancel();
			}
			reconnectTask = new ReconnectTask();
			backgroundTimer.schedule(reconnectTask, 1000);
		}
	}

	/**
	 * Returns the session id. This should be called from a {@link IOTransport}
	 *
	 * @return the session id to connect to the right Session.
	 */
	public String getSessionId() {
		return sessionId;
	}

	/**
	 * sends a String message from {@link SocketIO} to the {@link IOTransport}.
	 *
	 * @param socket
	 *            the socket
	 * @param ack
	 *            acknowledge package which can be called from the server
	 * @param text
	 *            the text
	 */
	public void send(SocketIO socket, IOAcknowledge ack, String text) {
		IOMessage message = new IOMessage(IOMessage.TYPE_MESSAGE,
				socket.getNamespace(), text);
		synthesizeAck(message, ack);
		sendPlain(message.toString());
	}

	/**
	 * sends a JSON message from {@link SocketIO} to the {@link IOTransport}.
	 *
	 * @param socket
	 *            the socket
	 * @param ack
	 *            acknowledge package which can be called from the server
	 * @param json
	 *            the json
	 */
	public void send(SocketIO socket, IOAcknowledge ack, Object json) {
		IOMessage message = new IOMessage(IOMessage.TYPE_JSON_MESSAGE, socket.getNamespace(), gson.toJson(json));

		synthesizeAck(message, ack);

		sendPlain(message.toString());
	}

	/**
	 * emits an event from {@link SocketIO} to the {@link IOTransport}.
	 *
	 * @param socket
	 *            the socket
	 * @param event
	 *            the event
	 * @param ack
	 *            acknowledge package which can be called from the server
	 * @param args
	 *            the arguments to be send
	 */
	public void emit(SocketIO socket, String event, IOAcknowledge ack, Object... args) {
		try {
            JsonObject json = new JsonObject();

            json.addProperty("name", event);
            json.add("args", gson.toJsonTree(args));

			IOMessage message = new IOMessage(IOMessage.TYPE_EVENT,
					socket.getNamespace(), json.toString());
			synthesizeAck(message, ack);
			sendPlain(message.toString());
		} catch (Exception e) {
			error(new SocketIOException(
					"Error while emitting an event. Make sure you only try to send arguments, which can be serialized into JSON."));
		}

	}

	/**
	 * Checks if IOConnection is currently connected.
	 *
	 * @return true, if is connected
	 */
	public boolean isConnected() {
		return getState() == STATE_READY;
	}

	/**
	 * Gets the current state of this IOConnection.
	 *
	 * @return current state
	 */
	private synchronized int getState() {
		return state;
	}

	/**
	 * Sets the current state of this IOConnection.
	 *
	 * @param state
	 *            the new state
	 */
	private synchronized void setState(int state) {
		if (getState() != STATE_INVALID) {
			this.state = state;

            onState(state);
        }
	}

	/**
	 * gets the currently used transport.
	 *
	 * @return currently used transport
	 */
	public IOTransport getTransport() {
		return transport;
	}

	@Override
	public void onDisconnect() {
		SocketIO socket = sockets.get("");
		if (socket != null)
            LOGGER.error(this.hashCode() + ": onDisconnect");
			socket.getCallback().onDisconnect();
	}

	@Override
	public void onConnect() {
		SocketIO socket = sockets.get("");
		if (socket != null)
            LOGGER.error(this.hashCode() + ": onConnect");
			socket.getCallback().onConnect();
	}

	@Override
	public void onMessage(String data, IOAcknowledge ack) {
		for (SocketIO socket : sockets.values())
			socket.getCallback().onMessage(data, ack);
	}

    @Override
    public void onSessionId(String sessionId) {
        this.sessionId = sessionId;

        // ensure the server uses this sessionId on reconnect
        headers.put("sessionId", sessionId);

        for (SocketIO socket : sockets.values()) {
            socket.getCallback().onSessionId(sessionId);
        }
    }

    @Override
	public void onMessage(JsonElement json, IOAcknowledge ack) {
		for (SocketIO socket : sockets.values())
			socket.getCallback().onMessage(json, ack);
	}

	@Override
	public void on(String event, IOAcknowledge ack, Object... args) {
		for (SocketIO socket : sockets.values())
			socket.getCallback().on(event, ack, args);
	}

	@Override
	public void onError(SocketIOException socketIOException) {
		for (SocketIO socket : sockets.values())
			socket.getCallback().onError(socketIOException);
	}

    @Override
    public void onState(int state) {
        for (SocketIO socket : sockets.values()) {
            socket.getCallback().onState(state);
        }
    }

    public Gson getGson() {
        return gson;
    }

    public void setGson(Gson gson) {
        this.gson = gson;
    }
}
