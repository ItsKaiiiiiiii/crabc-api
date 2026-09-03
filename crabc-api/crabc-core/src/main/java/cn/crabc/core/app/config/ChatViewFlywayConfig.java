package cn.crabc.core.app.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * chatView schema 演进：Flyway 装配
 *
 * 设计要点（chatView/docs/05 §3.3）：
 * 1. 【绝不经路由数据源】系统库迁移使用独立的 url/user/password 直连，
 *    避免误把迁移脚本跑到路由出去的业务库上；
 * 2. V1 基线 = crabc 原 mysql.sql 原样（历史存量库通过 baselineOnMigrate 跳过 V1）；
 * 3. 之后所有 schema 演进只允许新增 V*.sql，禁止再改 db/mysql.sql。
 *
 * @author chatview
 */
@Configuration
public class ChatViewFlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatViewFlywayConfig.class);

    @Value("${spring.datasource.url}")
    private String jdbcUrl;
    @Value("${spring.datasource.username}")
    private String username;
    @Value("${spring.datasource.password}")
    private String password;

    @Value("${crabc.flyway.enabled:true}")
    private boolean enabled;

    @Bean(initMethod = "migrate")
    public Flyway chatViewFlyway() {
        if (!enabled) {
            log.warn("[chatview] Flyway 已关闭（crabc.flyway.enabled=false），schema 演进需手动执行 db/flyway 下脚本");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/flyway")
                // 存量库（已按 mysql.sql 建表）：baseline 到 V1，V2 起正常执行
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .outOfOrder(false)
                .load();
        log.info("[chatview] Flyway 迁移配置完成，目标库：{}", jdbcUrl);
        return flyway;
    }
}
