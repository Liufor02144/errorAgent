# Contributing to ErrorAgent

感谢你参与 ErrorAgent。

## 开始开发

1. Fork 仓库并从 `main` 创建分支。
2. 使用 JDK 17 和 Maven 3.9+。
3. 后端修改至少执行 `mvn test`。
4. 前端修改执行 `cd web && npm ci && npm run build`。
5. 提交前确认 `git diff --check` 没有格式问题，且没有日志、密钥、内部域名或真实业务数据。

## Pull Request

- 一个 PR 聚焦一个问题。
- 描述问题、实现方式和验证结果。
- 行为变化应补充测试或说明为什么无法测试。
- UI 变化建议附截图。
- 不要提交 `target/`、`node_modules/`、`.env`、数据库数据或本地克隆的业务仓库。

## 安全与隐私

测试样本必须是编造数据。请勿在 Issue、PR、测试、截图或日志中提交真实用户信息、访问密钥、公司内部地址和生产日志。

安全漏洞请遵循 [SECURITY.md](SECURITY.md) 的私下报告方式。
