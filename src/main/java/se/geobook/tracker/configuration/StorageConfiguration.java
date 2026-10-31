package se.geobook.tracker.configuration;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;

@Configuration(proxyBeanMethods = false)
class StorageConfiguration {
  @Bean
  DataSource dataSource(@Value("${geobook.data-directory:./data}") String directory)
      throws IOException {
    final Path root = Files.createDirectories(Path.of(directory).toAbsolutePath());
    SQLiteConfig sqlite = new SQLiteConfig();
    sqlite.enforceForeignKeys(true);
    sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
    sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
    sqlite.setBusyTimeout(5000);
    // Transactions take the writer lock up front, so a concurrent writer makes them wait
    // instead of failing when a read inside them would have to upgrade.
    sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    HikariDataSource pool = new HikariDataSource();
    pool.setJdbcUrl("jdbc:sqlite:" + root.resolve("geobook.db"));
    pool.setDataSourceProperties(sqlite.toProperties());
    pool.setMaximumPoolSize(4);
    return pool;
  }
}
