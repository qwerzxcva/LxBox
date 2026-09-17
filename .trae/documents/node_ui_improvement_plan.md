# 节点与路由出口 UI 改进计划

## 代码调研结论

### 现状速览
- `OutboundProfile` 数据模型（`AppState.kt:399`）只有 `type: String`，没有国家/地区/旗帜字段；节点的 name 通常自带 emoji 🇭🇰 等
- `NodeRow`（`SubscriptionsScreen.kt:872`）目前只显示 `node.name` + `node.type`（一行小字），没有完整协议
- `RuleEditorPage` 的出口 picker（`RuleEditorPage.kt:258-280`）目前是一个 `SingleChoiceChips`：全部 groups + 全部 nodes 都摊开列出来
- `ping`（测速）依赖 VpnService 运行 + sing-box UrlTest 机制；没有运行中的 tunnel 时测速**本身就无法工作**（预期行为），不是 UI bug
- `speedTestUrl` 配置字段**不存在**于 UI 中 — `AppState.speedTestUrl = "https://cp.cloudflare.com/generate_204"` 硬编码在默认值里
- `OutboundProfile` 没有 region/flag 字段，需要在 UI 层从节点名字解析（取第一个 emoji → 国家代码 → 完整旗帜 emoji）

### 技术约束
1. **RouteRule.outbound 是 String**（单 tag），sing-box 原生就不支持 "一个 route rule 指向多个节点"
2. 想让 route rule 出口 = 多个节点，必须在编译器层自动创建一个临时 selector group，把选中的节点塞进去，然后 RouteRule.outbound 指向那个 group tag
3. 这个复杂度较高，建议先做 "旗帜分组 + 每旗帜可挑节点" 的 UI，然后在 compile 层生成一个临时 OutboundGroup

## 文件与模块

| 文件 | 改动 |
|------|------|
| `AppState.kt` | RouteRule 新增 `outboundNodes: List<String>`（可选多选，编译时自动生成 group）；新增 `ProxyGroupTag: String` 常量 |
| `SubscriptionsScreen.kt` | NodeRow 显示完整协议（type + config 摘要）；节点列表改为**旗帜分组折叠列表**；新增"测速地址"字段 |
| `NodeRow` 改为 `FlaggedNodeGroup` | 每个旗帜 emoji 一行，显示地区名 + 节点数 + "展开/收起"；展开后显示具体 NodeRow |
| `RuleEditorPage.kt` | 出口 picker 重构：先两档（代理/直连）→ 选代理 → 显示旗帜分组多选器 |
| `new: FlagParser.kt` | 从节点 name 提取旗帜 emoji → 国家代码 → 地区名；聚合 nodes by region |
| `ConfigCompiler.kt` | 如果 RouteRule.outboundNodes 非空，动态创建 selector group，把 outboundNodes 塞进去，outbound 指向那个 tag |
| `strings.xml` | 新增资源串 |

## 实现步骤（按依赖顺序）

### Phase 1 — 基础能力：旗帜解析器
1. **新建 `FlagParser.kt`**：从 emoji 取 flag（第一个 2-区域字母的国家 emoji）→ 映射到地区名称（🇭🇰 → Hong Kong, 🇸🇬 → Singapore, 🇺🇸 → USA…）→ 同时提取协议（从 config 解析 `type` 字段，或从 OutboundProfile.type）
2. **写 `groupByRegion(nodes: List<OutboundProfile>): List<RegionGroup>`**：按旗帜分组，同旗帜按节点名排序
3. RegionGroup 数据类：`flagEmoji, regionName, nodes: List<OutboundProfile>`

### Phase 2 — 节点列表 UI
4. **NodeRow 改协议显示**：第二行从 `node.type` → `node.type · server:port`（从 config JSON 里取 server/port）
5. **节点列表改为折叠分组**：`FlaggedNodeGroupRow` — 每行显示 emoji + 地区名 + "×N"，点击展开/收起；默认按 config 顺序展开状态由 count 决定（≤5 自动展开，>5 折叠）
6. **新增测速地址入口**：在节点区 header 或设置页加一个 speedTestUrl 可编辑字段

### Phase 3 — RouteRule 出口 picker 重构
7. **简化 picker 第一层**：`SingleChoiceChips` 只显示两个：代理（→selector）、直连
8. **选代理后展开旗帜多选器**：显示所有旗帜分组，每个分组右侧有 expand 图标
9. **旗帜可多选**：勾选旗帜 = 选该旗帜下所有节点；展开后可取消个别节点
10. **编辑时回填**：根据 RouteRule.outboundNodes → 选中对应旗帜和节点

### Phase 4 — 编译层适配（关键）
11. **ConfigCompiler 新增临时 group 逻辑**：RouteRule.outboundNodes 非空时：
    - 生成唯一 tag `"auto-pool-${rule.id.take(8)}"`
    - 在 compiled outboundGroups 里插入一个 selector group，members = outboundNodes
    - 该 rule 的 outbound 指向这个 tag
12. 保持 RouteRule.outboundNodes 为空时走原有路径（兼容旧配置）

## 依赖与注意事项

- **Emoji → 国家映射**：Unicode flag 由两个 regional indicator 字母组成（U+1F1E6–U+1F1FF），可通过正则提取 `\uD83C\uDDE6-\uD83C\uDDFF\uD83C\uDDE6-\uD83C\uDDFF` 匹配
- **节点名可能无旗帜**：归类到 "其他 / Unknown" 分组，显示 🌐
- **config JSON 解析**：每个 OutboundProfile.config 是 JSON string，需要用 kotlinx.serialization.json 取 `type`/`server`/`port`
- **向后兼容**：RouteRule.outboundNodes 默认为空 list，旧配置不需要迁移

## 验证
- `cargo test`（Rust 编译层不受影响）
- Gradle assembleDebug（Kotlin 编译通过）
- 手动在 UI 上测试：同旗帜节点合并显示 / 展开后选节点 / RouteRule 出口 picker 两级结构 / 编译后 sing-box 配置正确生成临时 group

## 风险
- **Emoji 解析可能遇到变体**：某些订阅用的是 Unicode flag 变体选择符（VS16），解析器要跳过
- **多选节点编译成 selector 可能影响用户预期**：sing-box selector 支持手动选节点，但 route rule 指向 selector 的行为是 "该 rule 流量走 selector 当前选中的节点"，不是同时走多个节点。需要在 UI 文案上说明
- **节点配置里没有 server/port**：极少数 outbound 类型可能没有这些字段（如 HTTP 代理），显示降级到只有 type
