package dev.purefps.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;

import org.lwjgl.opengl.Display;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Estimateur de latence réseau réelle 100 % légitime (Watchdog-safe) pour les serveurs qui renvoient
 * un faux ping de 0 ou 1 ms dans le paquet Tab (S38PacketPlayerListItem) — fréquent derrière les proxies
 * BungeeCord / Velocity / Waterfall / TCPShield ou sur les lobbys anti-sniping.
 *
 * Fonctionnement :
 * - Si NetworkPlayerInfo.getResponseTime() > 1 ms, la valeur serveur est utilisée directement (0 trafic).
 * - Sinon (<= 1 ms), un thread daemon interroge au plus toutes les 7 secondes l'adresse publique du serveur
 *   via le protocole standard Minecraft 1.8.9 Server List Ping (C01PacketPing -> S01PacketPong), avec repli
 *   sur le RTT de connexion TCP si le proxy bloque les requêtes SLP.
 */
public final class RealPingEstimator {

    private static final int MC_189_PROTOCOL_VERSION = 47;
    private static final int SOCKET_TIMEOUT_MS = 2500;
    private static final long PROBE_INTERVAL_MS = 7000L;
    private static final long DNS_CACHE_TTL_MS = 60_000L;
    private static final int MAX_VALID_PING_MS = 2500;

    private static final ExecutorService PROBE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MashClient-PingProbe");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean PROBE_IN_FLIGHT = new AtomicBoolean(false);

    private static volatile String activeServerIp = "";
    private static volatile int estimatedPingMs = -1;
    private static volatile long lastProbeStartMs = 0L;
    private static volatile int consecutiveFailures = 0;

    // Cache DNS de l'endpoint résolu (évite une résolution DNS toutes les 7 s)
    private static volatile String cachedDnsHost = "";
    private static volatile int cachedDnsPort = -1;
    private static volatile InetSocketAddress cachedDnsEndpoint = null;
    private static volatile long cachedDnsTimeMs = 0L;

    // Filtre médian sur les 3 derniers échantillons pour éliminer tout pic isolé
    private static final int[] SAMPLE_RING = new int[3];
    private static int sampleCount = 0;
    private static int sampleCursor = 0;

    private RealPingEstimator() {
    }

    public static void reset() {
        activeServerIp = "";
        estimatedPingMs = -1;
        lastProbeStartMs = 0L;
        consecutiveFailures = 0;
        cachedDnsHost = "";
        cachedDnsPort = -1;
        cachedDnsEndpoint = null;
        cachedDnsTimeMs = 0L;
        synchronized (SAMPLE_RING) {
            sampleCount = 0;
            sampleCursor = 0;
        }
    }

    /**
     * Retourne le meilleur ping disponible :
     * - Si tabPing > 1 ms : retourne directement tabPing (vrai ping serveur).
     * - Si tabPing <= 1 ms : lance/réutilise la sonde réseau réelle vers ServerData.serverIP.
     */
    public static int resolveEffectivePing(Minecraft mc, int tabPing) {
        if (tabPing > 1) {
            return tabPing;
        }
        if (mc == null || mc.isSingleplayer()) {
            return -1;
        }
        ServerData data = mc.getCurrentServerData();
        if (data == null || data.serverIP == null) {
            return tabPing;
        }
        String ip = data.serverIP.trim();
        if (ip.isEmpty()) {
            return tabPing;
        }

        if (!ip.equals(activeServerIp)) {
            reset();
            activeServerIp = ip;
            // Valeur initiale immédiate issue de la liste des serveurs avant la première sonde asynchrone
            if (data.pingToServer > 1L && data.pingToServer <= MAX_VALID_PING_MS) {
                recordSample((int) data.pingToServer);
            }
        }

        boolean windowActive = true;
        try {
            if (Display.isCreated()) {
                windowActive = Display.isActive();
            }
        } catch (Throwable ignored) {
        }

        long now = System.currentTimeMillis();
        long backoffMs = consecutiveFailures >= 3 ? 25_000L : PROBE_INTERVAL_MS;
        if (windowActive && now - lastProbeStartMs >= backoffMs && PROBE_IN_FLIGHT.compareAndSet(false, true)) {
            lastProbeStartMs = now;
            final String targetIp = ip;
            PROBE_EXECUTOR.execute(() -> {
                try {
                    ServerAddress addr = ServerAddress.fromString(targetIp);
                    String host = addr != null ? addr.getIP() : targetIp;
                    int port = addr != null ? addr.getPort() : 25565;
                    int measured = measureServerPingMs(host, port, SOCKET_TIMEOUT_MS);
                    if (targetIp.equals(activeServerIp)) {
                        if (measured > 0 && measured <= MAX_VALID_PING_MS) {
                            consecutiveFailures = 0;
                            recordSample(measured);
                        } else {
                            consecutiveFailures++;
                        }
                    }
                } catch (Exception | LinkageError ignored) {
                    if (targetIp.equals(activeServerIp)) {
                        consecutiveFailures++;
                    }
                } finally {
                    PROBE_IN_FLIGHT.set(false);
                }
            });
        }

        int est = estimatedPingMs;
        if (est > 1) {
            return est;
        }
        if (data.pingToServer > 1L && data.pingToServer <= MAX_VALID_PING_MS) {
            return (int) data.pingToServer;
        }
        return tabPing;
    }

