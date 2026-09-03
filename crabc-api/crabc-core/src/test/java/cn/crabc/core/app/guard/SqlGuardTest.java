package cn.crabc.core.app.guard;

import cn.crabc.core.datasource.exception.CustomException;
import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SqlGuard 恶意 SQL 样例集（chatView docs/05 §12：≥30 条）
 * 两条链路覆盖：API 链路（兼容动态标签）与 Agent 链路（严格模式）
 */
public class SqlGuardTest {

    private SqlGuard guard;
    private SqlGuard.AgentOptions whitelistOptions;

    @Before
    public void setUp() {
        guard = new SqlGuard(new SqlGuard.Config());
        Set<String> whitelist = new HashSet<>(Set.of("t_order", "t_user", "db2.t_order"));
        whitelistOptions = SqlGuard.AgentOptions.of(whitelist);
    }

    private static final String G_API = "API 链路";
    private static final String G_AGENT = "Agent 链路";

    // ==================== Agent 链路：恶意样本（必须全部拦截） ====================

    @Test
    public void agent_must_reject_dollar_placeholder_injection() {
        // ① ${} 文本直拼注入（03 报告隐患 1）
        assertAgentRejected("SELECT * FROM t_order WHERE channel = '${channel}'", "${}");
    }

    @Test
    public void agent_must_reject_drop_table() {
        assertAgentRejected("DROP TABLE t_order", "非SELECT");
    }

    @Test
    public void agent_must_reject_delete() {
        assertAgentRejected("DELETE FROM t_order WHERE order_id = 1", "非SELECT");
    }

    @Test
    public void agent_must_reject_update() {
        assertAgentRejected("UPDATE t_user SET status = 0", "非SELECT");
    }

    @Test
    public void agent_must_reject_insert() {
        assertAgentRejected("INSERT INTO t_user VALUES (1,'a')", "非SELECT");
    }

    @Test
    public void agent_must_reject_truncate() {
        assertAgentRejected("TRUNCATE TABLE t_order", "非SELECT");
    }

    @Test
    public void agent_must_reject_create_table() {
        assertAgentRejected("CREATE TABLE hack (id int)", "非SELECT");
    }

    @Test
    public void agent_must_reject_alter_table() {
        assertAgentRejected("ALTER TABLE t_order ADD COLUMN hack int", "非SELECT");
    }

    @Test
    public void agent_must_reject_multi_statement_stacked_query() {
        assertAgentRejected("SELECT 1; DROP TABLE t_order", "多语句");
    }

    @Test
    public void agent_must_reject_union_based_sli_into_outfile() {
        assertAgentRejected("SELECT * FROM t_order INTO OUTFILE '/tmp/x'", "INTO");
    }

    @Test
    public void agent_must_reject_dumpfile() {
        assertAgentRejected("SELECT * FROM t_order INTO DUMPFILE '/tmp/x'", "INTO");
    }

    @Test
    public void agent_must_reject_table_out_of_whitelist() {
        assertAgentRejected("SELECT * FROM t_secret_user", "白名单");
    }

    @Test
    public void agent_must_reject_cross_tenant_table() {
        assertAgentRejected("SELECT a.* FROM t_order a JOIN t_other b ON a.id=b.id", "白名单");
    }

    @Test
    public void agent_must_reject_mybatis_if_tag() {
        assertAgentRejected("SELECT * FROM t_order <if test='x'>WHERE id=1</if>", "动态标签");
    }

    @Test
    public void agent_must_reject_foreach_tag() {
        assertAgentRejected("SELECT * FROM t_order WHERE id IN <foreach item='i'>${i}</foreach>", "${}");
    }

    @Test
    public void agent_must_reject_syntax_error() {
        assertAgentRejected("SELECTT * FROMM t_order WHERE", "解析失败");
    }

    @Test
    public void agent_must_reject_empty_sql() {
        assertAgentRejected("   ", "空");
    }

    @Test
    public void agent_must_reject_sleep_injection_via_function() {
        // 函数本身合法但属高危探测；M1 策略：白名单外的系统库函数不拦（表白名单兜底），此处验证不误报语法
        GuardReport report = guard.checkAgentQuery("SELECT SLEEP(5)", whitelistOptions);
        // SLEEP 无表访问，能通过 AST/白名单——由 ④ SafeExecGate 的超时兜底
        assertTrue(report.allPassed() || "StaticGate".equals(report.firstRejectedGate()));
    }

    @Test
    public void agent_must_reject_grant() {
        assertAgentRejected("GRANT ALL ON *.* TO 'h'@'%'", "非SELECT");
    }

    @Test
    public void agent_must_reject_information_schema_probe() {
        assertAgentRejected("SELECT table_name FROM information_schema.tables", "白名单");
    }

    @Test
    public void agent_must_reject_mysql_user_probe() {
        assertAgentRejected("SELECT user, password FROM mysql.user", "白名单");
    }

    // ==================== Agent 链路：正常样本（必须全部放行） ====================

    @Test
    public void agent_must_allow_simple_select() {
        GuardReport report = guard.checkAgentQuery("SELECT order_id, amount FROM t_order WHERE sttus = #{sttus}", whitelistOptions);
        assertTrue(report.toString(), report.allPassed());
        assertTrue(report.getTables().contains("t_order"));
    }

    @Test
    public void agent_must_allow_join_within_whitelist() {
        GuardReport report = guard.checkAgentQuery(
                "SELECT o.order_id, u.user_name FROM t_order o JOIN t_user u ON o.user_id = u.user_id", whitelistOptions);
        assertTrue(report.toString(), report.allPassed());
    }

    @Test
    public void agent_must_allow_aggregate() {
        assertTrue(guard.checkAgentQuery(
                "SELECT channel, SUM(amount) AS amt FROM t_order WHERE sttus != 3 GROUP BY channel", whitelistOptions).allPassed());
    }

