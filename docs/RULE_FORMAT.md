# NotifyBox 规则导出格式 v2

使用 UTF-8 JSON。顶层包含 format="notifybox"、schemaVersion=2、rules、profiles 和 profileConfigs；兼容 v1 文件，未知字段或格式版本会拒绝导入。完整导出保留规则启用状态及转发配置，导入新增规则仍默认关闭。规则自身 schemaVersion 保持 1。见 ../examples/notifybox.rules.json 中兼容的 v1 示例。

## 规则

Rule：id、name、enabled、schemaVersion、applications、textCondition、actions、migrationIssues。

- applications 是 { packageName, userId } 数组。userId=null 表示该包名的所有用户；空数组不匹配任何应用的通知。
- userId=0 表示主用户；999 只作为实际系统标识保存，是否为分身及是否能监听需要真机验证。
- textCondition.fields 为 TITLE / TEXT，可选择一个或两者；values=[] 不限制文本。
- operator：CONTAINS（单个包含）、ANY（包含任一）、ALL（包含全部）、NOT_CONTAINS（不包含任一）、REGEX（正则任一）。
- 各字段分别匹配；ALL 可由不同字段共同满足多个条件。不会把标题与正文拼接，避免跨字段产生虚假子串。
- ignoreCase 控制大小写。每条规则最多 64 个条件、每个最多 2048 字、最多 16 个动作。
- 正则采用 RE2/J，避免回溯耗时失控，不支持环视和反向引用。规则更改后重新校验。
- migrationIssues 非空时不能直接启用；编辑并确认后清除待确认项。

动作使用稳定的 type 区分：

    {"type":"dismiss","delayMs":5000,"includeOngoing":true}
    {"type":"webhook","profileId":"telegram-example","dedupEnabled":true,"dedupWindowSeconds":5}

消除 delayMs 为 0—86400000；includeOngoing 只是允许尝试，不保证系统接受。单条通知可匹配多条规则；同一通知的消除采用所有有效动作中最早的时间，来源规则记录在日志中。禁用或删除来源规则后，执行时移除对应计划，并重新计算剩余规则的最早时间。

新建规则默认启用，新增消除动作默认延迟 5 秒并包括常驻通知。已有规则的显式启用状态、延迟与常驻设置不变；导入规则仍默认关闭。

Webhook 动作引用单独的配置。方法、URL、请求头、正文及 Telegram 凭证在本机统一加密，规则表中只保存引用，多条规则可复用同一目的地。

## 转发配置

profiles 保存 id / name / kind（GENERIC 或 TELEGRAM），profileConfigs 以配置 ID 为键保存完整 WebhookConfig。导入时配置 ID 与规则引用一起重新映射，配置在设备上重新加密；v1 文件缺少完整配置时仍需要补填。只在用户已配置并持有持久写权限的 SAF 目录创建 `NotifyBox_exportConfig_yyyy-MM-dd HH_mm_ss.json`，不覆盖已有文件。

内部 WebhookConfig：method、urlTemplate、headers、bodyTemplate、jsonBody、telegramToken、chatId、messageTemplate。完整配置和排队后的渲染请求使用 Keystore AES-GCM 密文保存，不放入系统备份。

通用 Webhook 仅支持 HTTPS GET/POST。URL 占位内容编码；JSON 先解析、替换字符串节点再序列化，模板占位符必须位于字符串中。GET 不发送请求体。不会自动跟随重定向。

支持 {{android.title}}、{{android.text}}、{{android.package}}、{{android.appName}}、{{android.userId}}、{{android.postedAt}}、{{android.key}}，兼容单花括号写法。filterbox.field.PACKAGE_NAME / APP_NAME 有对应别名；WHEN 未确认，校验失败，需要用户明确改成新时间字段。

Telegram 固定使用 sendMessage 的 POST JSON，body 仅含 chat_id 与 text；正文默认标题、换行、正文。必须 HTTP 2xx 且 ok=true 才确认成功。

去重键由规则、目的地、包名、用户、方法、URL、请求头和渲染正文组成；时间窗口按任务入队时间计算，默认 5 秒。不同用户、不同规则不会互相抑制。网络故障默认持续退避重试，超时重发可能重复接收。自动重试保留已加密的请求内容，每次重新读取代理设置。手动重试更新原任务并使用当前配置；原通知已过期时 Telegram 保留原消息并更新凭证与聊天，通用 Webhook 保留原请求。

## 旧 FilterBox 格式

适配器位于 core/ImportAdapter.kt。没有用户原始导出及可靠内部规范，不能宣称无损转换。

接受规则数组，或 rules / filters 中的数组。只转换 name 及显式 applications[].packageName/userId；其他应用编码必须重新填写。根据设计稿确认过的四个名称重建基本动作：

- 全部消除：不限文本、4000 ms、尝试处理常驻；不保留 strs/strs1 残留列表。
- 部分消除：重建消除动作，文本匹配字段及关键词待用户确认。
- 微信转发至 tg：新建需要补填凭证的 Telegram 配置，默认去重 5 秒。
- 短信转发至 tg：同上，并排除“点按即可了解详情或停止应用”。

星期、屏幕、充电、method、distinct、其他未知条件/动作或字段不猜测；列为阻止启用的待确认项。dismissed_notify 不承诺还原。旧 URL 和凭证不保存；发现 URL 换行时在预览提示重新确认及填写。通知按钮操作永远不进入执行引擎。
