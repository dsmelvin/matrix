# Change Log for Matrix

## 202-08-02
- Upgrade Spring AI 2.0.1
- Add Google Gen AI models

## 202-08-01
- Add [Discord channel](https://docs.discord4j.com) and [Telegram channel](https://rubenlagus.github.io/TelegramBotsDocumentation/getting-started.html)
  All channels are sharing the same conversation id and response for now. May change later.

## 2026-07-23
- Add Docker environment

## 2026-07-22
- Add spring-ai-starter-mcp-client and can take Claude Desktop JSON format.

## 2026-07-21
- Remove "spring-ai-agent-utils" library dependency  
  "spring-ai-agent-utils" is a good library which has many features learn from Claude Code  
  However, I figure there are quite a few places I want to customize, especially the TaskTool and I don't need the whole packages.
  Also, I have some issues with "FileSystemTools" and TodoWriteTool that I have to modify them.
- Modify ClaudeSubagent using ChatModel instead of ChatClient and remove those [built-in agents](https://github.com/spring-ai-community/spring-ai-agent-utils/tree/main/spring-ai-agent-utils/src/main/resources/agent).

## 2026-07-05
- Integer with [Spring AI 2.0](https://docs.spring.io/spring-ai/reference/index.html) and [Spring AI Agent Utils](https://github.com/spring-ai-community/spring-ai-agent-utils)
- Implement with [User-Controlled Tool Execution](https://docs.spring.io/spring-ai/reference/api/tools.html#_user_controlled_tool_execution) and [ChatModel](https://docs.spring.io/spring-ai/reference/api/tools.html#_with_chatmodel)
- Implement [ImageReaderTool](operator/src/main/java/guru/kumo/operator/tool/ImageReaderTool.java) to work with model that support Vision
- Ability to save/load session memory from a file
- Ability to load prompt from a file
- Include [SkillsJars](https://www.skillsjars.com/docs)