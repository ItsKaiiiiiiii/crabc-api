package cn.crabc.core.app.guard;

import cn.crabc.core.datasource.exception.CustomException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SqlGuard —— chatView 统一 SQL 护栏（四道闸，一道防线）
 *
 * 设计：chatView/docs/05 §9。创作时（Agent 链路）与运行时（crabc API 链路）共用同一组闸门。
 *
 * ① AstGate     JSqlParser 解析，语法错误即拒（错误信息回喂 LLM 自纠）
 * ② StaticGate  仅 SELECT；禁 INTO OUTFILE/DUMPFILE；禁多语句；表级白名单（可选）
 * ③ DryRunGate  LIMIT 沙箱包裹（无 LIMIT 时包一层，方言可配），跨方言默认实现
 * ④ SafeExecGate 行数上限/超时配置（执行器读取本配置实施）
 *
 * 两种模式：
 * - API 链路（guardQueryOrThrow）：保留 crabc 原有 MyBatis 动态标签特性——含 #{} / ${} / 标签的 SQL
 *   无法 AST 解析，降级为正则静态校验；
 * - Agent 链路（checkAgentQuery）：严格模式——禁 ${}、禁动态标签，#{} 命名参数替换为 NULL 后全量 AST 校验，
 *   并强制 LIMIT 沙箱包裹。
 *
 * @author chatview
 */
public final class SqlGuard {

    /** ④ SafeExecGate 配置 */
    public static class Config {
        private int maxRows = 200;
        private int timeoutSeconds = 30;
        private boolean allowDmlInApiChain = true;

        public int getMaxRows() {
            return maxRows;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public boolean isAllowDmlInApiChain() {
            return allowDmlInApiChain;
        }
    }

    /** Agent 链路选项 */
    public static class AgentOptions {
        /** 租户授权 schema 白名单（小写表名，null=不限制） */
        private Set<String> allowedTables;
        /** 沙箱行数上限 */
        private int sandboxLimit = 200;

        public static AgentOptions of(Set<String> allowedTables) {
            AgentOptions o = new AgentOptions();
            o.allowedTables = allowedTables;
            return o;
        }

        public Set<String> getAllowedTables() {
            return allowedTables;
        }

        public int getSandboxLimit() {
            return sandboxLimit;
        }

        public AgentOptions sandboxLimit(int limit) {
            this.sandboxLimit = limit;
            return this;
        }
    }

    private static final SqlGuard INSTANCE = new SqlGuard(new Config());

    public static SqlGuard getInstance() {
        return INSTANCE;
    }

    private final Config config;

    public SqlGuard(Config config) {
        this.config = config;
    }

    public Config getConfig() {
        return config;
    }

    // ==================== API 链路 ====================

    /**
     * crabc API 链路的查询护栏：保留动态 SQL 特性，静态可校验的都校验。
     * 违规抛 CustomException（统一异常处理器负责转响应）。
     */
    public void guardQueryOrThrow(String sql) {
        GuardReport report = checkApiQuery(sql);
        if (!report.allPassed()) {
            throw new CustomException(4003, "SQL 护栏拦截[" + report.firstRejectedGate() + "]：" + report.firstRejectMessage());
        }
    }

    /**
     * API 链路校验（兼容 MyBatis 动态标签：含占位符/标签时降级正则校验）
     */
    public GuardReport checkApiQuery(String sql) {
        GuardReport report = new GuardReport();
        if (sql == null || sql.trim().isEmpty()) {
            report.add(GateResult.reject("AstGate", "SQL 为空"));
            return report;
        }
        if (containsMybatisDynamic(sql)) {
            // 动态 SQL：跳过 AST，正则静态校验（保留 crabc 原特性）
            report.add(GateResult.pass("AstGate"));
            report.add(staticRegexCheck(sql, report));
            return report;
        }
        return fullAstCheck(sql, null, false, 0, report);
    }

    // ==================== Agent 链路（严格模式） ====================

    /**
     * Agent 链路校验：禁 ${}、禁动态标签、#{} → NULL 后 AST 全量校验 + 白名单 + LIMIT 沙箱
     */
    public GuardReport checkAgentQuery(String sql, AgentOptions options) {
        GuardReport report = new GuardReport();
        if (sql == null || sql.trim().isEmpty()) {
            report.add(GateResult.reject("AstGate", "SQL 为空"));
            return report;
        }
        // Agent 链路硬规则：禁 ${} 文本直拼（03 报告隐患 1）
        if (sql.contains("${")) {
            report.add(GateResult.reject("StaticGate", "Agent 链路禁止 ${} 占位符，请使用 #{命名参数}"));
            return report;
        }
        if (containsMybatisTags(sql)) {
            report.add(GateResult.reject("StaticGate", "Agent 链路禁止 MyBatis 动态标签"));
            return report;
        }
        int limit = options == null ? 200 : options.getSandboxLimit();
        Set<String> whitelist = options == null ? null : options.getAllowedTables();
        return fullAstCheck(sql, whitelist, true, limit, report);
    }

    // ==================== 核心校验 ====================

