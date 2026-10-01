# 项目记忆

## 项目概况
- AI 面试平台：Spring Boot 4.0 + Java 21 + Spring AI 2.0 + React 18
- 单模块 Gradle 项目，前后端分离
- 用户：漆治含，天津理工大学大二升大三，Java 后端方向

## 关键架构知识
- LLM Provider 配置存数据库 `llm_provider_config` 表，API Key 用 AES-256-GCM 加密
- `LlmProviderBootstrapService` 只在表为空时 seed，改 `.env` 后 DB 不会自动更新（**坑**）
- 加密 Key 未配置时用 dev fallback: `interview-guide-dev-only-provider-api-key-encryption`
- `./gradlew bootRun` 的端口可能有异常（49677 而非 8080），需 `--args="--server.port=8080"`

## 已知问题与修复
- 2026-07-07: 修改 `.env` 中 API Key 后需手动更新数据库或清空 `llm_provider_config` 表重启
