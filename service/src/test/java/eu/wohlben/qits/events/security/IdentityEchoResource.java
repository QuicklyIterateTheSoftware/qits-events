package eu.wohlben.qits.events.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Test-only: reports what the request resolved to, so the header contract can be asserted directly
 * rather than inferred from something that happens to write a row.
 *
 * <p>Served under {@code /events/api/test-identity} — the suite inherits the shipped {@code
 * quarkus.rest.path=/events/api}, so this fixture is addressed exactly as a real route is.
 *
 * <p>Hidden from the OpenAPI document: a {@code @QuarkusTest} that generates the document indexes
 * the test classpath too, and a fixture published there would grow a method in any client generated
 * from it.
 */
@Path("/test-identity")
@Produces(MediaType.APPLICATION_JSON)
public class IdentityEchoResource {

  @Inject SecurityIdentity identity;

  public record Identity(boolean anonymous, String principal, Set<String> roles) {}

  @GET
  @Operation(hidden = true)
  public Identity get() {
    return new Identity(
        identity.isAnonymous(),
        identity.getPrincipal() == null ? null : identity.getPrincipal().getName(),
        identity.getRoles());
  }
}
