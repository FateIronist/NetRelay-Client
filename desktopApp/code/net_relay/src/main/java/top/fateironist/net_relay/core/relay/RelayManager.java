package top.fateironist.net_relay.core.relay;

import lombok.extern.slf4j.Slf4j;
import top.fateironist.net_relay.common.AsyncIoThreadPool;
import top.fateironist.net_relay.common.TaskScheduler;
import top.fateironist.net_relay.core.communication.CommunicationManager;
import top.fateironist.net_relay.model.common.enums.WorkingStatusEnum;
import top.fateironist.net_relay.model.common.properties.ProxyServerProperties;
import top.fateironist.net_relay.model.communication.CommunicationMsg;
import top.fateironist.net_relay.model.communication.CommunicationProtocol;
import top.fateironist.net_relay.model.relay.*;
import top.fateironist.net_relay.model.relay.enums.RelayTaskType;
import top.fateironist.net_relay.model.relay.enums.TransportLayerProtocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RelayManager {
    private Selector selector;
    private final ConcurrentLinkedQueue<RelayTask> taskQueue;

    // 注册表
    private final Map<String, RelayChannelAttachment> relayChannelAttachments;

    private WorkingStatusEnum workingStatus;

    // 空轮询检测
    private static final int THRESHOLD = 512;
    private static final long THRESHOLD_TIME = 10000; // ns
    private long lastPollTime = 0;
    private int emptyPollCount = 0;


    private final ProxyServerProperties proxyServerProperties;

    private CommunicationManager communicationManager;

    public RelayManager(ProxyServerProperties proxyServerProperties) {
        this.proxyServerProperties = proxyServerProperties;

        this.taskQueue = new ConcurrentLinkedQueue<>();
        this.workingStatus = WorkingStatusEnum.STARTING;
        this.relayChannelAttachments = new HashMap<>();
    }

    public void start(CommunicationManager communicationManager) {
        this.communicationManager = communicationManager;
        try {
            workingStatus = WorkingStatusEnum.STARTING;
            this.selector = Selector.open();
        } catch (IOException e) {
            log.error("RelayManager init error; exception:{}", e.getMessage());
            throw new RuntimeException(e);
        }

        // select多路复用逻辑
        Thread taskThread = new Thread(() -> {
            while(isRunning()) {
                try {
                    selector.select();
                } catch (IOException e) {
                    logError("RelayManager select error; exception:{}", e.getMessage());
                    selector.selectedKeys().forEach(key -> {
                        closeChannel(key.channel());
                    });
                    break;
                }

                // 空轮询检测 JDK 8
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                try {
                    emptyPollDetection(selectionKeys);
                } catch (IOException e) {
                    logError("RelayManager emptyPollDetection error; exception:{}", e.getMessage());
                    throw new RuntimeException(e);
                }

                Iterator<SelectionKey> iterator = selectionKeys.iterator();

                if (log.isDebugEnabled()) {
                    log.debug("RelayManager select; length:{}", selectionKeys.size());
                }

                try {
                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();
                        iterator.remove();
                        if (key.isValid() && key.isReadable()) {
                            processReadable(key);
                        }
                        if (key.isValid() && key.isWritable()) {
                            try {
                                processWritable(key);
                            } catch (Throwable e) {
                                key.interestOps(SelectionKey.OP_READ);
                            }

                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                RelayTask relayTask = null;
                while((relayTask = taskQueue.poll()) != null) {
                    processTask(relayTask);
                }
            }
        });

        taskThread.setUncaughtExceptionHandler((t, e) -> {
            if (e instanceof ClosedSelectorException) {
                if (workingStatus.getCode() <= WorkingStatusEnum.WORKING.getCode()) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new RuntimeException(e);
            }
        });

        taskThread.setDaemon(true);
        taskThread.setName("RelayManager");
        taskThread.start();

        workingStatus = WorkingStatusEnum.WORKING;

        // 定期清理空轮询数量
        TaskScheduler.scheduleWithFixedRate(() -> {
            emptyPollCount = 0;
        }, 1, 1, TimeUnit.MINUTES);

        // 定时清理udp的通道，主要因为udp为无状态
        TaskScheduler.scheduleWithFixedRate(() -> {
            List<String> keys = new ArrayList<>();
            relayChannelAttachments.forEach((key, value) -> {keys.add(key);});
            keys.forEach(key -> {
                RelayChannelAttachment attachment = relayChannelAttachments.get(key);
                if (attachment instanceof TcpRelayChannelPairAttachment) {
                    if (attachment.isClosed()) {
                        closeRelayChannel(attachment);
                    }
                }else if (attachment instanceof UdpRelayChannelAttachment) {
                    if (((UdpRelayChannelAttachment) attachment).shouldClose()) {
                        closeRelayChannel(attachment);
                    }
                }
            });
        }, 1, 1, TimeUnit.SECONDS);

    }

    // 处理读事件
    private void processReadable (SelectionKey key) {
        Channel channel = key.channel();

        if (channel instanceof SocketChannel) {
            TcpRelayChannelPairAttachmentWrapper wrapper = (TcpRelayChannelPairAttachmentWrapper) key.attachment();
            TcpRelayChannelPairAttachment attachment = wrapper.getAttachment();

            // 关闭逻辑
            if (attachment.isClosed() || !key.isValid()) {
                if (!closeRelayChannel(attachment.getTempId())) attachment.close();
                return;
            }

            ByteBuffer buffer = null;
            if (wrapper.isIn()) {
                buffer = attachment.getInBuffer();
            } else {
                buffer = attachment.getOutBuffer();
            }

            boolean isInitial = buffer.position() == 0;

            int len = 0;
            try {
                len = ((SocketChannel) channel).read(buffer);

                if (log.isDebugEnabled()) {
                    log.debug("TcpRelayChannel(proxiedPort:{}) read length:{}", attachment.getProxiedPort(), len);
                }

                if (log.isTraceEnabled() && len > 0) {
                    ByteBuffer slice = buffer.duplicate();
                    slice.flip();
                    byte[] bytes = new byte[slice.remaining()];
                    slice.get(bytes);
                    System.out.println("-------------------RelayTcpRead------------------");
                    log.trace("TcpRelayChannel(proxiedPort:{}) read: \n{}", attachment.getProxiedPort(), new String(bytes, StandardCharsets.UTF_8));
                    System.out.println("----------------------------------------------");
                }

            } catch (IOException e) {
                if (!attachment.isClosed()) log.warn("TcpRelayChannel(agentId:{}, localPort:{}) read error; exception:{}", attachment.getAgentId(), attachment.getProxiedPort(), e.getMessage());
                // 关闭逻辑
                if (!closeRelayChannel(attachment.getTempId())) attachment.close();
                return;
            }

            if (len == -1) {
                // 关闭逻辑
                if (!closeRelayChannel(attachment.getTempId())) attachment.close();
            }else if(len == 0) {
                // 判断是否要继续写
//                if (wrapper.isIn()) {
//                    attachment.setInBufferWriteContinue(true);
//                } else {
//                    attachment.setOutBufferWriteContinue(true);
//                }
            } else if (len > 0) {
//                if (wrapper.isIn()) {
//                    attachment.setInBufferWriteContinue(false);
//                } else {
//                    attachment.setOutBufferWriteContinue(false);
//                }

                SocketChannel writeChannel = null;
                try {
                    if (wrapper.isIn()) {
                        if (!buffer.hasRemaining()) {
                            writeChannel = attachment.getTcpResponseChannel();
                            writeChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, new TcpRelayChannelPairAttachmentWrapper(false, attachment));
                        } else if (isInitial) {
                            TaskScheduler.schedule(() -> {
                                if (attachment.isWriteInTimeout()) {
                                    RelayTask tcpRelayTask = new RelayTask(RelayTaskType.TCP_INTERSET_EVENT, wrapper);
                                    submitTask(tcpRelayTask);
                                }
                            }, TcpRelayChannelPairAttachment.MTU_AGGREGATION_WAIT_TIME, TimeUnit.MILLISECONDS);
                        }

                    } else {
                        if (!buffer.hasRemaining()) {
                            writeChannel = attachment.getTcpRelayChannel();
                            writeChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, new TcpRelayChannelPairAttachmentWrapper(true, attachment));
                        } else if (isInitial) {
                            TaskScheduler.schedule(() -> {
                                if (attachment.isWriteOutTimeout()) {
                                    RelayTask tcpRelayTask = new RelayTask(RelayTaskType.TCP_INTERSET_EVENT, wrapper);
                                    submitTask(tcpRelayTask);
                                }
                            }, TcpRelayChannelPairAttachment.MTU_AGGREGATION_WAIT_TIME, TimeUnit.MILLISECONDS);
                        }
                    }
                } catch (ClosedChannelException e) {
                    log.warn("SocketChannel(agentId:{}, localPort:{}) register writing error; exception:{}", attachment.getAgentId(), attachment.getProxyPort(), e.getMessage());
                    return;
                }
            }

        } else if (channel instanceof DatagramChannel) {
            DatagramChannel datagramChannel = (DatagramChannel) channel;
            UdpRelayChannelAttachment attachment = (UdpRelayChannelAttachment) key.attachment();
            if (attachment.shouldClose() || !key.isValid()) {
                if (!closeRelayChannel(attachment.getChannelId())) closeRelayChannel(attachment);
                return;
            }

            ByteBuffer buffer = attachment.getTempBuffer();

            // 接收
            SocketAddress address = null;
            try {
                attachment.refresh();
                address = datagramChannel.receive(buffer);
            } catch (IOException e) {
                if (!attachment.shouldClose()) log.warn("UdpRelayChannel(localPort:{}) read error; exception:{}", attachment.getAgentId(), attachment.getProxiedPort(), e.getMessage());
                return;
            }

            if (log.isDebugEnabled()) {
                log.debug("UdpRelayChannel(proxiedPort:{},ip:{}) read length:{}", attachment.getProxiedPort(), address.toString(), buffer.remaining());
            }

            if (log.isTraceEnabled() && buffer.remaining() > 0) {
                ByteBuffer slice = buffer.duplicate();
                slice.flip();
                byte[] bytes = new byte[slice.remaining()];
                slice.get(bytes);
                System.out.println("-------------------RelayUdpRead------------------");
                log.trace("UdpRelayChannel(proxiedPort:{},ip:{}) read: \ncontent:{}", attachment.getProxiedPort(), address.toString(), new String(bytes, StandardCharsets.UTF_8));
                System.out.println("-------------------------------------------------");
            }

            buffer.flip();

            // 由于Udp设计本身就是即写即发，这里直接发送
            Integer port = attachment.extractPort(address);
            ByteBuffer targetBuffer = null;

            if (address.toString().equals(attachment.getRemoteAddress().toString())) {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("UdpRelayChannel(proxiedPort:{}) write length:{}", attachment.getProxiedPort(), buffer.remaining());
                    }

                    if (log.isTraceEnabled()) {
                        ByteBuffer duplicate = buffer.duplicate();
                        byte[] bytes = new byte[duplicate.remaining()];
                        duplicate.get(bytes);
                        System.out.println("-------------------RelayUdpWrite------------------");
                        log.trace("UdpRelayChannel(proxiedPort:{},ip:{}) write: \ncontent:{}", attachment.getProxiedPort(), new InetSocketAddress("localhost", attachment.getLocalPort()), new String(bytes, StandardCharsets.UTF_8));
                        System.out.println("--------------------------------------------------");
                    }

                    attachment.refresh();
                    datagramChannel.send(buffer, new InetSocketAddress("localhost", attachment.getLocalPort()));
                } catch (IOException e) {
                    targetBuffer = attachment.getInBufferOrCreate();
                } catch (Exception e) {
                    if (!attachment.shouldClose()) log.warn("UdpRelayChannel(proxiedPort:{},ip:{}) write error; exception:{}", attachment.getProxiedPort(), "/127.0.0.1:" + attachment.getLocalPort(), e.getMessage());
                    attachment.close();
                    return;
                }
            } else if (attachment.isLocal(address) && port == attachment.getLocalPort()) {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("UdpRelayChannel(proxiedPort:{}) write length:{}", attachment.getProxiedPort(), buffer.remaining());
                    }

                    if (log.isTraceEnabled()) {
                        ByteBuffer duplicate = buffer.duplicate();
                        byte[] bytes = new byte[duplicate.remaining()];
                        duplicate.get(bytes);
                        System.out.println("-------------------RelayUdpWrite------------------");
                        log.trace("UdpRelayChannel(proxiedPort:{},ip:{}) write: \ncontent:{}", attachment.getProxiedPort(), attachment.getRemoteAddress(), new String(bytes, StandardCharsets.UTF_8));
                        System.out.println("--------------------------------------------------");
                    }

                    attachment.refresh();
                    datagramChannel.send(buffer, attachment.getRemoteAddress());
                } catch (IOException e) {
                    targetBuffer = attachment.getOutBufferOrCreate();
                } catch (Exception e) {
                    if (!attachment.shouldClose()) log.warn("UdpRelayChannel(proxiedPort:{},ip:{}) write error; exception:{}", attachment.getProxiedPort(), new InetSocketAddress(proxyServerProperties.getHost(), attachment.getRemotePort()).toString(), e.getMessage());
                    attachment.close();
                    return;
                }
            }

            // 极端情况下系统缓冲区满，才注册写事件
            if (targetBuffer != null) {
                if (targetBuffer.remaining() >= buffer.remaining()) {
                    targetBuffer.put(buffer);
                }

                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }

            buffer.clear();
        } else {
        }
    }

    // 处理写事件
    private void processWritable (SelectionKey key) {
        Channel channel = key.channel();

        if (channel instanceof SocketChannel) {
            TcpRelayChannelPairAttachmentWrapper wrapper = (TcpRelayChannelPairAttachmentWrapper) key.attachment();
            TcpRelayChannelPairAttachment attachment = wrapper.getAttachment();
            // 关闭逻辑
            if (attachment.isClosed() || !key.isValid()) {
                if (!closeRelayChannel(attachment.getTempId())) attachment.close();
                return;
            }

            ByteBuffer buffer = null;
            if (wrapper.isIn()) {
                buffer = attachment.getOutBuffer();
                attachment.setOutBufferLastWriteTime(System.currentTimeMillis());
            } else {
                buffer = attachment.getInBuffer();
                attachment.setInBufferLastWriteTime(System.currentTimeMillis());
            }

            buffer.flip();
            try {
                if (log.isDebugEnabled()) {
                    log.debug("TcpRelayChannel(proxiedPort:{}) write length:{}", attachment.getProxiedPort(), buffer.remaining());
                }

                if (log.isTraceEnabled()) {
                    ByteBuffer slice = buffer.slice();
                    byte[] bytes = new byte[slice.remaining()];
                    slice.get(bytes);
                    System.out.println("-------------------RelayTcpWrite------------------");
                    log.trace("TcpRelayChannel(proxiedPort:{}) write: \n{}", attachment.getProxiedPort(), new String(bytes, StandardCharsets.UTF_8));
                    System.out.println("--------------------------------------------------");
                }

                ((SocketChannel) channel).write(buffer);

            } catch (IOException e) {
                if (!attachment.isClosed()) {
                    log.warn("TcpRelayChannel(agentId:{}, localPort:{}) write error; exception:{}", attachment.getAgentId(), attachment.getProxiedPort(), e.getMessage());
                }
                // 关闭逻辑
                if (!closeRelayChannel(attachment.getTempId())) attachment.close();
                return;
            }

            if (!buffer.hasRemaining()) {
                buffer.clear();
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
//                if (!((wrapper.isIn() && attachment.isOutBufferWriteContinue()) || (!wrapper.isIn() && attachment.isInBufferWriteContinue()))) {
//                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
//                }
                return;
            }

            buffer.flip();
        } else if (channel instanceof DatagramChannel) {
            DatagramChannel datagramChannel = (DatagramChannel) channel;
            UdpRelayChannelAttachment attachment = (UdpRelayChannelAttachment) key.attachment();

            if (attachment.shouldClose() || !key.isValid()) {
                if (!closeRelayChannel(attachment.getChannelId())) attachment.close();
                return;
            }

            // 这里由于写时，同时存在两个方向，因此要进行区分，所以就需要两份日志 TWT
            ByteBuffer buffer = null;
            if (attachment.getInBuffer() != null) {
                buffer = attachment.getInBuffer();

                if (log.isDebugEnabled()) {
                    log.debug("UdpRelayChannel(proxiedPort:{}) write length:{}", attachment.getProxiedPort(), buffer.remaining());
                }

                if (log.isTraceEnabled()) {
                    ByteBuffer duplicate = buffer.duplicate();
                    byte[] bytes = new byte[duplicate.remaining()];
                    duplicate.get(bytes);
                    System.out.println("-------------------RelayUdpWrite------------------");
                    log.trace("UdpRelayChannel(proxiedPort:{},ip:{}) write: \ncontent:{}", attachment.getProxiedPort(), new InetSocketAddress("localhost", attachment.getLocalPort()), new String(bytes, StandardCharsets.UTF_8));
                    System.out.println("--------------------------------------------------");
                }
            }

            if (attachment.getOutBuffer() != null) {
                buffer = attachment.getOutBuffer();

                if (log.isDebugEnabled()) {
                    log.debug("UdpRelayChannel(proxiedPort:{}) write length:{}", attachment.getProxiedPort(), buffer.remaining());
                }

                if (log.isTraceEnabled()) {
                    ByteBuffer duplicate = buffer.duplicate();
                    byte[] bytes = new byte[duplicate.remaining()];
                    duplicate.get(bytes);
                    System.out.println("-------------------RelayUdpWrite------------------");
                    log.trace("UdpRelayChannel(proxiedPort:{},ip:{}) write: \ncontent:{}", attachment.getProxiedPort(), new InetSocketAddress(proxyServerProperties.getHost(), attachment.getRemotePort()), new String(bytes, StandardCharsets.UTF_8));
                    System.out.println("--------------------------------------------------");
                }

            }

            ByteBuffer inBuffer = attachment.getInBuffer();
            ByteBuffer outBuffer = attachment.getOutBuffer();

            inBuffer.flip();
            outBuffer.flip();

            try {
                if (inBuffer.hasRemaining()) {
                    attachment.refresh();
                    datagramChannel.send(buffer, new InetSocketAddress("localhost", attachment.getLocalPort()));
                }
                if (outBuffer.hasRemaining()) {
                    attachment.refresh();
                    datagramChannel.send(buffer, attachment.getRemoteAddress());
                }
            } catch (IOException e) {

            } catch (Exception e) {
                if (!attachment.shouldClose()) log.warn("UdpRelayChannel(proxiedPort:{},ip:{}) write error; exception:{}", attachment.getAgentId(), attachment.getProxiedPort(), "双向", e.getMessage());
                attachment.close();
                return;
            }

            if (!inBuffer.hasRemaining() && !outBuffer.hasRemaining()) {
                attachment.setInBuffer(null);
                attachment.setOutBuffer(null);
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            } else {
                inBuffer.flip();
                outBuffer.flip();
            }

        } else {
            // 关闭逻辑
        }
    }

    private void processTask(RelayTask relayTask) {

        if (log.isDebugEnabled()) {
            if (relayTask.getRelayChannelAttachment() != null) {
                log.debug("RelayManager processTask; taskType:{},protocol:{}", relayTask.getTaskType(), relayTask.getRelayChannelAttachment().getProtocol());
            } else {
                log.debug("RelayManager processTask; taskType:{},protocol:{}", relayTask.getTaskType(), TransportLayerProtocol.TCP);
            }
        }

        switch (relayTask.getTaskType()) {
            case REGISTER_RELAY_CHANNEL:
                RelayChannelAttachment relayChannelPairAttachment = relayTask.getRelayChannelAttachment();
                switch (relayChannelPairAttachment.getProtocol()) {
                    case TCP:
                        AsyncIoThreadPool.executeWithTimeoutIgnoreException(() -> {
                            TcpRelayChannelPairAttachment tcpRelayChannelPairAttachment = (TcpRelayChannelPairAttachment) relayChannelPairAttachment;
                            try {
                                SocketChannel resChannel = SocketChannel.open();

                                resChannel.socket().setTcpNoDelay(true);
                                resChannel.connect(new InetSocketAddress("127.0.0.1", tcpRelayChannelPairAttachment.getProxiedPort()));

                                resChannel.configureBlocking(false);
                                tcpRelayChannelPairAttachment.setResponseChannel(resChannel);


                                SocketChannel relayChannel = SocketChannel.open();

                                relayChannel.socket().setTcpNoDelay(true);
                                relayChannel.connect(new InetSocketAddress(proxyServerProperties.getHost(), proxyServerProperties.getPort()));

                                relayChannel.configureBlocking(false);
                                tcpRelayChannelPairAttachment.setRelayChannel(relayChannel);

                                RelayTask task = new RelayTask(RelayTaskType.REGISTER_RELAY_CHANNEL_FORMALLY, tcpRelayChannelPairAttachment);
                                this.submitTask(task);
                            } catch (IOException e) {
                                tcpRelayChannelPairAttachment.close();
                                if (!tcpRelayChannelPairAttachment.isClosed()) log.warn("SocketChannel(agentId:{}, localPort:{}) register relay channel error; exception:{}", tcpRelayChannelPairAttachment.getAgentId(), tcpRelayChannelPairAttachment.getProxiedPort(), e.getMessage());
                                return;
                            }
                        }, 1, TimeUnit.MINUTES, (e) -> {});
                        break;
                    case UDP:
                        UdpRelayChannelAttachment udpRelayChannelAttachment = (UdpRelayChannelAttachment) relayChannelPairAttachment;

                        try {
                            DatagramChannel datagramChannel = DatagramChannel.open();
                            datagramChannel.configureBlocking(true);
                            // fixme
//                            datagramChannel.bind(new InetSocketAddress( 9878));
                            datagramChannel.bind(new InetSocketAddress(0));

                            AsyncIoThreadPool.executeWithTimeoutIgnoreException(() -> {
                                try {
                                    CommunicationMsg penetrationMsg = new CommunicationMsg();
                                    penetrationMsg.setAgentId(udpRelayChannelAttachment.getAgentId());
                                    penetrationMsg.setRequest(new CommunicationMsg.Method(CommunicationProtocol.BODY_UDP_PENETRATION_MEG, null));

                                    ByteBuffer buffer = ByteBuffer.allocateDirect(penetrationMsg.buildBytesRequestMessage().length);
                                    byte[] reqBytes = penetrationMsg.buildBytesRequestMessage();

                                    buffer.put(reqBytes);
                                    buffer.flip();
                                    datagramChannel.send(buffer, new InetSocketAddress(proxyServerProperties.getHost(), udpRelayChannelAttachment.getRemotePort()));

                                    buffer.clear();
                                    datagramChannel.receive(buffer);
                                    buffer.flip();
                                    byte[] bytes = new byte[buffer.remaining()];
                                    buffer.get(bytes);
                                    CommunicationMsg resMsg = CommunicationMsg.parse(bytes);

                                    if (resMsg.getOrder().getName().equals(CommunicationProtocol.BODY_UDP_PENETRATION_RESPONSE_MSG)) {
                                        udpRelayChannelAttachment.setDatagramChannel(datagramChannel);
                                        RelayTask task = new RelayTask(RelayTaskType.REGISTER_RELAY_CHANNEL_FORMALLY, udpRelayChannelAttachment);
                                        this.submitTask(task);
                                    } else {
                                        closeRelayChannel(udpRelayChannelAttachment);
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }, 1, TimeUnit.MINUTES, (e) -> {
                                closeRelayChannel(udpRelayChannelAttachment);
                                log.warn("DatagramChannel(agentId:{}, localPort:{}) register relay channel error; exception:{}", udpRelayChannelAttachment.getAgentId(), udpRelayChannelAttachment.getProxiedPort(), e.getMessage());
                            });

                        } catch (IOException e) {
                            closeRelayChannel(udpRelayChannelAttachment);
                            if (!udpRelayChannelAttachment.shouldClose()) log.warn("DatagramChannel(agentId:{}, localPort:{}) register relay channel error; exception:{}", udpRelayChannelAttachment.getAgentId(), udpRelayChannelAttachment.getProxiedPort(), e.getMessage());
                            return;
                        }
                        break;
                }
                break;
            case REGISTER_RELAY_CHANNEL_FORMALLY:
                RelayChannelAttachment relayChannelAttachment = relayTask.getRelayChannelAttachment();
                switch (relayChannelAttachment.getProtocol()) {
                    case TCP:
                        TcpRelayChannelPairAttachment tcpRelayChannelAttachment = (TcpRelayChannelPairAttachment) relayChannelAttachment;
                        try {
                            tcpRelayChannelAttachment.setResponseChannelSelectionKey(tcpRelayChannelAttachment.getResponseChannel().register(selector, SelectionKey.OP_READ, new TcpRelayChannelPairAttachmentWrapper(false, tcpRelayChannelAttachment)));

                            tcpRelayChannelAttachment.setRelayChannelSelectionKey(tcpRelayChannelAttachment.getTcpRelayChannel().register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, new TcpRelayChannelPairAttachmentWrapper(true, tcpRelayChannelAttachment)));
                            relayChannelAttachments.put(tcpRelayChannelAttachment.getTempId(), tcpRelayChannelAttachment);

                            CommunicationMsg msg = new CommunicationMsg();
                            msg.setAgentId(tcpRelayChannelAttachment.getAgentId());
                            msg.setRequest(new CommunicationMsg.Method(CommunicationProtocol.BODY_REQUIRE_TCP_RELAY_CHANNEL_RESPONSE_MSG, new String[]{tcpRelayChannelAttachment.getProxiedPort().toString(), tcpRelayChannelAttachment.getTempId()}));
                            tcpRelayChannelAttachment.getOutBuffer().put(msg.buildBytesRequestMessage());
                        } catch (IOException e) {
                            tcpRelayChannelAttachment.close();
                            if (!tcpRelayChannelAttachment.isClosed()) log.warn("SocketChannel(agentId:{}, localPort:{}) register relay channel formally error; exception:{}", tcpRelayChannelAttachment.getAgentId(), tcpRelayChannelAttachment.getProxiedPort(), e.getMessage());
                            return;
                        }
                        break;
                    case UDP:
                        UdpRelayChannelAttachment udpRelayChannelAttachment = (UdpRelayChannelAttachment) relayChannelAttachment;
                        try {
                            DatagramChannel datagramChannel = udpRelayChannelAttachment.getDatagramChannel();

                            datagramChannel.configureBlocking(false);
                            udpRelayChannelAttachment.setSelectionKey(datagramChannel.register(selector, SelectionKey.OP_READ, udpRelayChannelAttachment));

                            CommunicationMsg communicationMsg = new CommunicationMsg();
                            communicationMsg.setAgentId(udpRelayChannelAttachment.getAgentId());
                            communicationMsg.setRequest(new CommunicationMsg.Method(CommunicationProtocol.BODY_REQUIRE_UDP_RELAY_CHANNEL_RESPONSE_MSG, new String[]{udpRelayChannelAttachment.getProxiedPort().toString(), udpRelayChannelAttachment.getChannelId()}));

                            communicationManager.sendMessage(communicationMsg);
                            relayChannelAttachments.put(udpRelayChannelAttachment.getChannelId(), udpRelayChannelAttachment);
                        } catch (IOException e) {
                            udpRelayChannelAttachment.close();
                            if (!udpRelayChannelAttachment.isClosed()) log.warn("DatagramChannel(agentId:{}, localPort:{}) register relay channel formally error; exception:{}", udpRelayChannelAttachment.getAgentId(), udpRelayChannelAttachment.getProxiedPort(), e.getMessage());
                            return;
                        }
                        break;
                }
                break;
            case TCP_INTERSET_EVENT:
                TcpRelayChannelPairAttachmentWrapper wrapper = relayTask.getTcpRelayChannelPairAttachmentWrapper();
                TcpRelayChannelPairAttachment attachment = wrapper.getAttachment();
                if (wrapper.isIn() && !attachment.isClosed()) {
                    SocketChannel writeChannel = attachment.getTcpResponseChannel();
                    try {
                        writeChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, new TcpRelayChannelPairAttachmentWrapper(false, attachment));
                    } catch (ClosedChannelException e) {
                        log.warn("SocketChannel(localPort:{}) register relay channel  write event error; exception:{}", attachment.getProxiedPort(), e.getMessage());
                    }
                } else if (!wrapper.isIn() && !attachment.isClosed()){
                    SocketChannel writeChannel = attachment.getTcpRelayChannel();
                    try {
                        writeChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, new TcpRelayChannelPairAttachmentWrapper(true, attachment));
                    } catch (ClosedChannelException e) {
                        log.warn("SocketChannel(localPort:{}) register relay channel  write event error; exception:{}", attachment.getProxiedPort(), e.getMessage());
                    }
                }

        }
    }

    private void emptyPollDetection(Set<SelectionKey> selectionKeys) throws IOException {
        lastPollTime = System.nanoTime();
        if (selectionKeys.isEmpty()) {
            if (System.nanoTime() - lastPollTime > THRESHOLD_TIME) {
                emptyPollCount++;
            }
            if (emptyPollCount >= THRESHOLD) {
                workingStatus = WorkingStatusEnum.STARTING;
                log.warn("RelayManager restarting...");
                Set<SelectionKey> keys = selector.keys();
                selector.close();
                selector = Selector.open();
                for (SelectionKey key : keys) {
                    if (key.isValid()) {
                        Channel channel = key.channel();
                        if (channel instanceof SocketChannel) {
                            SocketChannel socketChannel = (SocketChannel) channel;
                            socketChannel.register(selector, key.interestOps(), key.attachment());
                        } else if (channel instanceof DatagramChannel) {
                            DatagramChannel datagramChannel = (DatagramChannel) channel;
                            datagramChannel.register(selector, key.interestOps(), key.attachment());
                        }
                    }
                }
                workingStatus = WorkingStatusEnum.WORKING;
                log.warn("RelayManager restarted cased by empty poll");
                emptyPollCount = 0;
            }
        }
    }

    public void submitTask(RelayTask relayTask) {
        if (isRunning()) {
            taskQueue.offer(relayTask);
            selector.wakeup();
        }
    }

    private boolean closeRelayChannel(String id) {
        RelayChannelAttachment attachment = relayChannelAttachments.get(id);
        return closeRelayChannel(attachment);
    }

    private boolean closeRelayChannel(RelayChannelAttachment attachment) {
        if (attachment != null) {
            if (attachment instanceof TcpRelayChannelPairAttachment) {
                TcpRelayChannelPairAttachment tcpRelayChannelPairAttachment = (TcpRelayChannelPairAttachment) attachment;
                tcpRelayChannelPairAttachment.close();
                relayChannelAttachments.remove(tcpRelayChannelPairAttachment.getTempId());
                return true;
            } else {
                UdpRelayChannelAttachment udpRelayChannelAttachment = (UdpRelayChannelAttachment) attachment;
                udpRelayChannelAttachment.close();
                relayChannelAttachments.remove(udpRelayChannelAttachment.getChannelId());
                return true;
            }
        }

        return false;
    }

    private void closeChannel(Channel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
            }
        }
    }

    public void shutdown() {
        workingStatus = WorkingStatusEnum.STOPPING;

        taskQueue.clear();

        relayChannelAttachments.forEach((tempId, attachment) -> {
            closeRelayChannel(attachment);
        });

        try {
            selector.close();
        } catch (IOException e) {
        }

        workingStatus = WorkingStatusEnum.STOPPED;
    }

    private boolean isRunning() {
        return workingStatus.getCode() <= WorkingStatusEnum.WORKING.getCode();
    }

    private void logError(String msg, Object... objects) {
        log.error(msg, objects);
    }
}
