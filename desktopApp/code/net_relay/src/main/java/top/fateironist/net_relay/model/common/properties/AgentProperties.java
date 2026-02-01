package top.fateironist.net_relay.model.common.properties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
public class AgentProperties {
    private Proxy proxied;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Proxy {
        private String[] tcp;
        private String[] udp;
    }

    public AgentProperties(String[] tcp, String[] udp) {
        this.proxied = new Proxy(tcp, udp);
    }
}
