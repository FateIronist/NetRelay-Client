package top.fateironist.net_relay.model.common.properties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProxyServerProperties {
    private String host;
    private Integer port;
}
