package cn.crabc.core.datasource.driver.jdbc;

import cn.crabc.core.datasource.config.JdbcDataSourceRouter;
import cn.crabc.core.spi.DataSourceDriver;
import cn.crabc.core.spi.bean.BaseDataSource;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.util.JdbcConstants;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 默认通用方法实现类
 *
 * @author yuqf
 */
public abstract class DefaultDataSourceDriver implements DataSourceDriver {
    private static final Logger log = LoggerFactory.getLogger(DefaultDataSourceDriver.class);

    @Override
    public String getName() {
        return "jdbc";
    }

    @Override
    public String test(BaseDataSource baseDataSource) {
        try (HikariDataSource dataSource = createHikariDataSource(baseDataSource, true)) {
            try (Connection connection = dataSource.getConnection()) {
                return "1";
            }
        } catch (Exception e) {
            Throwable cause = e.getCause();
            log.error("数据库测试异常：{}", e.getMessage());
            return cause == null ? e.getMessage() : cause.getLocalizedMessage();
        }
    }

    @Override
    public void init(BaseDataSource ds) {
        String datasourceId = ds.getDatasourceId();
        DataSource oldDataSource = JdbcDataSourceRouter.exist(datasourceId) ? 
                                 JdbcDataSourceRouter.getDataSource(datasourceId) : null;

        HikariDataSource dataSource = createHikariDataSource(ds, false);
        JdbcDataSourceRouter.setDataSource(datasourceId, dataSource);
        
        destroyOldDataSource(oldDataSource);
    }

    @Override
    public void destroy(String dataSourceId) {
        JdbcDataSourceRouter.destroy(dataSourceId);
    }

    private HikariDataSource createHikariDataSource(BaseDataSource ds, boolean isTest) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setUsername(ds.getUsername());
        dataSource.setPassword(ds.getPassword());
        dataSource.setJdbcUrl(ds.getJdbcUrl());
        
        if (isTest) {
            dataSource.setInitializationFailTimeout(1);
            dataSource.setConnectionTimeout(2000);
        } else {
            dataSource.setMinimumIdle(2);
            dataSource.setMaximumPoolSize(20);
            dataSource.setMaxLifetime(900000);
            dataSource.setIdleTimeout(300000);
            dataSource.setConnectionTimeout(10000);
            dataSource.setKeepaliveTime(300000);
        }
        
        setDriverClass(dataSource, ds.getDatasourceType());
        return dataSource;
    }

    private void destroyOldDataSource(DataSource oldDataSource) {
        if (oldDataSource == null) {
            return;
        }
        
        if (oldDataSource instanceof DruidDataSource) {
            ((DruidDataSource) oldDataSource).close();
        } else if (oldDataSource instanceof HikariDataSource) {
            ((HikariDataSource) oldDataSource).close();
        }
    }

    /**
     * 加载特殊驱动
     */
    private void setDriverClass(HikariDataSource dataSource, String datasourceType) {
        if (datasourceType == null) {
            return;
        }
        
        switch (datasourceType.toLowerCase()) {
            case "dm":
                dataSource.setDriverClassName(JdbcConstants.DM_DRIVER);
                break;
            case "xugu":
                dataSource.setDriverClassName(JdbcConstants.XUGU_DRIVER);
                break;
            case "oceanbase":
                dataSource.setDriverClassName(JdbcConstants.OCEANBASE_DRIVER2);
                break;
            default:
                if (datasourceType.startsWith("gbase8")) {
                    dataSource.setDriverClassName(JdbcConstants.GBASE_DRIVER);
                } else if (datasourceType.startsWith("kingbase8")) {
                    dataSource.setDriverClassName(JdbcConstants.KINGBASE8_DRIVER);
                } else if (dataSource.getJdbcUrl().toLowerCase().startsWith("jdbc:jtds:")) {
                    dataSource.setConnectionTestQuery("SELECT 1");
                    dataSource.setDriverClassName("net.sourceforge.jtds.jdbc.Driver");
                }
        }
    }
}
