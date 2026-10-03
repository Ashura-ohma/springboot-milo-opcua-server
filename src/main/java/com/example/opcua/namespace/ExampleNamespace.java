package com.example.opcua.namespace;

import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import com.example.opcua.config.OpcUaServerProperties;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;

import java.util.List;

public class ExampleNamespace extends ManagedNamespaceWithLifecycle {
    public static final String NAMESPACE_URI = "urn:example:opcua:demo";


    public ExampleNamespace(OpcUaServer server) {
        this(server, new OpcUaServerProperties.DemoProperties());
    }

    public ExampleNamespace(OpcUaServer server, OpcUaServerProperties.DemoProperties properties) {
        super(server, properties.getNamespaceUri());
        createAddressSpace(properties);
    }

    private void createAddressSpace(OpcUaServerProperties.DemoProperties properties) {
        // 2. 获取节点管理器
        var nodeManager = getNodeManager();

        // 3. 创建一个根文件夹 "MyDevice"
        NodeId folderNodeId = newNodeId("MyDevice");
        UaFolderNode folderNode = new UaFolderNode(
                getNodeContext(),
                folderNodeId,
                newQualifiedName("MyDevice"),
                LocalizedText.english("MyDevice")
        );

        // 将文件夹添加到 Server 的 "Objects" 目录下
        nodeManager.addNode(folderNode);
        folderNode.addReference(new Reference(
                folderNode.getNodeId(),
                Identifiers.Organizes,
                Identifiers.ObjectsFolder.expanded(),
                false
        ));

        // 4. 创建一个变量节点 "SensorValue"
        NodeId varNodeId = newNodeId("MyDevice/SensorValue");
        UaVariableNode variableNode = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
                .setNodeId(varNodeId)
                .setAccessLevel(properties.isWritable() ? AccessLevel.READ_WRITE : AccessLevel.READ_ONLY)
                .setUserAccessLevel(properties.isWritable() ? AccessLevel.READ_WRITE : AccessLevel.READ_ONLY)
                .setBrowseName(newQualifiedName("SensorValue"))
                .setDisplayName(LocalizedText.english("Sensor Value"))
                .setDataType(Identifiers.Double)
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .build();

        // 设置初始值
        variableNode.setValue(new DataValue(new Variant(properties.getInitialValue())));

        // 将变量加入到文件夹中
        nodeManager.addNode(variableNode);
        folderNode.addOrganizes(variableNode);
    }



    @Override
    public void onDataItemsCreated(List<DataItem> list) {

    }

    @Override
    public void onDataItemsModified(List<DataItem> list) {

    }

    @Override
    public void onDataItemsDeleted(List<DataItem> list) {

    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> list) {

    }

}

