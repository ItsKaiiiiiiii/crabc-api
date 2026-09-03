package cn.crabc.core.agent.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 图表 DSL 服务端组装器（chatView/docs/05 §10）
 *
 * 原则：模型只提供扁平参数，DSL 由这里确定性组装——schema 保证合法；
 * 编码类型推断在此完成并写回 DSL，运行时零推断；table/metric 类型 x/y 可空。
 *
 * @author chatview
 */
@Component
public class ChartDslAssembler {

    private static final Set<String> CHART_TYPES = Set.of("bar", "line", "pie", "table", "metric");

    public Map<String, Object> assemble(String chartType, String title,
                                        String xField, String yField, String seriesField) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = chartType == null ? "" : chartType.trim().toLowerCase();
        if (!CHART_TYPES.contains(type)) {
            out.put("error", "chart_type 必须是 " + CHART_TYPES + "，实际：" + chartType);
            return out;
        }
        if (title == null || title.isBlank()) {
            out.put("error", "title 不能为空（需含口径说明）");
            return out;
        }
        boolean needsAxis = !"table".equals(type) && !"metric".equals(type);
        if (needsAxis && (isBlank(xField) || isBlank(yField))) {
            out.put("error", type + " 类型必须提供 x_field 与 y_field");
            return out;
        }
        if ("metric".equals(type) && isBlank(yField)) {
            out.put("error", "metric（数值卡）必须提供 y_field");
            return out;
        }

        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("version", "1");
        dsl.put("chartType", type);
        dsl.put("title", title.trim());

        Map<String, Object> encoding = new LinkedHashMap<>();
        if (!isBlank(xField)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("field", xField.trim());
            x.put("type", "temporal".equalsIgnoreCase(guessType(xField)) ? "temporal" : "nominal");
            x.put("label", xField.trim());
            encoding.put("x", x);
        }
        if (!isBlank(yField)) {
            Map<String, Object> y = new LinkedHashMap<>();
            y.put("field", yField.trim());
            y.put("type", "quantitative");
            y.put("label", yField.trim());
            encoding.put("y", y);
        }
        if (!isBlank(seriesField)) {
            Map<String, Object> series = new LinkedHashMap<>();
            series.put("field", seriesField.trim());
            series.put("type", "nominal");
            series.put("label", seriesField.trim());
            encoding.put("series", series);
        }
        dsl.put("encoding", encoding);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("stack", false);
        options.put("showLegend", seriesField != null && !seriesField.isBlank());
        options.put("nullStrategy", "break"); // 断线/补零显式化（M1 默认断线）
        dsl.put("options", options);

        out.putAll(dsl);
        return out;
    }

    /** 极简类型推断：列名含日期/时间语义 → temporal，其余 nominal（Y 轴恒 quantitative） */
    private String guessType(String field) {
        String f = field.toLowerCase();
        if (f.contains("date") || f.contains("time") || f.endsWith("_at") || f.endsWith("_day")
                || f.endsWith("_week") || f.endsWith("_month")) {
            return "temporal";
        }
        return "nominal";
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
