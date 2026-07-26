---
name: novel-writing
description: 小说创作主 Skill。教会 agent 如何使用 book_* 工具进行多阶段创作：构建世界观/角色卡、设计两级大纲、按节拍写作、保持连续性。
---

# Novel Writing — 小说创作方法论

当你需要创作或管理小说章节时，加载本 Skill 并按以下工作流推进。

## 文件结构（约定）

每本书是一个独立项目，位于 `/var/minis/books/{bookId}/`：

```
/var/minis/books/{bookId}/
  book.json              — 元数据
  outline.md             — 两级大纲（剧情单元 + 节拍）
  chapters/ch001.md      — 章节正文（Markdown）
  characters/            — 角色卡
  worldview/             — 世界观设定
  notes/                 — 笔记（伏笔/灵感/时间线）
  summaries/             — 章节摘要
```

## 可用工具

| 工具 | 用途 |
|------|------|
| `book_list_chapters` | 列出所有章节及字数 |
| `book_read_chapter(num)` | 读指定章节正文 |
| `book_write_chapter(num, content, append)` | 写/续写章节 |
| `book_edit_chapter(num, find, replace, replaceAll)` | 修改章节 |
| `book_read_outline` / `book_write_outline` | 大纲读写 |
| `book_reference(type, op, name, content)` | 角色/世界观/笔记 CRUD |
| `book_get_context(chapterNum)` | 获取写作上下文（前文 + 目录） |
| `book_search(query)` | 全文检索（关键词/伏笔） |
| `book_load_skill(name)` | 加载写作技能 |

**通用工具**（也可用）：
- `file_read` / `file_write` / `file_edit` — 直接操作文件
- `shell_execute` — 执行 Linux 命令（用 `rg` 搜索、用 `git` 做版本控制、用 `pandoc` 导出）

## 标准创作工作流

### 阶段 0：启动
1. `book_list_chapters` — 了解当前进度
2. `book_read_outline` — 看大纲结构
3. `book_load_skill("novel-writing")` — 确认本 Skill 已加载

### 阶段 1：规划
**两级大纲**（参考 OpenFic 设计）：
- **剧情单元**（Story Unit）：跨多个章节的故事块
  - 每个单元包含：目标（Goal）、冲突（Conflict）、结果（Result）、变化（Change）
  - 一个单元可以覆盖 5–20 章
- **节拍**（Beat）：最小写作单位
  - 节拍是具体情节节点，按顺序推进
  - 写作时以节拍为单位，不要求一次写完整章

写大纲用 `book_write_outline`，遵循上述结构。

**角色卡**：用 `book_reference(type="characters", op="write", name, content)` 创建。
每张角色卡包含：
- 姓名、身份、核心欲望
- 性格特点、口头禅、习惯
- 与其他角色的关系

**世界观设定**：用 `book_reference(type="worldview", ...)` 管理。

### 阶段 2：写作
1. 先调 `book_get_context(chapterNum)` 获得完整上下文
2. 用 `book_read_chapter(前几章)` 保持行文风格一致
3. 用 `book_write_chapter(num, content)` 写新章
4. 写完或稍后调 `book_edit_chapter` 润色

### 阶段 3：连续性检查
每写 3–5 章做一次：
1. `book_list_chapters` — 整体进度
2. `book_search(角色名)` — 检查人物出场是否一致
3. `book_get_context` — 回顾全局
4. `book_reference(type="notes", op="list")` — 检查伏笔是否呼应

### 格式规范
- 章节第一行用 `# 第X章 标题` 作为章名
- 段落之间空一行
- 对话用 `"对话内容"`（中文全角引号）
- 动作描写和对话分行写
- 每章末尾留钩子（悬念/转折）

## 后命名章节约定
创建新章时先用占位标题（如 `第12章`），待整个剧情单元完成后，再统一回填正式标题。避免写作过程中标题剧透或重复。