    private GuardReport fullAstCheck(String sql, Set<String> whitelist, boolean sandboxWrap, int sandboxLimit, GuardReport report) {
        // Agent 链路：#{param} 预编译占位符替换为 NULL 以便 AST 解析（语义校验不受影响）
        String parseable = sandboxWrap ? sql.replaceAll("\\#\\{[^}]*}", "NULL") : sql;

        // ① AstGate + ② StaticGate
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(parseable);
            if (statements.size() > 1) {
                report.add(GateResult.reject("AstGate", "禁止多语句 SQL"));
                return report;
            }
            report.add(GateResult.pass("AstGate"));

            Statement statement = statements.get(0);
            if (!(statement instanceof Select)) {
                report.add(GateResult.reject("StaticGate", "仅允许 SELECT 查询，实际：" + statement.getClass().getSimpleName()));
                return report;
            }
            // SELECT ... INTO OUTFILE/DUMPFILE/表
            String into = extractIntoTarget(parseable);
            if (into != null) {
                report.add(GateResult.reject("StaticGate", "SELECT 不允许 INTO " + into));
                return report;
            }

            // 表提取 + 白名单
            List<String> tables = extractTables(statement);
            report.setTables(tables);
            if (whitelist != null && !whitelist.isEmpty()) {
                for (String table : tables) {
                    if (!whitelist.contains(table.toLowerCase(Locale.ROOT))) {
                        report.add(GateResult.reject("StaticGate", "表不在租户授权白名单内：" + table));
                        return report;
                    }
                }
            }
            report.add(GateResult.pass("StaticGate"));
        } catch (Exception e) {
            report.add(GateResult.reject("AstGate", "SQL 解析失败：" + rootMessage(e)));
            return report;
        }

        // ③ DryRunGate：LIMIT 沙箱包裹
        if (sandboxWrap) {
            report.setSandboxedSql(applySandboxLimit(sql, sandboxLimit));
            report.add(GateResult.pass("DryRunGate"));
        } else {
            report.add(GateResult.pass("DryRunGate"));
        }

        // ④ SafeExecGate：配置项由执行器实施，此处记录通过
        report.add(GateResult.pass("SafeExecGate"));
        return report;
    }

    /** 正则静态校验（动态 SQL 降级路径） */
    private GateResult staticRegexCheck(String sql, GuardReport report) {
        String stripped = stripLiterals(sql);
        String lower = stripped.toLowerCase(Locale.ROOT);
        // 多语句（引号外分号）
        if (stripped.contains(";")) {
            return GateResult.reject("StaticGate", "禁止多语句 SQL");
        }
        if (Pattern.compile("\\binto\\s+(outfile|dumpfile)\\b").matcher(lower).find()) {
            return GateResult.reject("StaticGate", "禁止 SELECT INTO OUTFILE/DUMPFILE");
        }
        // DDL/管理语句首词
        String first = lower.trim().split("[\\s(]+", 2)[0];
        Set<String> forbiddenFirst = Set.of("drop", "alter", "create", "truncate", "grant", "revoke",
                "set", "use", "lock", "unlock", "call", "handler", "load", "prepare", "execute");
        if (forbiddenFirst.contains(first)) {
            return GateResult.reject("StaticGate", "禁止执行 " + first.toUpperCase() + " 语句");
        }
        report.setTables(List.of());
        return GateResult.pass("StaticGate");
    }

    // ==================== 工具方法 ====================

    private List<String> extractTables(Statement statement) {
        try {
            return new ArrayList<>(new TablesNamesFinder().getTables(statement));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractIntoTarget(String sql) {
        java.util.regex.Matcher m = Pattern.compile("\\binto\\s+(outfile|dumpfile)\\s", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (m.find()) {
            return m.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * LIMIT 沙箱包裹（DataAgent 验证过的跨方言做法）：
     * 无 LIMIT 时包一层 SELECT * FROM (…) _sandbox LIMIT n
     */
    public String applySandboxLimit(String sql, int limit) {
        String trimmed = sql.trim().replaceAll(";\\s*$", "");
        String noComments = stripComments(trimmed);
        if (Pattern.compile("\\blimit\\s+\\d+", Pattern.CASE_INSENSITIVE).matcher(noComments).find()
                || Pattern.compile("\\bfetch\\s+(first|next)\\s+\\d+\\s+rows\\s+only", Pattern.CASE_INSENSITIVE).matcher(noComments).find()
                || Pattern.compile("\\brownum\\b", Pattern.CASE_INSENSITIVE).matcher(noComments).find()) {
            return trimmed;
        }
        return "SELECT * FROM (" + trimmed + ") AS _sandbox LIMIT " + limit;
    }

    private boolean containsMybatisDynamic(String sql) {
        return sql.contains("#{") || sql.contains("${") || containsMybatisTags(sql);
    }

    private boolean containsMybatisTags(String sql) {
        return Pattern.compile("</?(if|foreach|where|set|choose|when|otherwise|trim|bind)\\b", Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }

    /** 去掉字符串字面量（用于引号外检查） */
    private String stripLiterals(String sql) {
        return sql.replaceAll("'(?:[^']|'')*'", "''").replaceAll("\"(?:[^\"]|\"\")*\"", "\"\"");
    }

    private String stripComments(String sql) {
        return sql.replaceAll("--[^\n]*", " ").replaceAll("/\\*.*?\\*/", " ");
    }

    private String rootMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        return msg.split("\n")[0];
    }

    // ==================== 供测试与埋点 ====================

    public GuardReport check(String sql, AgentOptions options) {
        return checkAgentQuery(sql, options);
    }
}
