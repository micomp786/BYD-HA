package com.micomp.bydha.daemon;

import com.micomp.bydha.common.Config;
import com.micomp.bydha.common.Paths;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.function.Consumer;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** One MQTT connection to the user's broker, with Last Will and HA birth handling. */
final class MqttLink implements MqttCallbackExtended {
    private static final String TAG = "Mqtt";

    private final Config cfg;
    private final String version;
    private final Consumer<String> onCommand;
    private MqttClient client;
    private volatile String state = "not started";

    MqttLink(Config cfg, String version, Consumer<String> onCommand) {
        this.cfg = cfg;
        this.version = version;
        this.onCommand = onCommand;
    }

    String state() {
        return state;
    }

    boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    /** Blocks until the first connection succeeds; Paho reconnects by itself afterwards. */
    void connectLoop() {
        long delay = 5;
        while (true) {
            try {
                connectOnce();
                return;
            } catch (Exception e) {
                state = "error: " + Byd.describe(e.getCause() != null ? e.getCause() : e);
                Log.i(TAG, "connect failed, retry in " + delay + "s: " + state);
                try {
                    Thread.sleep(delay * 1000);
                } catch (InterruptedException ie) {
                    return;
                }
                delay = Math.min(delay * 2, 120);
            }
        }
    }

    private void connectOnce() throws Exception {
        String scheme = cfg.tls ? "ssl://" : "tcp://";
        String uri = scheme + cfg.brokerHost + ":" + cfg.brokerPort;
        state = "connecting to " + uri;
        Log.i(TAG, state + (cfg.tailscaleEnabled ? " via Tailscale" : ""));
        if (client == null) {
            client = new MqttClient(uri, "bydha-" + cfg.vehicleId, new MemoryPersistence());
            client.setCallback(this);
        }
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        o.setAutomaticReconnect(true);
        o.setKeepAliveInterval(60);
        o.setConnectionTimeout(20);
        o.setMaxReconnectDelay(120);
        o.setMaxInflight(200);
        if (!cfg.username.isEmpty()) {
            o.setUserName(cfg.username);
            o.setPassword(cfg.password.toCharArray());
        }
        o.setWill(cfg.topic("availability"), "offline".getBytes(StandardCharsets.UTF_8), 1, true);
        if (cfg.tls) {
            if (cfg.tlsInsecure) o.setSocketFactory(trustAllSsl());
            if (cfg.tailscaleEnabled) {
                Log.i(TAG, "TLS cannot go through the Tailscale SOCKS proxy; connecting directly. "
                        + "Tailscale already encrypts the link, so use plain MQTT with Tailscale.");
            }
        } else if (cfg.tailscaleEnabled) {
            o.setSocketFactory(new SocksSocketFactory());
        }
        client.connect(o);
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        state = "connected";
        Log.i(TAG, (reconnect ? "reconnected" : "connected") + " to " + serverURI);
        try {
            client.subscribe(cfg.topic("cmd"), 1);
            client.subscribe(cfg.discoveryPrefix + "/status", 1);
        } catch (Exception e) {
            Log.w(TAG, "subscribe failed", e);
        }
        // Publish from a worker thread so the Paho callback thread stays free.
        Thread t = new Thread(() -> {
            publish(cfg.topic("availability"), "online", true);
            sendDiscovery();
        }, "bydha-discovery");
        t.setDaemon(true);
        t.start();
    }

    void sendDiscovery() {
        try {
            for (Map.Entry<String, String> e : Discovery.build(cfg, version).entrySet()) {
                publish(e.getKey(), e.getValue(), true);
            }
            Log.i(TAG, "discovery sent");
        } catch (Exception e) {
            Log.w(TAG, "discovery failed", e);
        }
    }

    synchronized boolean publish(String topic, String payload, boolean retained) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 20; attempt++) {
            MqttClient c = client;
            if (c == null || !c.isConnected()) return false;
            try {
                c.publish(topic, body, 1, retained);
                return true;
            } catch (MqttException e) {
                if (e.getReasonCode() == MqttException.REASON_CODE_MAX_INFLIGHT) {
                    try { Thread.sleep(250); } catch (InterruptedException ie) { return false; }
                    continue;
                }
                Log.w(TAG, "publish " + topic + " failed", e);
                return false;
            }
        }
        Log.w(TAG, "publish " + topic + " gave up: broker not acknowledging");
        return false;
    }

    @Override
    public void connectionLost(Throwable cause) {
        state = "disconnected: " + Byd.describe(cause);
        Log.i(TAG, state);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String body = new String(message.getPayload(), StandardCharsets.UTF_8).trim();
        if (topic.equals(cfg.discoveryPrefix + "/status")) {
            if ("online".equalsIgnoreCase(body)) {
                // Home Assistant restarted: re-announce so entities come back immediately.
                new Thread(this::sendDiscovery, "rediscover").start();
            }
            return;
        }
        onCommand.accept(body);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    void close() {
        MqttClient c = client;
        if (c == null) return;
        try {
            if (c.isConnected()) {
                publish(cfg.topic("availability"), "offline", true);
                c.disconnect(2000);
            }
            c.close();
        } catch (Exception ignored) {
        }
    }

    /** Routes the broker connection through tailscaled's local SOCKS5 server. */
    static final class SocksSocketFactory extends SocketFactory {
        private static Proxy proxy() {
            return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", Paths.SOCKS_PORT));
        }

        @Override public Socket createSocket() { return new Socket(proxy()); }

        @Override public Socket createSocket(String host, int port) throws IOException {
            Socket s = createSocket();
            s.connect(InetSocketAddress.createUnresolved(host, port));
            return s;
        }

        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return createSocket(host, port);
        }

        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket s = createSocket();
            s.connect(new InetSocketAddress(host, port));
            return s;
        }

        @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return createSocket(address, port);
        }
    }

    private static SocketFactory trustAllSsl() throws Exception {
        TrustManager[] tm = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tm, new java.security.SecureRandom());
        return ssl.getSocketFactory();
    }
}
