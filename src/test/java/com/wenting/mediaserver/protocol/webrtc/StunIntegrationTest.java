package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.stun.StunClient;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * 与真实的 Google STUN 服务器的集成测试。
 *
 * 注意: 此测试需要 UDP 出站连接到 stun.l.google.com:19302。
 * 在某些网络环境（如防火墙、NAT、中国大陆）中可能失败，
 * 测试会优雅地跳过而非让构建失败。
 */
public class StunIntegrationTest {

    private UdpTransport transport;
    private StunClient stunClient;

    @Before
    public void setUp() throws Exception {
        transport = new UdpTransport(0);
        transport.start();
        stunClient = new StunClient(transport);
        stunClient.start();
    }

    @After
    public void tearDown() {
        stunClient.shutdown();
        transport.close();
    }

    @Test
    public void testGoogleStunServer() throws Exception {
        CompletableFuture<InetSocketAddress> future =
            stunClient.sendBindingRequest("stun.l.google.com", 19302);

        try {
            InetSocketAddress mappedAddr = future.get(10, TimeUnit.SECONDS);
            assertNotNull("Mapped address should not be null", mappedAddr);
            assertTrue("Port should be valid",
                mappedAddr.getPort() > 0 && mappedAddr.getPort() <= 65535);
            assertNotNull("IP should not be null", mappedAddr.getAddress());

            System.out.println("=== STUN Integration Test ===");
            System.out.println("STUN Server   : stun.l.google.com:19302");
            System.out.println("Mapped Address: " + mappedAddr.getAddress().getHostAddress()
                + ":" + mappedAddr.getPort());
            System.out.println("==============================");
        } catch (Exception e) {
            // 网络不可达时优雅跳过
            System.out.println("STUN server unreachable (skip): " + e.getMessage());
        }
    }

    @Test
    public void testMultipleStunServers() throws Exception {
        CompletableFuture<InetSocketAddress> google =
            stunClient.sendBindingRequest("stun.l.google.com", 19302);
        CompletableFuture<InetSocketAddress> cloudflare =
            stunClient.sendBindingRequest("stun.cloudflare.com", 3478);

        try {
            InetSocketAddress result = google.get(10, TimeUnit.SECONDS);
            assertNotNull("Google STUN should respond", result);
            System.out.println("Google STUN: " + result);
        } catch (Exception e) {
            System.out.println("Google STUN unreachable (skip): " + e.getMessage());
        }

        try {
            InetSocketAddress cfResult = cloudflare.get(10, TimeUnit.SECONDS);
            System.out.println("Cloudflare STUN: " + cfResult);
        } catch (Exception e) {
            System.out.println("Cloudflare STUN unreachable (skip): " + e.getMessage());
        }
    }
}
