package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.dtls.DtlsHandshake;
import com.wenting.mediaserver.protocol.webrtc.core.dtls.UdpDatagramTransport;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * DTLS handshake loopback test.
 *
 * 启动两个 UdpTransport，通过 UdpDatagramTransport 适配器桥接到
 * BouncyCastle DTLS，执行完整的 DTLS 1.2 握手并验证 SRTP key material 导出。
 */
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
        // 1. 创建服务器端 UdpTransport 适配器
        UdpDatagramTransport serverAdapter = new UdpDatagramTransport(transportB, peerA);

        // 2. 在后台线程启动服务器 DTLS 握手
        AtomicReference<DtlsHandshake> serverRef = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(1);

        Thread serverThread = new Thread(() -> {
            try {
                serverRef.set(new DtlsHandshake(serverAdapter, true));
                serverLatch.countDown();
            } catch (Throwable t) {
                serverError.set(t);
                serverLatch.countDown();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // 等待服务器线程初始化（启动协议监听）
        Thread.sleep(100);

        // 3. 创建客户端适配器并执行握手
        UdpDatagramTransport clientAdapter = new UdpDatagramTransport(transportA, peerB);
        DtlsHandshake client = new DtlsHandshake(clientAdapter, false);

        // 4. 等待服务器完成
        boolean serverDone = serverLatch.await(30, TimeUnit.SECONDS);
        assertTrue("Server handshake should complete within 30 seconds", serverDone);
        assertNull("Server handshake should not throw: " + serverError.get(), serverError.get());
        assertNotNull("Server DtlsHandshake should be created", serverRef.get());

        DtlsHandshake server = serverRef.get();

        // 5. 验证 SRTP key material
        byte[] clientKey = client.getSrtpKeyMaterial();
        byte[] serverKey = server.getSrtpKeyMaterial();

        assertNotNull("Client should have SRTP key material", clientKey);
        assertNotNull("Server should have SRTP key material", serverKey);
        assertEquals("Client key material should be 60 bytes", 60, clientKey.length);
        assertEquals("Server key material should be 60 bytes", 60, serverKey.length);

        // RFC 5764: 双方导出的 key material 应该一致
        assertArrayEquals("Client and server key material should match", clientKey, serverKey);
    }

    @Test(timeout = 30000)
    public void testDtlsTransportCreated() throws Exception {
        // Minimal test: verify DtlsHandshake creates a non-null transport
        UdpDatagramTransport serverAdapter = new UdpDatagramTransport(transportB, peerA);
        AtomicReference<DtlsHandshake> serverRef = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(1);

        Thread serverThread = new Thread(() -> {
            try {
                serverRef.set(new DtlsHandshake(serverAdapter, true));
                serverLatch.countDown();
            } catch (Throwable t) {
                serverError.set(t);
                serverLatch.countDown();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        Thread.sleep(100);

        UdpDatagramTransport clientAdapter = new UdpDatagramTransport(transportA, peerB);
        DtlsHandshake client = new DtlsHandshake(clientAdapter, false);

        serverLatch.await(15, TimeUnit.SECONDS);
        assertNull(serverError.get());

        assertNotNull("Client should have DTLS transport", client.getDtlsTransport());
        assertNotNull("Server should have DTLS transport", serverRef.get().getDtlsTransport());
    }

    @Test(timeout = 30000)
    public void testDtlsHandshakeMultipleTimes() throws Exception {
        // Run the DTLS handshake loopback twice to ensure reusability
        for (int i = 0; i < 2; i++) {
            // Need fresh transports for each handshake
            UdpTransport ta = new UdpTransport(0);
            UdpTransport tb = new UdpTransport(0);
            ta.start();
            tb.start();
            InetSocketAddress aAddr = new InetSocketAddress("127.0.0.1", ta.getLocalAddress().getPort());
            InetSocketAddress bAddr = new InetSocketAddress("127.0.0.1", tb.getLocalAddress().getPort());

            UdpDatagramTransport serverAdapter = new UdpDatagramTransport(tb, aAddr);
            AtomicReference<DtlsHandshake> serverRef = new AtomicReference<>();
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            CountDownLatch serverLatch = new CountDownLatch(1);

            Thread serverThread = new Thread(() -> {
                try {
                    serverRef.set(new DtlsHandshake(serverAdapter, true));
                    serverLatch.countDown();
                } catch (Throwable t) {
                    serverError.set(t);
                    serverLatch.countDown();
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            Thread.sleep(100);

            UdpDatagramTransport clientAdapter = new UdpDatagramTransport(ta, bAddr);
            DtlsHandshake client = new DtlsHandshake(clientAdapter, false);

            serverLatch.await(15, TimeUnit.SECONDS);
            assertNull("Handshake round " + i + " should not throw: " + serverError.get(), serverError.get());

            assertNotNull("Client key material round " + i, client.getSrtpKeyMaterial());
            assertEquals("Client key material should be 60 bytes", 60, client.getSrtpKeyMaterial().length);

            ta.close();
            tb.close();
        }
    }
}
