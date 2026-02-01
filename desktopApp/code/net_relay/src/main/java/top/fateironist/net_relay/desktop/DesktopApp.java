package top.fateironist.net_relay.desktop;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import top.fateironist.net_relay.core.communication.CommunicationManager;
import top.fateironist.net_relay.core.relay.RelayManager;
import top.fateironist.net_relay.model.common.properties.AgentProperties;
import top.fateironist.net_relay.model.common.properties.ProxyServerProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class DesktopApp extends Application {
    private boolean isProxyRunning = false;

    // UI组件 - 全部在start方法中初始化
    private Label statusLabel;
    private Button toggleButton;
    private TextField serverIp;
    private TextField serverPort;
    private TextField proxyTcpPort;
    private TextField proxyUdpPort;

    // 代理信息表单组件
    private TableView<ProxyInfo> proxyInfoTable;

    // 配置管理对象
    private ProxyConfigManager configManager;

    @Override
    public void start(Stage primaryStage) {
        // 初始化配置管理器
        configManager = new ProxyConfigManager();

        // === 1. 初始化所有组件（直接创建，不通过方法） ===
        // 创建状态标签（不使用createStatusLabel方法）
        statusLabel = new Label(isProxyRunning ? " 代理服务运行中" : " 代理服务已停止");
        statusLabel.setFont(Font.font("Microsoft YaHei", FontWeight.MEDIUM, 14));
        statusLabel.setPadding(new Insets(10, 0, 5, 0));
        statusLabel.setTextFill(isProxyRunning ? Color.web("#27AE60") : Color.web("#95A5A6"));

        // 创建切换按钮
        toggleButton = new Button(isProxyRunning ? "取消代理" : "启动代理");
        toggleButton.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
        toggleButton.setPrefWidth(180);
        toggleButton.setPrefHeight(45);

        // 初始按钮样式
        if (isProxyRunning) {
            toggleButton.setStyle(
                    "-fx-background-color: linear-gradient(to right, #E74C3C, #C0392B);" +
                            "-fx-background-radius: 10;" +
                            "-fx-cursor: hand;" +
                            "-fx-effect: dropshadow(gaussian, rgba(231, 76, 60, 0.3), 10, 0, 0, 3);"
            );
        } else {
            toggleButton.setStyle(
                    "-fx-background-color: linear-gradient(to right, #3498DB, #2980B9);" +
                            "-fx-background-radius: 10;" +
                            "-fx-cursor: hand;" +
                            "-fx-effect: dropshadow(gaussian, rgba(52, 152, 219, 0.3), 10, 0, 0, 3);"
            );
        }

        // 设置按钮事件
        toggleButton.setOnAction(e -> {
            if (!isProxyRunning) {
                if (validateInputs()) {
                    isProxyRunning = true;
                    startProxyService();
                }
            } else {
                isProxyRunning = false;
                stopProxyService();
            }
            updateButtonState(); // 更新按钮和状态
        });

        // 创建输入框
        serverIp = createStyledTextField("例如: 192.168.1.100");
        serverPort = createStyledTextField("例如: 8080");
        proxyTcpPort = createStyledTextField("例如: 80,443,8080");
        proxyUdpPort = createStyledTextField("例如: 53,67,68");

        // 加载保存的配置
        loadSavedConfig();

        // === 2. 主标题区域 ===
        Label topTitle = new Label("NetRelay Client");
        topTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 28));
        topTitle.setTextFill(Color.web("#2C3E50"));
        topTitle.setPadding(new Insets(0, 0, 20, 0));

        // === 3. 服务器属性卡片 ===
        VBox serverCard = createCard("服务器属性");

        GridPane severPropertyGridPane = new GridPane();
        severPropertyGridPane.setHgap(20);
        severPropertyGridPane.setVgap(15);
        severPropertyGridPane.setPadding(new Insets(15));

        severPropertyGridPane.add(createFormLabel("服务器IP:"), 0, 0);
        severPropertyGridPane.add(serverIp, 1, 0);
        severPropertyGridPane.add(createFormLabel("服务器端口:"), 0, 1);
        severPropertyGridPane.add(serverPort, 1, 1);

        serverCard.getChildren().add(severPropertyGridPane);

        // === 4. 代理属性卡片 ===
        VBox proxyCard = createCard("代理属性");

        Label agentPropertyTips = new Label("如果需要配置多个端口，请用英文逗号\",\"进行分割，如\"8080,9090\"");
        agentPropertyTips.setFont(Font.font("Microsoft YaHei", 13));
        agentPropertyTips.setTextFill(Color.web("#7F8C8D"));
        agentPropertyTips.setWrapText(true);
        agentPropertyTips.setMaxWidth(600);
        agentPropertyTips.setPadding(new Insets(0, 0, 15, 0));

        GridPane agentPropertyGridPane = new GridPane();
        agentPropertyGridPane.setHgap(20);
        agentPropertyGridPane.setVgap(15);
        agentPropertyGridPane.setPadding(new Insets(15));

        agentPropertyGridPane.add(createFormLabel("被代理TCP端口:"), 0, 0);
        agentPropertyGridPane.add(proxyTcpPort, 1, 0);
        agentPropertyGridPane.add(createFormLabel("被代理UDP端口:"), 0, 1);
        agentPropertyGridPane.add(proxyUdpPort, 1, 1);

        proxyCard.getChildren().addAll(agentPropertyTips, agentPropertyGridPane);

        // === 5. 代理信息表单卡片 ===
        VBox proxyInfoCard = createCard("代理信息");
        proxyInfoTable = createProxyInfoTable();
        proxyInfoCard.getChildren().add(proxyInfoTable);

        // === 6. 操作按钮 ===
        HBox buttonContainer = new HBox(20);
        buttonContainer.setAlignment(Pos.CENTER);

        Button resetButton = new Button("重置配置");
        resetButton.setFont(Font.font("Microsoft YaHei", FontWeight.NORMAL, 14));
        resetButton.setTextFill(Color.web("#4A5568"));
        resetButton.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-border-color: #CBD5E0;" +
                        "-fx-border-radius: 8;" +
                        "-fx-border-width: 1.5;" +
                        "-fx-padding: 10 25;" +
                        "-fx-cursor: hand;"
        );

        resetButton.setOnMouseEntered(e -> {
            resetButton.setStyle(
                    "-fx-background-color: #F7FAFC;" +
                            "-fx-border-color: #A0AEC0;" +
                            "-fx-border-radius: 8;" +
                            "-fx-border-width: 1.5;" +
                            "-fx-padding: 10 25;" +
                            "-fx-cursor: hand;"
            );
        });

        resetButton.setOnMouseExited(e -> {
            resetButton.setStyle(
                    "-fx-background-color: transparent;" +
                            "-fx-border-color: #CBD5E0;" +
                            "-fx-border-radius: 8;" +
                            "-fx-border-width: 1.5;" +
                            "-fx-padding: 10 25;" +
                            "-fx-cursor: hand;"
            );
        });

        resetButton.setOnAction(e -> resetAllFields());

        buttonContainer.getChildren().addAll(toggleButton, resetButton);

        // === 7. 主布局 ===
        VBox mainContainer = new VBox();
        mainContainer.setSpacing(25);
        mainContainer.setAlignment(Pos.CENTER);
        mainContainer.setPadding(new Insets(30, 40, 40, 40));

        // 创建可滚动容器以支持更多内容
        ScrollPane scrollPane = new ScrollPane(mainContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        // 创建主容器
        GridPane container = new GridPane();
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(20));
        container.setHgap(2);
        container.setVgap(2);

        container.add(serverCard, 0, 0);
        container.add(proxyCard, 0, 1);
        container.add(proxyInfoCard, 1, 0);

        VBox statusContainer = new VBox();
        statusContainer.setSpacing(10);
        statusContainer.setAlignment(Pos.CENTER);
        statusContainer.getChildren().addAll(statusLabel, buttonContainer);

        container.add(statusContainer, 1, 1);

        mainContainer.getChildren().addAll(
                topTitle,
                container
        );

        // === 8. 场景和舞台设置 ===
        Scene scene = new Scene(scrollPane, 1200, 700);

        primaryStage.setTitle("NetRelay Client");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(700);
        primaryStage.setMinHeight(600);
        primaryStage.centerOnScreen();
        primaryStage.show();
    }

    // === 辅助方法 ===

    /**
     * 创建代理信息表格
     */
    private TableView<ProxyInfo> createProxyInfoTable() {
        TableView<ProxyInfo> table = new TableView<>();
        table.setPrefHeight(200);
        table.setStyle("-fx-font-family: 'Microsoft YaHei'; -fx-font-size: 13px;");

        // 创建列
        TableColumn<ProxyInfo, String> protocolCol = new TableColumn<>("Protocol");
        protocolCol.setCellValueFactory(cellData -> cellData.getValue().protocolProperty());
        protocolCol.setPrefWidth(100);

        TableColumn<ProxyInfo, String> localPortCol = new TableColumn<>("LocalPort");
        localPortCol.setCellValueFactory(cellData -> cellData.getValue().localPortProperty());
        localPortCol.setPrefWidth(100);

        TableColumn<ProxyInfo, String> remoteAddressCol = new TableColumn<>("RemoteAddress");
        remoteAddressCol.setCellValueFactory(cellData -> cellData.getValue().remoteAddressProperty());
        remoteAddressCol.setPrefWidth(150);

        TableColumn<ProxyInfo, String> remotePortCol = new TableColumn<>("RemotePort");
        remotePortCol.setCellValueFactory(cellData -> cellData.getValue().remotePortProperty());
        remotePortCol.setPrefWidth(100);

        table.getColumns().addAll(protocolCol, localPortCol, remoteAddressCol, remotePortCol);

        return table;
    }

    /**
     * 加载保存的配置
     */
    private void loadSavedConfig() {
        serverIp.setText(configManager.getProperty("serverIp", ""));
        serverPort.setText(configManager.getProperty("serverPort", ""));
        proxyTcpPort.setText(configManager.getProperty("proxyTcpPort", ""));
        proxyUdpPort.setText(configManager.getProperty("proxyUdpPort", ""));
    }

    /**
     * 保存配置到文件
     */
    private void saveConfigToFile() {
        configManager.setProperty("serverIp", serverIp.getText());
        configManager.setProperty("serverPort", serverPort.getText());
        configManager.setProperty("proxyTcpPort", proxyTcpPort.getText());
        configManager.setProperty("proxyUdpPort", proxyUdpPort.getText());
        configManager.saveConfig();
    }

    /**
     * 创建卡片容器
     */
    private VBox createCard(String title) {
        VBox card = new VBox();
        card.setSpacing(10);
        card.setPadding(new Insets(20));
        card.setStyle(
                "-fx-background-color: white;" +
                        "-fx-background-radius: 12;" +
                        "-fx-border-color: #E0E0E0;" +
                        "-fx-border-radius: 12;" +
                        "-fx-border-width: 1;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 10, 0, 0, 2);"
        );
        card.setMaxWidth(700);

        Label cardTitle = new Label(title);
        cardTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 18));
        cardTitle.setTextFill(Color.web("#3498DB"));
        cardTitle.setPadding(new Insets(0, 0, 5, 0));

        Separator separator = new Separator();
        separator.setPrefWidth(650);
        separator.setStyle("-fx-background-color: #F0F0F0;");

        card.getChildren().addAll(cardTitle, separator);
        return card;
    }

    /**
     * 创建样式化输入框
     */
    private TextField createStyledTextField(String prompt) {
        TextField textField = new TextField();
        textField.setPromptText(prompt);
        textField.setPrefWidth(300);
        textField.setPrefHeight(40);
        textField.setStyle(
                "-fx-background-color: #F8F9FA;" +
                        "-fx-background-radius: 8;" +
                        "-fx-border-color: #D1D5DB;" +
                        "-fx-border-radius: 8;" +
                        "-fx-border-width: 1.5;" +
                        "-fx-padding: 10 15;" +
                        "-fx-font-size: 14px;" +
                        "-fx-font-family: 'Microsoft YaHei';"
        );

        textField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                textField.setStyle(
                        "-fx-background-color: white;" +
                                "-fx-background-radius: 8;" +
                                "-fx-border-color: #3498DB;" +
                                "-fx-border-radius: 8;" +
                                "-fx-border-width: 2;" +
                                "-fx-padding: 10 15;" +
                                "-fx-font-size: 14px;" +
                                "-fx-font-family: 'Microsoft YaHei';"
                );
            } else {
                textField.setStyle(
                        "-fx-background-color: #F8F9FA;" +
                                "-fx-background-radius: 8;" +
                                "-fx-border-color: #D1D5DB;" +
                                "-fx-border-radius: 8;" +
                                "-fx-border-width: 1.5;" +
                                "-fx-padding: 10 15;" +
                                "-fx-font-size: 14px;" +
                                "-fx-font-family: 'Microsoft YaHei';"
                );
            }
        });

        return textField;
    }

    /**
     * 创建表单标签
     */
    private Label createFormLabel(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("Microsoft YaHei", FontWeight.MEDIUM, 15));
        label.setTextFill(Color.web("#4A5568"));
        label.setPrefWidth(120);
        return label;
    }

    /**
     * 更新按钮和状态
     */
    private void updateButtonState() {
        // 更新按钮文本
        toggleButton.setText(isProxyRunning ? "取消代理" : "启动代理");

        // 更新按钮样式
        if (isProxyRunning) {
            toggleButton.setStyle(
                    "-fx-background-color: linear-gradient(to right, #E74C3C, #C0392B);" +
                            "-fx-background-radius: 10;" +
                            "-fx-cursor: hand;" +
                            "-fx-effect: dropshadow(gaussian, rgba(231, 76, 60, 0.3), 10, 0, 0, 3);"
            );

            toggleButton.setOnMouseEntered(e -> {
                toggleButton.setStyle(
                        "-fx-background-color: linear-gradient(to right, #C0392B, #A93226);" +
                                "-fx-background-radius: 10;" +
                                "-fx-cursor: hand;" +
                                "-fx-effect: dropshadow(gaussian, rgba(192, 57, 43, 0.4), 12, 0, 0, 4);"
                );
            });

            toggleButton.setOnMouseExited(e -> {
                toggleButton.setStyle(
                        "-fx-background-color: linear-gradient(to right, #E74C3C, #C0392B);" +
                                "-fx-background-radius: 10;" +
                                "-fx-cursor: hand;" +
                                "-fx-effect: dropshadow(gaussian, rgba(231, 76, 60, 0.3), 10, 0, 0, 3);"
                );
            });
        } else {
            toggleButton.setStyle(
                    "-fx-background-color: linear-gradient(to right, #3498DB, #2980B9);" +
                            "-fx-background-radius: 10;" +
                            "-fx-cursor: hand;" +
                            "-fx-effect: dropshadow(gaussian, rgba(52, 152, 219, 0.3), 10, 0, 0, 3);"
            );

            toggleButton.setOnMouseEntered(e -> {
                toggleButton.setStyle(
                        "-fx-background-color: linear-gradient(to right, #2980B9, #2573A7);" +
                                "-fx-background-radius: 10;" +
                                "-fx-cursor: hand;" +
                                "-fx-effect: dropshadow(gaussian, rgba(41, 128, 185, 0.4), 12, 0, 0, 4);"
                );
            });

            toggleButton.setOnMouseExited(e -> {
                toggleButton.setStyle(
                        "-fx-background-color: linear-gradient(to right, #3498DB, #2980B9);" +
                                "-fx-background-radius: 10;" +
                                "-fx-cursor: hand;" +
                                "-fx-effect: dropshadow(gaussian, rgba(52, 152, 219, 0.3), 10, 0, 0, 3);"
                );
            });
        }

        // 更新状态标签
        if (isProxyRunning) {
            statusLabel.setText(" 代理服务运行中");
            statusLabel.setTextFill(Color.web("#27AE60"));
        } else {
            statusLabel.setText(" 代理服务已停止");
            statusLabel.setTextFill(Color.web("#95A5A6"));
        }

        // 更新输入框状态
        updateFieldsEditable(!isProxyRunning);
    }

    /**
     * 验证输入
     */
    private boolean validateInputs() {
        if (serverIp.getText().trim().isEmpty()) {
            showAlert("验证错误", "请输入服务器IP地址");
            return false;
        }

        if (serverPort.getText().trim().isEmpty()) {
            showAlert("验证错误", "请输入服务器端口");
            return false;
        }

        return true;
    }

    /**
     * 启动代理服务
     */
    private void startProxyService() {
        // 保存配置到文件
        saveConfigToFile();

        String ip = serverIp.getText();
        Integer port = Integer.parseInt(serverPort.getText());
        String[] tcpPorts = proxyTcpPort.getText().split(",");
        String[] udpPorts = proxyUdpPort.getText().split(",");

        Thread.ofVirtual().start(() -> {
            ProxyServerProperties proxyServerProperties = new ProxyServerProperties(ip, port);
            AgentProperties agentProperties = new AgentProperties(tcpPorts, udpPorts);

            RelayManager relayManager = new RelayManager(proxyServerProperties);
            CommunicationManager communicationManager = new CommunicationManager(proxyServerProperties, agentProperties, relayManager);

            try {
                communicationManager.init();
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> showAlert("启动失败", "代理服务启动失败"));
                return;
            }

            Platform.runLater(() -> {
                // 根据配置信息填充代理信息表（示例数据）
                populateProxyInfoTable(communicationManager);

                showAlert("启动成功", "代理服务已成功启动");
            });
        });
    }

    /**
     * 填充代理信息表
     */
    private void populateProxyInfoTable(CommunicationManager communicationManager) {
        proxyInfoTable.getItems().clear();

        // 示例数据：根据服务器返回的信息填充
        List<ProxyInfo> proxyInfoList = new ArrayList<>();

        String host = communicationManager.getProxyServerProperties().getHost();

        communicationManager.getTcpProxy().forEach((key, value) -> {
            proxyInfoList.add(new ProxyInfo("TCP", key.toString(), host, value.toString()));
        });

        communicationManager.getUdpProxy().forEach((key, value) -> {
            proxyInfoList.add(new ProxyInfo("UDP", key.toString(), host, value.toString()));
        });

        proxyInfoTable.getItems().addAll(proxyInfoList);
    }

    /**
     * 停止代理服务
     */
    private void stopProxyService() {
        System.out.println("停止代理服务...");
        proxyInfoTable.getItems().clear();
        showAlert("停止成功", "代理服务已停止");
    }

    /**
     * 更新输入框可编辑状态
     */
    private void updateFieldsEditable(boolean editable) {
        serverIp.setEditable(editable);
        serverPort.setEditable(editable);
        proxyTcpPort.setEditable(editable);
        proxyUdpPort.setEditable(editable);

        String opacity = editable ? "1.0" : "0.7";
        String currentStyle = serverIp.getStyle();
        String newStyle = currentStyle.replaceAll("-fx-opacity: [0-9.]+;", "") + "-fx-opacity: " + opacity + ";";

        serverIp.setStyle(newStyle);
        serverPort.setStyle(newStyle);
        proxyTcpPort.setStyle(newStyle);
        proxyUdpPort.setStyle(newStyle);
    }

    /**
     * 重置所有字段
     */
    private void resetAllFields() {
        serverIp.clear();
        serverPort.clear();
        proxyTcpPort.clear();
        proxyUdpPort.clear();

        if (isProxyRunning) {
            isProxyRunning = false;
            stopProxyService();
            updateButtonState();
        }

        showAlert("重置成功", "所有配置已重置");
    }

    /**
     * 显示提示对话框
     */
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

    public static void main(String[] args) {
        launch(args);
    }

