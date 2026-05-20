package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.dtls.DtlsHandshake;
import com.wenting.mediaserver.protocol.webrtc.core.dtls.UdpDatagramTransport;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCrypto;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.security.Security;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DtlsHandshakeTest {

    private UdpTransport transportA;
    private UdpTransport transportB;
    private InetSocketAddress peerA;
    private InetSocketAddress peerB;

    @Before
    public void setUp() throws Exception {
        transportA = new UdpTransport(0);
        transportB = new UdpTransport(0);
        transportA.start();
        transportB.start();
        peerA = new InetSocketAddress("127.0.0.1", transportA.getLocalAddress().getPort());
        peerB = new InetSocketAddress("127.0.0.1", transportB.getLocalAddress().getPort());
    }

    @After
    public void tearDown() {
        transportB.close();
        transportA.close();
    }

    @Test(timeout = 60000)
    public void testDtlsHandshakeLoopback() throws Exception {
        DtlsHandshake.CertCredentials serverCredentials = generateCredentials();
        DtlsHandshake.CertCredentials clientCredentials = generateCredentials();
        String serverFingerprint = DtlsHandshake.computeFingerprint(serverCredentials);
        String clientFingerprint = DtlsHandshake.computeFingerprint(clientCredentials);

        HandshakeResult result = runLoopbackHandshake(
            serverCredentials, clientCredentials, clientFingerprint, serverFingerprint);

        assertNull("Server handshake should not throw: " + result.serverError.get(), result.serverError.get());
        assertNotNull("Server DtlsHandshake should be created", result.serverRef.get());

        byte[] clientKey = result.client.getSrtpKeyMaterial();
        byte[] serverKey = result.serverRef.get().getSrtpKeyMaterial();

        assertNotNull("Client should have SRTP key material", clientKey);
        assertNotNull("Server should have SRTP key material", serverKey);
        assertEquals("Client key material should be 60 bytes", 60, clientKey.length);
        assertEquals("Server key material should be 60 bytes", 60, serverKey.length);
        assertArrayEquals("Client and server key material should match", clientKey, serverKey);
    }

    @Test(timeout = 30000)
    public void testDtlsTransportCreated() throws Exception {
        DtlsHandshake.CertCredentials serverCredentials = generateCredentials();
        DtlsHandshake.CertCredentials clientCredentials = generateCredentials();
        String serverFingerprint = DtlsHandshake.computeFingerprint(serverCredentials);
        String clientFingerprint = DtlsHandshake.computeFingerprint(clientCredentials);

        HandshakeResult result = runLoopbackHandshake(
            serverCredentials, clientCredentials, clientFingerprint, serverFingerprint);

        assertNull(result.serverError.get());
        assertNotNull("Client should have DTLS transport", result.client.getDtlsTransport());
        assertNotNull("Server should have DTLS transport", result.serverRef.get().getDtlsTransport());
    }

    @Test(timeout = 30000)
    public void testDtlsHandshakeMultipleTimes() throws Exception {
        for (int i = 0; i < 2; i++) {
            UdpTransport ta = new UdpTransport(0);
            UdpTransport tb = new UdpTransport(0);
            ta.start();
            tb.start();

            InetSocketAddress aAddr = new InetSocketAddress("127.0.0.1", ta.getLocalAddress().getPort());
            InetSocketAddress bAddr = new InetSocketAddress("127.0.0.1", tb.getLocalAddress().getPort());
            DtlsHandshake.CertCredentials serverCredentials = generateCredentials();
            DtlsHandshake.CertCredentials clientCredentials = generateCredentials();
            String serverFingerprint = DtlsHandshake.computeFingerprint(serverCredentials);
            String clientFingerprint = DtlsHandshake.computeFingerprint(clientCredentials);

            AtomicReference<DtlsHandshake> serverRef = new AtomicReference<>();
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            CountDownLatch serverLatch = new CountDownLatch(1);
            UdpDatagramTransport serverAdapter = new UdpDatagramTransport(tb, aAddr);

            Thread serverThread = new Thread(() -> {
                try {
                    serverRef.set(new DtlsHandshake(serverAdapter, true, serverCredentials, clientFingerprint));
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverLatch.countDown();
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            Thread.sleep(100);

            UdpDatagramTransport clientAdapter = new UdpDatagramTransport(ta, bAddr);
            DtlsHandshake client = new DtlsHandshake(clientAdapter, false, clientCredentials, serverFingerprint);

            assertTrue(serverLatch.await(15, TimeUnit.SECONDS));
            assertNull("Handshake round " + i + " should not throw: " + serverError.get(), serverError.get());
            assertNotNull("Client key material round " + i, client.getSrtpKeyMaterial());
            assertEquals("Client key material should be 60 bytes", 60, client.getSrtpKeyMaterial().length);

            ta.close();
            tb.close();
        }
    }

    @Test(timeout = 30000)
    public void testClientRejectsMismatchedServerFingerprint() throws Exception {
        DtlsHandshake.CertCredentials serverCredentials = generateCredentials();
        DtlsHandshake.CertCredentials clientCredentials = generateCredentials();
        String clientFingerprint = DtlsHandshake.computeFingerprint(clientCredentials);
        String wrongServerFingerprint = mutateFingerprint(DtlsHandshake.computeFingerprint(serverCredentials));

        AtomicReference<DtlsHandshake> serverRef = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(1);
        UdpDatagramTransport serverAdapter = new UdpDatagramTransport(transportB, peerA);

        Thread serverThread = new Thread(() -> {
            try {
                serverRef.set(new DtlsHandshake(serverAdapter, true, serverCredentials, clientFingerprint));
            } catch (Throwable t) {
                serverError.set(t);
            } finally {
                serverLatch.countDown();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        Thread.sleep(100);

        UdpDatagramTransport clientAdapter = new UdpDatagramTransport(transportA, peerB);
        try {
            new DtlsHandshake(clientAdapter, false, clientCredentials, wrongServerFingerprint);
            fail("Client handshake should fail on mismatched server fingerprint");
        } catch (Exception expected) {
            assertTrue(messageContains(expected, "fingerprint"));
        }

        assertTrue(serverLatch.await(15, TimeUnit.SECONDS));
        assertNotNull("Server should observe the failed handshake", serverError.get());
    }

    @Test(timeout = 30000)
    public void testServerRejectsMismatchedClientFingerprint() throws Exception {
        DtlsHandshake.CertCredentials serverCredentials = generateCredentials();
        DtlsHandshake.CertCredentials clientCredentials = generateCredentials();
        String serverFingerprint = DtlsHandshake.computeFingerprint(serverCredentials);
        String wrongClientFingerprint = mutateFingerprint(DtlsHandshake.computeFingerprint(clientCredentials));

        AtomicReference<DtlsHandshake> serverRef = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(1);
        UdpDatagramTransport serverAdapter = new UdpDatagramTransport(transportB, peerA);

        Thread serverThread = new Thread(() -> {
            try {
                serverRef.set(new DtlsHandshake(serverAdapter, true, serverCredentials, wrongClientFingerprint));
            } catch (Throwable t) {
                serverError.set(t);
            } finally {
                serverLatch.countDown();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        Thread.sleep(100);

        UdpDatagramTransport clientAdapter = new UdpDatagramTransport(transportA, peerB);
        try {
            new DtlsHandshake(clientAdapter, false, clientCredentials, serverFingerprint);
            fail("Client handshake should fail when server rejects client fingerprint");
        } catch (Exception expected) {
            assertTrue(serverLatch.await(15, TimeUnit.SECONDS));
            assertNotNull("Server should reject the client fingerprint", serverError.get());
            assertTrue(messageContains(serverError.get(), "fingerprint")
                || messageContains(expected, "alert"));
        }
    }

    private HandshakeResult runLoopbackHandshake(DtlsHandshake.CertCredentials serverCredentials,
                                                 DtlsHandshake.CertCredentials clientCredentials,
                                                 String expectedClientFingerprint,
                                                 String expectedServerFingerprint) throws Exception {
        UdpDatagramTransport serverAdapter = new UdpDatagramTransport(transportB, peerA);
        AtomicReference<DtlsHandshake> serverRef = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(1);

        Thread serverThread = new Thread(() -> {
            try {
                serverRef.set(new DtlsHandshake(serverAdapter, true,
                    serverCredentials, expectedClientFingerprint));
            } catch (Throwable t) {
                serverError.set(t);
            } finally {
                serverLatch.countDown();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        Thread.sleep(100);

        UdpDatagramTransport clientAdapter = new UdpDatagramTransport(transportA, peerB);
        DtlsHandshake client = new DtlsHandshake(clientAdapter, false,
            clientCredentials, expectedServerFingerprint);

        assertTrue("Server handshake should complete within 30 seconds",
            serverLatch.await(30, TimeUnit.SECONDS));
        return new HandshakeResult(client, serverRef, serverError);
    }

    private static DtlsHandshake.CertCredentials generateCredentials() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        JcaTlsCrypto crypto = new JcaTlsCryptoProvider()
            .setProvider("BC")
            .create(new SecureRandom());
        return DtlsHandshake.generateSelfSignedCert(crypto);
    }

    private static String mutateFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return fingerprint;
        }
        char first = fingerprint.charAt(0);
        char replacement = first == 'A' ? 'B' : 'A';
        return replacement + fingerprint.substring(1);
    }

    private static boolean messageContains(Throwable throwable, String token) {
        String lowerToken = token.toLowerCase();
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains(lowerToken)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class HandshakeResult {
        private final DtlsHandshake client;
        private final AtomicReference<DtlsHandshake> serverRef;
        private final AtomicReference<Throwable> serverError;

        private HandshakeResult(DtlsHandshake client,
                                AtomicReference<DtlsHandshake> serverRef,
                                AtomicReference<Throwable> serverError) {
            this.client = client;
            this.serverRef = serverRef;
            this.serverError = serverError;
        }
    }
}
