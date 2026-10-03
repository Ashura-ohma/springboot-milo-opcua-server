# Spring Boot Milo OPC UA Server

基于 Eclipse Milo 的可配置 OPC UA **服务端**。保留 `spring.opcua.server` 配置前缀，支持独立运行及作为依赖自动装配。不是 OPC UA 客户端，不会主动连接 PLC。

## 兼容性与构建

- Java 11 / 17，Spring Boot 2.7.18，Eclipse Milo 0.6.13
- 保持原有 Java 11 / Boot 2 API；本次不做 Boot 3/4、Jakarta 或 Milo 1.x 破坏性迁移
- Boot 2.7 已结束开源支持。这是兼容性改造，不代表生产安全基线已经升级；公开部署前应规划受支持版本迁移及依赖漏洞审计
- Maven 3.6.3+：`mvn clean verify`
- 普通依赖包：`target/opcua-server-demo-1.0.0-SNAPSHOT.jar`
- 独立启动包：`target/opcua-server-demo-1.0.0-SNAPSHOT-exec.jar`

## 5 分钟本地启动

请自行设置 `OPCUA_KEYSTORE_PASSWORD` 为非空密码（不要写进源码或提交到 Git）。然后：

```bash
mvn clean verify
java -jar target/opcua-server-demo-1.0.0-SNAPSHOT-exec.jar --spring.profiles.active=dev
```

`dev` 明确允许生成本机自签名证书，默认只监听 `127.0.0.1:4840`，公告 `localhost`，匿名连接、无消息加密，仅适合本地演示。可用 UaExpert 连接 `opc.tcp://localhost:4840/`；演示节点 URI 为 `urn:example:opcua:demo`，字符串标识 `MyDevice/SensorValue`，初始值 123.45，默认只读。命名空间索引由服务端分配，不要写死。

没有指定 dev 时，默认不生成证书；必须自行准备 keystore。`spring.opcua.server.enabled=false` 将完全关闭 OPC UA 自动装配，且不要求证书密码、不创建证书或监听端口。

## application.yml 配置

外部配置文件、环境变量、命令行和 Spring profiles 均在运行时生效，不再依赖 Maven 资源替换或重新打包。

```yaml
spring:
  opcua:
    server:
      enabled: true
      application-name: My OPC UA Server
      product-uri: urn:my-company:opcua
      application-uri: urn:my-company:opcua:server-01
      bind-address: 127.0.0.1
      advertised-hosts: [localhost]
      path: /
      discovery-path: /discovery
      tcp:
        port: 4840
        encoding: binary
      https:
        enabled: false
        port: 8443
        encoding: binary
      security:
        policy: Basic256Sha256
        mode: SignAndEncrypt
      authentication:
        token-policies: [username]
      key-store:
        path: /run/opcua/server.p12
        type: PKCS12
        server-alias: server-opcua
        https-alias: server-https
        password: ${OPCUA_KEYSTORE_PASSWORD}
        generate: false
      trust-list-manager:
        path: /run/opcua/pki
      autostart:
        enabled: true
      lifecycle:
        startup-timeout: 30s
        shutdown-timeout: 30s
      demo:
        enabled: false
        namespace-uri: urn:example:opcua:demo
        initial-value: 123.45
        writable: false
```

