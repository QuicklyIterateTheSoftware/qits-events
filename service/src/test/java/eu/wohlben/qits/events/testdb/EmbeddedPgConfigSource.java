package eu.wohlben.qits.events.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply: {@code jdbc.url}, {@code username}, {@code password}.
 *
 * <p>It is a config source rather than three lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time — the instance
 * takes a free one, so nothing can be written down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over both the shipped defaults
 * in the events jar and anything the test properties file might carry, and it is registered through
 * {@code META-INF/services}, which is how a config source joins a Quarkus application without being
 * a bean.
 *
 * <p>It reaches the {@code @QuarkusTest}s only. The packaged-artifact ITs launch another process
 * with another classpath, so they hand that process the {@code QITS_RESOURCE_DB_*} triple instead —
 * which is the point there: what is under test is the shipped expression itself.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /** This module's database on the shared instance — {@code events} names its own. */
  private static final String DATABASE = "events_svc";

  private static final String PREFIX = "quarkus.datasource.events.";

  private final Map<String, String> values =
      Map.of(
          PREFIX + "jdbc.url", EmbeddedPg.url(DATABASE),
          PREFIX + "username", EmbeddedPg.USER,
          PREFIX + "password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
