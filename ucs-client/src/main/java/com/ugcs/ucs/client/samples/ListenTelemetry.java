package com.ugcs.ucs.client.samples;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.Properties;

import com.ugcs.ucs.client.Client;
import com.ugcs.ucs.client.ServerNotification;
import com.ugcs.ucs.client.ServerNotificationListener;
import com.ugcs.ucs.proto.DomainProto.EventSubscriptionWrapper;
import com.ugcs.ucs.proto.DomainProto.EventWrapper;
import com.ugcs.ucs.proto.DomainProto.Telemetry;
import com.ugcs.ucs.proto.DomainProto.TelemetryEvent;
import com.ugcs.ucs.proto.DomainProto.TelemetrySubscription;
import com.ugcs.ucs.proto.MessagesProto.AuthorizeHciRequest;
import com.ugcs.ucs.proto.MessagesProto.AuthorizeHciResponse;
import com.ugcs.ucs.proto.MessagesProto.LoginRequest;
import com.ugcs.ucs.proto.MessagesProto.SubscribeEventRequest;
import com.ugcs.ucs.proto.MessagesProto.SubscribeEventResponse;
import com.ugcs.ucs.proto.MessagesProto.UnsubscribeEventRequest;

public class ListenTelemetry {
	public static void main(String[] args) {
		long waitMillis = -1L;
		
		boolean usage = false;
		for (int i = 0; i < args.length; ++i) {
			if (args[i].equals("-t")) {
				if (i + 1 == args.length) {
					usage = true;
					break;
				}
				waitMillis = Long.parseLong(args[++i]) * 1000L;
				continue;
			}
		}
		if (waitMillis == -1L)
			usage = true;
		
		if (usage) {
			System.err.println("ListenTelemetry -t waitSeconds");
			System.err.println("");
			System.err.println("\tListen to all telemetry, received by the UCS server");
			System.err.println("\tfor a specified amount of time in seconds.");
			System.err.println("");
			System.err.println("Example:");
			System.err.println("");
			System.err.println("\tListenTelemetry -t 10");
			System.exit(1);
		} else {
			try {
				listenTelemetry(waitMillis);
			} catch (Exception e) {
				e.printStackTrace(System.err);
				System.exit(1);
			}
		}
	}
	
	public static void listenTelemetry(long waitMillis) throws Exception {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		Properties properties = new Properties();
		try (InputStream in = classLoader.getResourceAsStream("client.properties")) {
			properties.load(in);
		}
		
		InetSocketAddress serverAddress = new InetSocketAddress(
				properties.getProperty("server.host", "localhost"),
				Integer.parseInt(properties.getProperty("server.port", "3334")));
		
		try (Client client = new Client(serverAddress)) {
			
			// Register a server telemetry event listener.
			// This method just adds listeners to the client listeners
			// collection and can be invoked any time during the client 
			// life-cycle (even after re-connections).
			// This listener will stay quiet until event subscription will
			// be registered on the-server side.
			client.addNotificationListener(new TelemetryListener());
			client.connect();

			Session session = new Session(client);
			
			// To create a new client session application should send 
			// an AuthorizeHciRequest message and set the clientId field 
			// value to -1. In this case server will create a new session 
			// object and return its identity in the clientId field.
			session.authorizeHci();
	
			// Any session should be associated with an authenticated user. 
			// To authenticate user provide its credentials via LoginRequest 
			// message.
			session.login(
					properties.getProperty("user.login"),
					properties.getProperty("user.password"));
			
			int subscriptionId = session.subscribeTelemetryEvent();
			try {
				long t0 = System.currentTimeMillis() + waitMillis;
				while (waitMillis > 0) {
					Thread.sleep(waitMillis);
					waitMillis = t0 - System.currentTimeMillis();
				}
			} finally {
				session.unsubscribe(subscriptionId);
			}
		}
	}
	
	static class TelemetryListener implements ServerNotificationListener {
		@Override
		public void notificationReceived(ServerNotification event) {
			
			// Listener is invoked on every server notification.
			// To filter notifications within particular subscriptions all 
			// events provide access to the associated subscription id.
			// 
			// So, you can do something like this:
			// if (event.getSubscriptionId() == subscriptionId) {
			//     ...
			// }
			
			EventWrapper wrapper = event.getEvent();
			if (wrapper == null)
				return;
			TelemetryEvent telemetryEvent = wrapper.getTelemetryEvent();
			if (telemetryEvent == null)
				return;
			
			// Telemetry event object contains vehicle tail number
			// and incremental telemetry batch.
			// (lat, lon) position is not necessary present 
			// in every batch.
			
			System.out.println("Telemetry received: " +
					telemetryEvent.getVehicle() == null
							? "Unknown vehicle"
							: telemetryEvent.getVehicle().getTailNumber());
			for (Telemetry telemtry : telemetryEvent.getTelemetryList()) {
				System.out.println("\t" + new Date(telemtry.getTime()) + 
						"\t" + telemtry.getType() + 
						" = " + telemtry.getValue());
			}
		}
	}
	
	static class Session {
		private final Client client;
		private int clientId = -1;
		
		public Session(Client client) {
			if (client == null)
				throw new IllegalArgumentException("client");
			
			this.client = client;
		}
		
		public void authorizeHci() throws Exception {
			clientId = -1;
			AuthorizeHciRequest request = AuthorizeHciRequest.newBuilder()
				.setClientId(clientId)
				.build();
			AuthorizeHciResponse response = client.execute(request);
			clientId = response.getClientId();
		} 
	
		public void login(String login, String password) throws Exception {
			if (login == null || login.isEmpty())
				throw new IllegalArgumentException("login");
			if (password == null || password.isEmpty())
				throw new IllegalArgumentException("password");

			LoginRequest request = LoginRequest.newBuilder()
					.setClientId(clientId)
					.setUserLogin(login)
					.setUserPassword(password)
					.build();
			client.execute(request);
		}
		
		public int subscribeTelemetryEvent() throws Exception {
			SubscribeEventRequest request = SubscribeEventRequest.newBuilder()
					.setClientId(clientId)
					.setSubscription(EventSubscriptionWrapper.newBuilder()
							.setTelemetrySubscription(TelemetrySubscription.newBuilder()))
					.build();
			SubscribeEventResponse response = client.execute(request);
			return response.getSubscriptionId();
		}
		
		public void unsubscribe(int subscriptionId) throws Exception {
			UnsubscribeEventRequest request = UnsubscribeEventRequest.newBuilder()
					.setClientId(clientId)
					.setSubscriptionId(subscriptionId)
					.build();
			client.execute(request);
		}
	}
}
