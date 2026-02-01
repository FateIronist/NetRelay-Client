module top.fateironist.net_relay {
    requires javafx.controls;
    requires javafx.fxml;
    requires lombok;
    requires org.slf4j;
    requires ch.qos.logback.classic;


    opens top.fateironist.net_relay.desktop to javafx.fxml;
    exports top.fateironist.net_relay.desktop to javafx.controls;

}