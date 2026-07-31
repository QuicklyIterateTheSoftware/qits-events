package eu.wohlben.qits.events.control;

import eu.wohlben.qits.events.persistence.EventRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for events control-layer tests: wipes the table before each test so every case starts from an
 * empty log. Runs against in-memory H2 (see src/test/resources/application.properties) — no docker,
 * no auth variant, nothing on PATH.
 */
public abstract class EventsTestSupport {

  @Inject EventRepository eventRepository;

  @BeforeEach
  void wipe() {
    QuarkusTransaction.requiringNew().run(() -> eventRepository.deleteAll());
  }

  /**
   * Runs {@code assertion} inside a fresh transaction. Direct control-layer tests call several
   * transactional services on one thread with no request scope; a prior non-transactional read can
   * leave a thread-bound session whose first-level cache masks a later committed delete. Wrapping
   * the "is it gone?" assertion in a new transaction forces a fresh session so it reflects DB truth
   * (a @QuarkusTest artifact only — real HTTP requests each get their own session).
   */
  protected static void inFreshTx(Runnable assertion) {
    QuarkusTransaction.requiringNew().run(assertion);
  }
}
