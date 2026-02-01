package top.fateironist.net_relay.core.communication;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.DialogPane;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import top.fateironist.net_relay.common.TaskScheduler;
import top.fateironist.net_relay.core.relay.RelayManager;
import top.fateironist.net_relay.model.common.enums.WorkingStatusEnum;
import top.fateironist.net_relay.model.common.properties.AgentProperties;
import top.fateironist.net_relay.model.common.properties.ProxyServerProperties;
import top.fateironist.net_relay.model.communication.CommunicationMsg;
import top.fateironist.net_relay.model.communication.CommunicationProtocol;
import top.fateironist.net_relay.model.communication.CommunicationTask;
import top.fateironist.net_relay.model.communication.exception.CommunicationChannelRegisterFailedException;
import top.fateironist.net_relay.model.communication.exception.ProxyRegisterFailedException;
import top.fateironist.net_relay.model.relay.RelayTask;
import top.fateironist.net_relay.model.relay.TcpRelayChannelPairAttachment;
import top.fateironist.net_relay.model.relay.UdpRelayChannelAttachment;
import top.fateironist.net_relay.model.relay.enums.RelayTaskType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class CommunicationManager{

    @Getter
    private String agentId;
    private WorkingStatusEnum workingStatus;

    private final LinkedBlockingQueue<CommunicationTask> taskQueue;
    private Socket communicationSocket;

    @Getter
    private final Map<Integer, Integer> tcpProxy;
    @Getter
    private final Map<Integer, Integer> udpProxy;


    // 控制Bean销毁时阻塞等待直到发送完关闭信号
    private final Lock shutdownLock;
    private final Condition shutdownCondition;

    @Getter
    private final ProxyServerProperties proxyServerProperties;

    private final AgentProperties agentProperties;

    private final RelayManager relayManager;

    public CommunicationManager(ProxyServerProperties proxyServerProperties, AgentProperties agentProperties, RelayManager relayManager) {
        this.proxyServerProperties = proxyServerProperties;
        this.agentProperties = agentProperties;
        this.relayManager = relayManager;

        this.taskQueue = new LinkedBlockingQueue<>();
        this.tcpProxy = new HashMap<>();
        this.udpProxy = new HashMap<>();

        this.shutdownLock = new ReentrantLock();
        this.shutdownCondition = shutdownLock.newCondition();
    }

//    @PostConstruct
    public void init() throws Exception {
        this.start();
        relayManager.start(this);
    }

    public void start() throws CommunicationChannelRegisterFailedException, ProxyRegisterFailedException, IOException {
        this.workingStatus = WorkingStatusEnum.STARTING;

        if (log.isDebugEnabled()) {
            log.debug("CommunicationManager init");
        }

        try {
            // 创建通信Socket并发送注册消息
            Socket socket = new Socket();
            communicationSocket = socket;
            socket.connect(new InetSocketAddress(proxyServerProperties.getHost(), proxyServerProperties.getPort()));
            CommunicationMsg registerCommunicationChannelMsg = new CommunicationMsg();
            registerCommunicationChannelMsg.setRequest(
                    new CommunicationMsg.Method(CommunicationProtocol.BODY_REGISTER_COMMUNICATION_CHANNEL_MSG, null)
            );

            socket.getOutputStream().write(registerCommunicationChannelMsg.buildBytesRequestMessage());
            socket.getOutputStream().flush();

            byte[] bytes = new byte[CommunicationProtocol.MAX_MSG_SIZE];
            int len = socket.getInputStream().read(bytes);
            if (len <= 0) {
                throw new CommunicationChannelRegisterFailedException("Communication channel register failed with connection closed");
            }
            CommunicationMsg registerCommunicationChannelResponseMsg = CommunicationMsg.parse(Arrays.copyOfRange(bytes, 0, len));

            if (registerCommunicationChannelResponseMsg.getOrder().getArgs()[0].equals("0")) {
                throw new CommunicationChannelRegisterFailedException("Communication channel register failed with response 0");
            }

            this.agentId = registerCommunicationChannelResponseMsg.getOrder().getArgs()[1];

            if (log.isDebugEnabled()) {
                log.debug("CommunicationManager register Communication channel success");
            }

            // 注册tcp代理
            if (agentProperties.getProxied().getTcp() != null && agentProperties.getProxied().getTcp().length > 0) {
                CommunicationMsg registerTcpProxyMsg = new CommunicationMsg();
                registerTcpProxyMsg.setAgentId(agentId);
                registerTcpProxyMsg.setRequest(
                        new CommunicationMsg.Method(CommunicationProtocol.BODY_REGISTER_TCP_PROXY_MSG, agentProperties.getProxied().getTcp())
                );

                socket.getOutputStream().write(registerTcpProxyMsg.buildBytesRequestMessage());

                len = socket.getInputStream().read(bytes);
                if (len <= 0) {
                    throw new ProxyRegisterFailedException("Proxy register failed with connection closed");
                }
                CommunicationMsg registerTcpChannelResponseMsg = CommunicationMsg.parse(Arrays.copyOfRange(bytes, 0, len));

                for (int i = 0; i < registerTcpChannelResponseMsg.getOrder().getArgs().length; i++) {
                    if (registerTcpChannelResponseMsg.getOrder().getArgs()[i].equals("0")) {
                        throw new ProxyRegisterFailedException("TCP(port:" + agentProperties.getProxied().getTcp()[i] + ") proxy register failed with response 0");
                    } else {
                        tcpProxy.put(Integer.parseInt(agentProperties.getProxied().getTcp()[i]), Integer.parseInt(registerTcpChannelResponseMsg.getOrder().getArgs()[i]));
                    }
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("CommunicationManager register TCP proxy success");
            }

            // 注册udp代理
            if (agentProperties.getProxied().getUdp() != null && agentProperties.getProxied().getUdp().length > 0) {
                CommunicationMsg registerUdpProxyMsg = new CommunicationMsg();
                registerUdpProxyMsg.setAgentId(agentId);
                registerUdpProxyMsg.setRequest(
                        new CommunicationMsg.Method(CommunicationProtocol.BODY_REGISTER_UDP_PROXY_MSG, agentProperties.getProxied().getUdp())
                );

                socket.getOutputStream().write(registerUdpProxyMsg.buildBytesRequestMessage());

                len = socket.getInputStream().read(bytes);
                if (len <= 0) {
                    throw new ProxyRegisterFailedException("Proxy register failed with connection closed");
                }
                CommunicationMsg registerUdpChannelResponseMsg = CommunicationMsg.parse(Arrays.copyOfRange(bytes, 0, len));

                for (int i = 0; i < registerUdpChannelResponseMsg.getOrder().getArgs().length; i++) {
                    if (registerUdpChannelResponseMsg.getOrder().getArgs()[i].equals("0")) {
                        throw new ProxyRegisterFailedException("UDP(port:" + agentProperties.getProxied().getUdp()[i] + ") proxy register failed with response 0");
                    } else {
                        udpProxy.put(Integer.parseInt(agentProperties.getProxied().getUdp()[i]), Integer.parseInt(registerUdpChannelResponseMsg.getOrder().getArgs()[i]));
                    }
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("CommunicationManager register UDP proxy success");
            }
        } catch (Exception e) {
            log.error("Communication channel register failed; exception:{}", e.getMessage());
            throw e;
        }

        // 通信读线程
        new Thread(() -> {
            int length = 0;
            byte[] buffer = new byte[CommunicationProtocol.MAX_MSG_SIZE];
            while (isRunning()) {
                try {
                    length = communicationSocket.getInputStream().read(buffer);

                    if (log.isDebugEnabled()) {
                        log.debug("Communication channel read length:{}", length);
                    }
                    if (log.isTraceEnabled()) {
                        log.trace("Communication channel read:{}", new String(buffer, 0, length));
                    }

                } catch (IOException e) {
                    logError("Communication channel read error; exception:{}", e.getMessage());
                    broken(e.getMessage());
                    break;
                }

                if (length < 0) {
                    logError("Communication channel closed!");
                    broken("Communication channel closed!");
                    break;
                }

                if (CommunicationMsg.findMsgEnd(buffer) < 0) {
                    log.warn("Receive invalid msg from server");
                    continue;
                }

                CommunicationMsg communicationMsg = CommunicationMsg.parse(Arrays.copyOfRange(buffer, 0, length));

                switch (communicationMsg.getOrder().getName()) {
                    case CommunicationProtocol.BODY_REQUIRE_TCP_RELAY_CHANNEL_MSG:
                        Integer tcpPort = Integer.parseInt(communicationMsg.getOrder().getArgs()[0]);
                        String tempId = communicationMsg.getOrder().getArgs()[1];

                        TcpRelayChannelPairAttachment tcpRelayChannelPairAttachment = new TcpRelayChannelPairAttachment(agentId, tempId, tcpPort, tcpProxy.get(tcpPort));

                        RelayTask tcpRelayTask = new RelayTask(RelayTaskType.REGISTER_RELAY_CHANNEL, tcpRelayChannelPairAttachment);

                        relayManager.submitTask(tcpRelayTask);
                        break;

                    case CommunicationProtocol.BODY_REQUIRE_UDP_RELAY_CHANNEL_MSG:
                        Integer proxiedPort = Integer.parseInt(communicationMsg.getOrder().getArgs()[0]);
                        Integer remotePort = Integer.parseInt(communicationMsg.getOrder().getArgs()[1]);
                        String channelId = communicationMsg.getOrder().getArgs()[2];

                        UdpRelayChannelAttachment udpRelayChannelAttachment = new UdpRelayChannelAttachment(agentId, proxiedPort, udpProxy.get(proxiedPort), proxyServerProperties.getHost(), remotePort, channelId);

                        RelayTask udpRelayTask = new RelayTask(RelayTaskType.REGISTER_RELAY_CHANNEL, udpRelayChannelAttachment);

                        relayManager.submitTask(udpRelayTask);
                }
            }
        }).start();

        // 通信写线程
        new Thread(() -> {
            while (workingStatus.getCode() <= WorkingStatusEnum.WORKING.getCode()) {
                try {
                    CommunicationTask communicationTask = taskQueue.take();
                    communicationSocket.getOutputStream().write(communicationTask.getCommunicationMsg().buildBytesRequestMessage());
                    communicationSocket.getOutputStream().flush();

                    if (log.isTraceEnabled()) {
                        log.trace("Communication channel write:{}", communicationTask.getCommunicationMsg().buildStrRequestMessage());
                    }

                    if (communicationTask.getCommunicationMsg().getRequest().getName().equals(CommunicationProtocol.BODY_SHUTDOWN_MSG)) {
                        shutdownLock.lock();
                        shutdownCondition.signal();
                        shutdownLock.unlock();
                    }
                } catch (InterruptedException e) {
                    log.warn("Communication taskQueue take error; exception:{}", e.getMessage());
                } catch (IOException e) {
                    logError("Communication channel write error; exception:{}", e.getMessage());
                    broken(e.getMessage());
                    break;
                }
            }
        }).start();


        log.info("Communication channel started; AgentId: {}", agentId);
        log.info("+-----------------------------------------------------+");
        log.info(String.format("|%-10s|%-10s|%-20s|%-10s|", "Protocol", "LocalPort", "RemoteAddress", "RemotePort"));
        log.info("+----------+----------+--------------------+----------+");
        for (Map.Entry entry : tcpProxy.entrySet()) {
            log.info(String.format("|%-10s|%-10s|%-20s|%-10s|", "TCP", entry.getKey(), proxyServerProperties.getHost(), entry.getValue()));
        }
        for (Map.Entry entry : udpProxy.entrySet()) {
            log.info(String.format("|%-10s|%-10s|%-20s|%-10s|", "UDP", entry.getKey(), proxyServerProperties.getHost(), entry.getValue()));
        }
        log.info("+-----------------------------------------------------+");

        // 定时发送ping消息
        TaskScheduler.scheduleWithFixedRate(() -> {
            CommunicationMsg pingMsg = new CommunicationMsg();
            pingMsg.setAgentId(agentId);
            pingMsg.setRequest(new CommunicationMsg.Method(CommunicationProtocol.BODY_REGISTER_PING_MSG, null));
            sendMessage(pingMsg);
        }, 0, 1, TimeUnit.MINUTES);

        this.workingStatus = WorkingStatusEnum.WORKING;
    }

    public void sendMessage(CommunicationMsg msg) {
        try {
            taskQueue.put(new CommunicationTask(msg));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void broken(String cause) {
        shutdown();
        Platform.runLater(() -> showAlert("NetRelay broken", "NetRelay broken!!!   Caused by " + cause));
        log.error("NetRelay broken!!!   Caused by {}", cause);
    }

    public void shutdown() {
        workingStatus = WorkingStatusEnum.STOPPING;

        relayManager.shutdown();

        CommunicationMsg shutdownMsg = new CommunicationMsg();
        shutdownMsg.setAgentId(agentId);
        shutdownMsg.setRequest(new CommunicationMsg.Method(CommunicationProtocol.BODY_SHUTDOWN_MSG, null));
        sendMessage(shutdownMsg);

        try {
            shutdownLock.lock();
            shutdownCondition.await(1, TimeUnit.SECONDS);

            communicationSocket.close();
        }catch (Exception e) {
        }finally {
            shutdownLock.unlock();
        }
        workingStatus = WorkingStatusEnum.STOPPED;
    }

    private boolean isRunning() {
        return workingStatus.getCode() <= WorkingStatusEnum.WORKING.getCode();
    }

    private void logError(String msg, Object... objects) {
        if (isRunning()) log.error(msg, objects);
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);

        DialogPane dialogPane = alert.getDialogPane();
        dialogPane.setStyle(
                "-fx-background-color: white;" +
                        "-fx-border-color: #E0E0E0;" +
                        "-fx-border-width: 1;" +
                        "-fx-border-radius: 8;" +
                        "-fx-background-radius: 8;"
        );

        alert.show();
    }
}
