package eu.wohlben.qits.events.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Completes {@link ForwardAuthMechanism}'s trusted request into a {@link SecurityIdentity}: the
 * principal is the header-supplied username, and that is all.
 *
 * <p><b>No roles, deliberately.</b> Authorization is a single global check
 * ({@code qits.auth.required-role}) performed at qits-gateway, which therefore emits no groups
 * header — one fewer trusted header, and it keeps "the gateway terminates auth entirely" literally
 * true. No code in this service makes a role decision, so roles here would be a security control
 * that decides nothing.
 *
 * <p>Do not add them to "match" anything. If a per-resource role decision is ever needed here, that
 * is a new design (scoped tokens), not this class growing a header.
 *
 * <p>The principal is the <b>name</b>, not a stable subject id, for the same reason its siblings
 * use the name: it is what gets written into a "who did this" column, and mixing the two kinds of
 * value in one column leaves nothing to tell them apart.
 */
@ApplicationScoped
public class ForwardAuthIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

  @Override
  public Class<TrustedAuthenticationRequest> getRequestType() {
    return TrustedAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
    return Uni.createFrom()
        .item(
            QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(request.getPrincipal()))
                .build());
  }
}
