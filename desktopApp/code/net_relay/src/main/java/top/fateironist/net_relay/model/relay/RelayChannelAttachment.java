package top.fateironist.net_relay.model.relay;

import lombok.Data;
import top.fateironist.net_relay.model.relay.enums.TransportLayerProtocol;

import java.nio.channels.Channel;

@Data
public class RelayChannelAttachment {
    public static final int DEFAULT_UDP_BUFFER_SIZE = 1472;
    public static final int DEFAULT_TCP_BUFFER_SIZE = 1460;

    private TransportLayerProtocol protocol;
    private String agentId;

    private Integer proxiedPort;
    private Integer proxyPort;

    private boolean isClosed;


    public void closeChannel(Channel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception e) {
            }
        }
    }

}