- `bind-address` 为本地监听地址，`advertised-hosts` 为客户端可达的公告主机名/IP；容器/NAT 环境请显式设置，并确保它们在证书 SAN 内；IPv6 请填写不带方括号的地址（如 `::1`）
- `application-uri` 必须与已有证书的 URI SAN 完全一致；更改配置不会覆盖或重签已有 keystore
- `security.policy=None` 只能搭配 `mode=None`；安全策略须搭配 `Sign` 或 `SignAndEncrypt`
- 保留 `Basic128Rsa15` 仅兼容旧配置，不建议使用；新部署优先 `Basic256Sha256 / SignAndEncrypt`
- TCP 仅 binary；HTTPS 仅支持 binary（Milo 0.6 实际不支持 xml/json，配置时会明确报错），HTTPS 必须具备独立 https-alias 私钥及证书。先开启 TCP 创建的 keystore 不会自动补写 HTTPS alias
- `discovery-path` 必须以 `/discovery` 结尾（Milo 据此限制为发现服务），业务 `path` 不得以该后缀结尾，两者只接受无 query/fragment 的 URI path，除根路径 `/` 外不可带尾随 `/`
- discovery 端点按协议提供无消息安全的发现服务，不应作为业务会话端点；生产应同时配置身份认证、证书信任、网络访问限制
- `autostart.enabled=false` 仍创建/校验服务端对象及 keystore，但不启动监听；可注入 `MiloServerStarter` 手动 `start()` / `stop()`；Milo 0.6 的实例关闭后不支持重新启动，需重建 Spring context
- 生命周期超时约束 Milo 异步启动/关闭；自定义 namespace 同步回调必须快速返回，不要在回调中执行无界阻塞
- 常用环境变量：`OPCUA_ENABLED`、`OPCUA_BIND_ADDRESS`、`OPCUA_ADVERTISED_HOSTS`（逗号分隔）、`OPCUA_TCP_PORT`、`OPCUA_APPLICATION_URI`、`OPCUA_KEYSTORE_PATH`、`OPCUA_KEYSTORE_PASSWORD`、`OPCUA_TRUST_PATH`

## 用户名认证与自定义节点

配置 username/x509 token 只选择认证方式，默认认证器拒绝所有用户名/证书，不提供内置账户或明文口令。通过业务 Bean 接入真实认证服务：

```java
@Bean
UsernameAuthenticator usernameAuthenticator(AccountService accountService) {
    return credentials -> accountService.verify(
        credentials.getUsername(), credentials.getPassword());
}
```

`AccountService` 是你的业务服务示例，需要自行实现密码哈希验证、限流和账号管理。X509 可实现 `X509Authenticator`。不要将用户密码写进 application.yml。

自定义节点：关闭 `spring.opcua.server.demo.enabled`，声明一个继承 `ManagedNamespaceWithLifecycle` 的 Bean；生命周期管理器按顺序启动 namespace，关闭时逆序释放。需要订阅时自行实现 Milo 的数据项/监控回调；内置演示节点仅提供读写演示，不承诺数据变化订阅。

作为依赖：将普通 jar 安装/发布到你的 Maven 仓库并添加依赖即可；Boot 通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 自动加载。独立示例配置命名为 `opcua-demo*.yml`，仅由示例 main 方法启用，作为依赖时不覆盖宿主 application.yml、通用 profiles 或日志配置。`OpcUaServer`、`TrustListManager`、`ServerCertificateValidator`、认证器、`KeyStoreLoader`、`MiloServerStarter` 支持用户 Bean 覆盖。覆盖 `OpcUaServer` 本身时，默认 keystore/trust-list Bean 仍会创建且配置仍校验；若完全接管服务端基础设施，请同时提供对应 Bean 和有效配置。

## 安全迁移

旧版本把 `security/ks.p12` 放入了 Git。新版本不再随代码提供此文件，默认迁移到 `data/opcua/`，并忽略 keystore/private-key 文件。**移除当前文件不清除 Git 历史；如果曾使用旧证书，必须更换私钥与证书、撤销旧信任并在客户端重新建立信任。** 不要把旧 keystore 拷贝到新路径继续使用。

生产启动示例：准备好外置 keystore、匹配的 URI/SAN、可信客户端证书及认证 Bean，然后设置 `SPRING_PROFILES_ACTIVE=prod`。prod 关闭演示节点和自动证书生成，启用签名加密及用户名认证；没有认证实现时会拒绝登录。不要把 dev 配置用于公网。

## 验证范围

自动化测试覆盖配置绑定/校验、禁用与自动装配、Bean 覆盖、生命周期失败和清理，以及本地回环连接读取演示节点。CI 在 Java 11/17 执行 `mvn clean verify`。不访问真实 PLC 或生产 OPC UA endpoint；HTTPS、真实 CA/用户名认证系统及真实设备兼容性需在你的测试环境验收。

在禁止 JVM 动态附加代理的隔离环境中，Mockito inline 测试需要显式给 Surefire `argLine` 指定当前依赖版本的 Byte Buddy agent jar（`-javaagent:/path/to/byte-buddy-agent.jar`）；这是测试 JVM 选项，不是生产启动参数。
