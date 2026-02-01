package top.fateironist.net_relay.model.relay;

import lombok.Data;
import top.fateironist.net_relay.model.relay.enums.RelayTaskType;

@Data
public class RelayTask {
    private RelayTaskType taskType;

    private RelayChannelAttachment relayChannelAttachment;

    private TcpRelayChannelPairAttachmentWrapper tcpRelayChannelPairAttachmentWrapper;

    public RelayTask(RelayTaskType taskType, RelayChannelAttachment attachment) {
        this.taskType = taskType;
        this.relayChannelAttachment = attachment;
    }

    public RelayTask(RelayTaskType taskType, TcpRelayChannelPairAttachmentWrapper tcpRelayChannelPairAttachmentWrapper) {
        this.taskType = taskType;
        this.tcpRelayChannelPairAttachmentWrapper = tcpRelayChannelPairAttachmentWrapper;
    }
}
