package top.fateironist.net_relay.model.relay;

import lombok.Data;
import lombok.EqualsAndHashCode;
import top.fateironist.net_relay.model.relay.enums.TransportLayerProtocol;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;

@Data
@EqualsAndHashCode(callSuper = true)
public class UdpRelayChannelAttachment extends RelayChannelAttachment {
    private int localPort;
    private int remotePort;
    private SocketAddress remoteAddress;

    private String channelId;

    private DatagramChannel datagramChannel;
    private SelectionKey selectionKey;

    private ByteBuffer tempBuffer;
    private ByteBuffer inBuffer;
    private ByteBuffer outBuffer;

    private long lastActiveTime = System.currentTimeMillis();

    public Integer extractPort(SocketAddress address) {
        return Integer.valueOf(address.toString().split(":")[1]);
    }

    public static boolean isLocal(SocketAddress address) {
        InetSocketAddress inetSocketAddress = (InetSocketAddress) address;
        return inetSocketAddress.getAddress().isLoopbackAddress();
    }

    public ByteBuffer getInBufferOrCreate() {
        if (inBuffer == null) {
            inBuffer = ByteBuffer.allocate(DEFAULT_UDP_BUFFER_SIZE * 8);
        }
        return inBuffer;
    }

    public ByteBuffer getOutBufferOrCreate() {
        if (outBuffer == null) {
            outBuffer = ByteBuffer.allocate(DEFAULT_UDP_BUFFER_SIZE * 8);
        }
        return outBuffer;
    }

    public UdpRelayChannelAttachment(String agentId, Integer proxiedPort, Integer proxyPort,String remoteHost ,Integer remotePort, String channelId) {
        setProtocol(TransportLayerProtocol.UDP);
        setAgentId(agentId);
        setProxiedPort(proxiedPort);
        setProxyPort(proxyPort);

        this.localPort = proxiedPort;
        this.remotePort = remotePort;
        this.channelId = channelId;

        this.remoteAddress = new InetSocketAddress(remoteHost, remotePort);

        this.tempBuffer = ByteBuffer.allocate(DEFAULT_UDP_BUFFER_SIZE);

        setClosed(false);
    }

    public boolean shouldClose() {
        return isClosed() || System.currentTimeMillis() - lastActiveTime > 1000 * 30;
    }

    public void refresh() {
        lastActiveTime = System.currentTimeMillis();
    }

    public void close() {
        setClosed(true);
        if (this.selectionKey != null) {
            this.selectionKey.cancel();
            closeChannel(datagramChannel);
        }
    }
}
