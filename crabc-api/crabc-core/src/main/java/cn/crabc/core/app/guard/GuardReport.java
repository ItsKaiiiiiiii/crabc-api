package cn.crabc.core.app.guard;

import java.util.ArrayList;
import java.util.List;

/**
 * 四道闸综合报告（chatView：产物留痕 + 埋点四道闸拦截率的数据来源）
 *
 * @author chatview
 */
public class GuardReport {

    private final List<GateResult> results = new ArrayList<>();
    private final List<String> tables = new ArrayList<>();
    /** ③ 道闸产物：LIMIT 沙箱包裹后的可执行 SQL */
    private String sandboxedSql;

    public void add(GateResult result) {
        results.add(result);
    }

    public void setTables(List<String> tableNames) {
        tables.clear();
        if (tableNames != null) {
            tables.addAll(tableNames);
        }
    }

    public void setSandboxedSql(String sql) {
        this.sandboxedSql = sql;
    }

    public boolean allPassed() {
        return results.stream().allMatch(GateResult::isPassed);
    }

    /** 第一道被拒的闸名（埋点定位弱环节用） */
    public String firstRejectedGate() {
        return results.stream().filter(r -> !r.isPassed()).map(GateResult::getGate).findFirst().orElse(null);
    }

    public String firstRejectMessage() {
        return results.stream().filter(r -> !r.isPassed()).map(GateResult::getMessage).findFirst().orElse(null);
    }

    public List<GateResult> getResults() {
        return results;
    }

    public List<String> getTables() {
        return tables;
    }

    public String getSandboxedSql() {
        return sandboxedSql;
    }

    @Override
    public String toString() {
        return "GuardReport{" + results + ", tables=" + tables + "}";
    }
}
