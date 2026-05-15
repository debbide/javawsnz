<div align="center">

# Java-ws
这是一个基于 Java 实现的多协议代理服务器，支持 VLESS、Trojan 和 Shadowsocks 协议 over WebSocket，集成哪吒探针服务(v0或v1)，无需三方内核，资源占用更少。

---

Telegram交流反馈群组：https://t.me/eooceu

</div>>

## 功能特性
- ✅ 多协议支持：VLESS、Trojan、Shadowsocks over WebSocket
- ✅ 协议自动检测：自动识别客户端使用的协议
- ✅ 订阅生成：自动生成 Base64 格式的订阅链接
- ✅ 哪吒监控集成：内置 Java 哪吒探针，无需下载外部 agent 二进制
- ✅ 集中硬编码配置：所有运行配置集中在 `src/main/java/HardcodedConfig.java`
- ✅ 域名屏蔽：自动屏蔽测速网站
- ✅ DNS 缓存：减少 DNS 查询，提高性能
- ✅ 静默模式：非 DEBUG 模式下只显示关键日志
- ✅ 自动端口检测：端口被占用时自动寻找可用端口

* 运行配置集中写在 `src/main/java/HardcodedConfig.java`，修改后需要重新编译打包。
  | 配置项        | 默认值 | 备注 |
  | ------------ | ------ | ------ |
  | UUID         | 7bd180e8-1142-4387-93f5-03e8d750a896 | 节点 UUID，也作为 TUIC UUID 的默认回退 |
  | PORT         | 3000 | 节点监听端口 |
  | NEZHA_SERVER |  | 哪吒服务地址，v1 可填 `nz.abc.com:8008`，v0 可配合 `NEZHA_PORT` |
  | NEZHA_PORT   |  | 哪吒端口，server 已带端口时可留空 |
  | NEZHA_KEY    |  | 哪吒 client secret 或 agent key |
  | NAME         |  | 节点名称前缀，例如 `koyeb` |
  | DOMAIN       |  | 项目分配的域名或已反代的域名，不包括 `https://` 前缀 |
  | SUB_PATH     | sub | 订阅 token |
  | AUTO_ACCESS  | false | 是否开启自动访问保活，需同时填写 `DOMAIN` |
  | DEBUG        | false | 调试模式 |

* 域名/${SUB_APTH}查看节点信息，非标端口，域名:端口/${SUB_APTH}  SUB_APTH为自行设置的订阅token，未设置默认为sub

* 运行环境需要 Java 21。哪吒探针已作为 Java 模块内置，在 `HardcodedConfig` 设置 `NEZHA_SERVER` 和 `NEZHA_KEY` 后随主程序启动，不下载外部二进制，也不生成配置文件。

### 使用cloudflare workers 或 snippets 反代域名给节点套cdn加速,也可以使用端口回源方式
```

export default {
    async fetch(request, env) {
        let url = new URL(request.url);
        if (url.pathname.startsWith('/')) {
            var arrStr = [
                'change.your.domain', // 此处单引号里填写你的节点伪装域名
            ];
            url.protocol = 'https:'
            url.hostname = getRandomArray(arrStr)
            let new_request = new Request(url, request);
            return fetch(new_request);
        }
        return env.ASSETS.fetch(request);
    },
};
function getRandomArray(array) {
  const randomIndex = Math.floor(Math.random() * array.length);
  return array[randomIndex];
}
```

## TUIC client mode

This fork adds a Java TUIC client path without removing the original WebSocket server.

Set `MODE=tuic` in `HardcodedConfig` to run only the local SOCKS5 to TUIC client. Set `MODE=both` to run the
original WebSocket server and the TUIC client in the same process.

| HardcodedConfig field | Default | Description |
| --- | --- | --- |
| MODE | ws | `ws`, `tuic`, or `both` |
| SOCKS_BIND | 127.0.0.1 | Local SOCKS5 bind address |
| SOCKS_PORT | 1080 | Local SOCKS5 port |
| TUIC_SERVER |  | TUIC server hostname or IP |
| TUIC_PORT | 443 | TUIC server UDP port |
| TUIC_UUID | UUID | TUIC UUID; falls back to `UUID` |
| TUIC_PASSWORD | UUID 前 16 位 | TUIC password; empty value derives from `TUIC_UUID`/`UUID` without dashes |
| TUIC_SNI | TUIC_SERVER | TLS SNI |
| TUIC_INSECURE | false | Skip certificate verification |
| TUIC_ALPN | tuic | QUIC ALPN |
| TUIC_CONGESTION_CONTROL | bbr | `bbr`, `cubic`, or `reno` |

Implemented so far: Netty QUIC integration, local SOCKS5 CONNECT inbound, TUIC v5
`Authenticate` / `Connect` / `Heartbeat` command encoding, and TCP stream forwarding.
TUIC v5 standard authentication depends on TLS exporter keying material. Netty QUIC does
not currently expose a stable public exporter API, so this is isolated in
`TlsExporterTokenProvider`; real TUIC server end-to-end validation is still required.

版权所有 ©2026 `eooce`