    private static void recordSample(int ms) {
        synchronized (SAMPLE_RING) {
            SAMPLE_RING[sampleCursor] = ms;
            sampleCursor = (sampleCursor + 1) % SAMPLE_RING.length;
            if (sampleCount < SAMPLE_RING.length) {
                sampleCount++;
            }
            if (sampleCount == 1) {
                estimatedPingMs = SAMPLE_RING[0];
            } else if (sampleCount == 2) {
                estimatedPingMs = (SAMPLE_RING[0] + SAMPLE_RING[1]) / 2;
            } else {
                int a = SAMPLE_RING[0];
                int b = SAMPLE_RING[1];
                int c = SAMPLE_RING[2];
                // Médiane de 3
                int med = Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
                estimatedPingMs = med;
            }
        }
    }

    private static InetSocketAddress resolveEndpointCached(String host, int port) {
        long now = System.currentTimeMillis();
        InetSocketAddress cached = cachedDnsEndpoint;
        if (cached != null
                && port == cachedDnsPort
                && host.equals(cachedDnsHost)
                && (now - cachedDnsTimeMs) < DNS_CACHE_TTL_MS) {
            return cached;
        }
        InetSocketAddress fresh = new InetSocketAddress(host, port);
        if (!fresh.isUnresolved()) {
            cachedDnsHost = host;
            cachedDnsPort = port;
            cachedDnsEndpoint = fresh;
            cachedDnsTimeMs = now;
        }
        return fresh;
    }

    /**
     * Mesure purement Java 8 du RTT réel via Minecraft 1.8.9 Server List Ping (Handshake -> Status -> Ping/Pong)
     * avec repli automatique sur le RTT de handshake TCP SYN/ACK si le serveur refuse le statut SLP.
     */
    public static int measureServerPingMs(String host, int port, int timeoutMs) {
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) {
            return -1;
        }
        Socket socket = new Socket();
        int tcpConnectMs = -1;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMs);
            InetSocketAddress endpoint = resolveEndpointCached(host, port);
            if (endpoint.isUnresolved()) {
                return -1;
            }

            long tConnStart = System.nanoTime();
            socket.connect(endpoint, timeoutMs);
            long connElapsedMs = Math.max(1L, Math.round((System.nanoTime() - tConnStart) / 1_000_000.0D));
            if (connElapsedMs <= MAX_VALID_PING_MS) {
                tcpConnectMs = (int) connElapsedMs;
            }

            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 256));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1024));

            // 1. Handshake packet (ID 0x00, protocol 47, host, port, nextState = 1 Status)
            ByteArrayOutputStream hsPayload = new ByteArrayOutputStream(64);
            writeVarInt(hsPayload, 0x00);
            writeVarInt(hsPayload, MC_189_PROTOCOL_VERSION);
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(hsPayload, hostBytes.length);
            hsPayload.write(hostBytes);
            hsPayload.write((port >>> 8) & 0xFF);
            hsPayload.write(port & 0xFF);
            writeVarInt(hsPayload, 1);
            sendFramedPacket(out, hsPayload.toByteArray());

            // 2. Status Request packet (ID 0x00, empty)
            sendFramedPacket(out, new byte[]{0x00});
            out.flush();

            // 3. Read Status Response packet (ID 0x00 + JSON string) and drain without allocating up to 512 KB
            int respLen = readVarInt(in);
            if (respLen <= 0 || respLen > 512 * 1024) {
                return tcpConnectMs;
            }
            int firstByte = in.read();
            if (firstByte != 0x00) {
                return tcpConnectMs;
            }
            int remaining = respLen - 1;
            if (remaining > 0) {
                byte[] drain = new byte[Math.min(256, remaining)];
                while (remaining > 0) {
                    int n = in.read(drain, 0, Math.min(drain.length, remaining));
                    if (n < 0) {
                        throw new EOFException("Unexpected EOF while draining status response");
                    }
                    remaining -= n;
                }
            }

            // 4. Ping packet (ID 0x01 + 8-byte payload) -> Pong packet (ID 0x01 + 8-byte payload)
            long payload = System.nanoTime();
            ByteArrayOutputStream pingBuf = new ByteArrayOutputStream(10);
            writeVarInt(pingBuf, 0x01);
            for (int shift = 56; shift >= 0; shift -= 8) {
                pingBuf.write((int) ((payload >>> shift) & 0xFFL));
            }

            long tPingStart = System.nanoTime();
            sendFramedPacket(out, pingBuf.toByteArray());
            out.flush();

            int pongLen = readVarInt(in);
            if (pongLen <= 0 || pongLen > 64) {
                return tcpConnectMs;
            }
            int pongId = readVarInt(in);
            if (pongId != 0x01) {
                return tcpConnectMs;
            }
            in.readLong(); // Echoed payload
            long slpRttMs = Math.max(1L, Math.round((System.nanoTime() - tPingStart) / 1_000_000.0D));

            if (slpRttMs >= 2L && slpRttMs <= MAX_VALID_PING_MS) {
                return (int) slpRttMs;
            }
            return tcpConnectMs > 0 ? tcpConnectMs : (int) Math.min(MAX_VALID_PING_MS, slpRttMs);
        } catch (IOException ignored) {
            return tcpConnectMs;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sendFramedPacket(DataOutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream(5);
        writeVarInt(header, payload.length);
        out.write(header.toByteArray());
        out.write(payload);
    }

    public static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        int numRead = 0;
        while (true) {
            int read = in.read();
            if (read < 0) {
                throw new EOFException("Unexpected EOF in VarInt");
            }
            result |= (read & 0x7F) << (7 * numRead);
            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt too big");
            }
            if ((read & 0x80) == 0) {
                return result;
            }
        }
    }
}
