# CrudTool

CrudTool 是一个免费的 IntelliJ IDEA 插件,面向 Spring Cloud & MyBatis 开发,提供 URL 复制、Mapper 导航、路由搜索、SQL 格式化和实体类生成能力。

## 功能

### 1. 复制 Controller 完整 URL
在带 Restful 注解(`@GetMapping`、`@PostMapping`、`@RequestMapping` 等)的 Controller 方法行号栏上会出现剪贴板图标,点击即可把完整请求 URL 复制到剪贴板——包含 Spring 配置中的 `server.servlet.context-path` 和 `spring.mvc.servlet.path` 前缀。

### 2. Mapper 双向导航
- 在 Mapper 接口方法名上 **Ctrl+Alt+Click** 跳转到对应的 XML 语句(`<select>` / `<insert>` / `<update>` / `<delete>`)。
- 在 XML 语句的 `id` 上 **Ctrl+Alt+Click** 跳回 Mapper 接口方法。
- 在 Mapper 方法或 XML 语句 `id` 上 **Ctrl+Click** 跳转到工程内的调用处(引用列表)。
- 行号栏图标同样支持 Java 方法 ↔ XML 语句互跳,以及 XML 中 `resultMap` 属性值 → `<resultMap>` 定义的跳转。

### 3. 路由搜索
按 **Ctrl+\\** 弹出搜索窗口,列出工程内所有 Controller 路由。输入路由关键字实时过滤(支持模糊匹配),按 **回车** 或双击跳转到对应的 Controller 方法。

### 4. 格式化 Mapper SQL
在 MyBatis mapper XML 语句标签内按 **Ctrl+Alt+F** 格式化其中的 SQL。支持动态标签(`<if>`、`<where>`、`<set>`、`<foreach>` 等),格式化时保持 XML 结构不变。

### 5. 数据库表生成实体类
在 Database 工具窗口右键一个或多个表,选择 **Entity Generator → Generate Entity to Desktop**,即可在桌面生成对应的 Java 实体类:
- 表名 / 列名下划线转驼峰(`bank_card` → `BankCard`)
- SQL 类型映射为 Java 类型(`BIGINT` → `Long`、`DECIMAL` → `BigDecimal`、`TIMESTAMP` → `LocalDateTime` 等)
- 表注释生成类 Javadoc,列注释以 `// 注释` 跟在字段后

注解选项(如 `@Data`、`@AllArgsConstructor`、`@NoArgsConstructor`)可在 **Settings → Tools → CrudTool** 中按项目配置。配置保存在项目 `.idea/workspace.xml` 中,各项目互相独立。

## 环境要求

- IntelliJ IDEA 2026.x(build 252+)
- JDK 25(仅源码构建时需要)
- 实体类生成功能依赖带 Database 插件的 IDE(如 IntelliJ IDEA Ultimate);其余功能不受限制

## 安装

从 [Releases](https://github.com/ukpkmkk512/CrudTool/releases) 下载最新的 `crud-tool-x.xx.zip`,在 IDEA 中进入 **Settings → Plugins → ⚙ → Install Plugin from Disk...** 选择该 zip,重启 IDE 即可。

## 源码构建

```bash
.\gradlew.bat buildPlugin
```

插件 zip 生成在 `build/distributions/` 目录下。

注意:构建使用本地 IDE 作为 SDK(见 `build.gradle.kts` 中的 `local(...)` 和 `compileOnly` 路径),请按你的本机环境调整。

## License

MIT