//    public static void runDesktop() {
//        launch();
//    }

    /**
     * 内部类：代理信息数据模型
     */
    public static class ProxyInfo {
        private javafx.beans.property.SimpleStringProperty protocol;
        private javafx.beans.property.SimpleStringProperty localPort;
        private javafx.beans.property.SimpleStringProperty remoteAddress;
        private javafx.beans.property.SimpleStringProperty remotePort;

        public ProxyInfo(String protocol, String localPort, String remoteAddress, String remotePort) {
            this.protocol = new javafx.beans.property.SimpleStringProperty(protocol);
            this.localPort = new javafx.beans.property.SimpleStringProperty(localPort);
            this.remoteAddress = new javafx.beans.property.SimpleStringProperty(remoteAddress);
            this.remotePort = new javafx.beans.property.SimpleStringProperty(remotePort);
        }

        public javafx.beans.property.SimpleStringProperty protocolProperty() {
            return protocol;
        }

        public javafx.beans.property.SimpleStringProperty localPortProperty() {
            return localPort;
        }

        public javafx.beans.property.SimpleStringProperty remoteAddressProperty() {
            return remoteAddress;
        }

        public javafx.beans.property.SimpleStringProperty remotePortProperty() {
            return remotePort;
        }

        public String getProtocol() {
            return protocol.get();
        }

        public String getLocalPort() {
            return localPort.get();
        }

        public String getRemoteAddress() {
            return remoteAddress.get();
        }

        public String getRemotePort() {
            return remotePort.get();
        }
    }

    /**
     * 内部类：配置管理器
     */
    private static class ProxyConfigManager {
        private static final String CONFIG_FILE = System.getProperty("user.home") + "/net-relay/" + "data/proxy_config.properties";
        private Properties properties;

        public ProxyConfigManager() {
            this.properties = new Properties();
            loadConfig();
        }

        /**
         * 加载配置
         */
        private void loadConfig() {
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    properties.load(fis);
                    System.out.println("配置已加载");
                } catch (IOException e) {
                    System.err.println("加载配置失败: " + e.getMessage());
                }
            } else {
                System.out.println("配置文件不存在，将使用默认配置");
            }
        }

        /**
         * 保存配置
         */
        public void saveConfig() {
            File configFile = new File(CONFIG_FILE);
            configFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
                properties.store(fos, "代理服务配置 - " + java.time.LocalDateTime.now());
                System.out.println("配置已保存");
            } catch (IOException e) {
                System.err.println("保存配置失败: " + e.getMessage());
            }
        }

        /**
         * 获取配置值
         */
        public String getProperty(String key, String defaultValue) {
            return properties.getProperty(key, defaultValue);
        }

        /**
         * 设置配置值
         */
        public void setProperty(String key, String value) {
            properties.setProperty(key, value);
        }
    }
}