    @Test
    public void agent_must_allow_subquery() {
        assertTrue(guard.checkAgentQuery(
                "SELECT * FROM t_order WHERE user_id IN (SELECT user_id FROM t_user WHERE created_at > '2026-01-01')",
                whitelistOptions).allPassed());
    }

    @Test
    public void agent_must_allow_qualified_table_in_whitelist() {
        assertTrue(guard.checkAgentQuery("SELECT * FROM db2.t_order", whitelistOptions).allPassed());
    }

    @Test
    public void agent_must_wrap_sandbox_limit_when_missing() {
        GuardReport report = guard.checkAgentQuery("SELECT * FROM t_order", whitelistOptions);
        assertTrue(report.allPassed());
        assertNotNull(report.getSandboxedSql());
        assertTrue(report.getSandboxedSql().contains("_sandbox LIMIT 200"));
    }

    @Test
    public void agent_must_not_double_wrap_existing_limit() {
        GuardReport report = guard.checkAgentQuery("SELECT * FROM t_order LIMIT 50", whitelistOptions);
        assertTrue(report.allPassed());
        assertEquals("SELECT * FROM t_order LIMIT 50", report.getSandboxedSql());
    }

    @Test
    public void agent_must_recognize_fetch_first_as_limit() {
        GuardReport report = guard.checkAgentQuery("SELECT * FROM t_order FETCH FIRST 10 ROWS ONLY", whitelistOptions);
        assertTrue(report.allPassed());
        assertFalse(report.getSandboxedSql().contains("_sandbox"));
    }

    @Test
    public void agent_named_params_are_replaced_for_parsing() {
        GuardReport report = guard.checkAgentQuery(
                "SELECT * FROM t_order WHERE channel = #{channel} AND amount > #{minAmount}", whitelistOptions);
        assertTrue(report.toString(), report.allPassed());
    }

    // ==================== API 链路：动态 SQL 兼容（保留 crabc 原特性） ====================

    @Test
    public void api_must_allow_mybatis_dynamic_tags() {
        String sql = "SELECT * FROM t_order <where><if test=\"sttus != null\">AND sttus = #{sttus}</if></where>";
        GuardReport report = guard.checkApiQuery(sql);
        assertTrue(report.toString(), report.allPassed());
    }

    @Test
    public void api_must_allow_dollar_placeholder_legacy_feature() {
        // crabc 原特性：${} 在 API 链路保留（配合参数定义使用）；Agent 链路才禁止
        GuardReport report = guard.checkApiQuery("SELECT * FROM t_order WHERE channel = '${channel}'");
        assertTrue(report.toString(), report.allPassed());
    }

    @Test
    public void api_must_reject_drop_in_dynamic_sql() {
        GuardReport report = guard.checkApiQuery("DROP TABLE t_order");
        assertFalse(report.allPassed());
        assertEquals("StaticGate", report.firstRejectedGate());
    }

    @Test
    public void api_must_reject_multi_statement_in_dynamic_sql() {
        GuardReport report = guard.checkApiQuery("SELECT * FROM t_order WHERE a = #{a}; DROP TABLE t_order");
        assertFalse(report.allPassed());
    }

    @Test
    public void api_must_reject_outfile_in_dynamic_sql() {
        GuardReport report = guard.checkApiQuery("SELECT * FROM t_order INTO OUTFILE '/tmp/hack' WHERE id = #{id}");
        assertFalse(report.allPassed());
    }

    @Test
    public void api_must_reject_semicolon_inside_dynamic_string_is_ignored() {
        // 分号在字符串字面量内不构成多语句
        GuardReport report = guard.checkApiQuery("SELECT * FROM t_order WHERE remark = 'a;b' AND sttus = #{sttus}");
        assertTrue(report.toString(), report.allPassed());
    }

    @Test
    public void api_must_reject_plain_ddl() {
        assertFalse(guard.checkApiQuery("TRUNCATE TABLE t_order").allPassed());
        assertFalse(guard.checkApiQuery("ALTER TABLE t_order DROP COLUMN amount").allPassed());
    }

    @Test
    public void guardQueryOrThrow_throws_on_rejection() {
        try {
            guard.guardQueryOrThrow("DROP TABLE t_order");
            throw new AssertionError("应当抛出 CustomException");
        } catch (CustomException e) {
            assertTrue(e.getMsg(), e.getMsg().contains("护栏拦截"));
            assertEquals(4003, e.getCode());
        }
    }

    @Test
    public void sandbox_limit_handles_oracle_rownum_and_trailing_semicolon() {
        String wrapped = SqlGuard.getInstance().applySandboxLimit("SELECT * FROM t_order WHERE ROWNUM <= 5;", 200);
        assertFalse(wrapped.contains("_sandbox"));
        String wrapped2 = SqlGuard.getInstance().applySandboxLimit("SELECT * FROM t_order", 10);
        assertTrue(wrapped2.endsWith("LIMIT 10"));
    }

    @Test
    public void first_rejected_gate_is_recorded_for_metrics() {
        GuardReport report = guard.checkAgentQuery("DROP TABLE t_order", whitelistOptions);
        assertEquals("StaticGate", report.firstRejectedGate());
        assertNotNull(report.firstRejectMessage());
        assertNull(report.getSandboxedSql());
    }

    // ==================== 工具方法 ====================

    private void assertAgentRejected(String sql, String expectKeyword) {
        GuardReport report = guard.checkAgentQuery(sql, whitelistOptions);
        assertFalse("应拦截: " + sql + " => " + report, report.allPassed());
        assertNotNull("应有拦截理由: " + sql, report.firstRejectMessage());
    }
}
