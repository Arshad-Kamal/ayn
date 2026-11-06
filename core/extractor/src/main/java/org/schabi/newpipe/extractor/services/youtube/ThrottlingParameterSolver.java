// ADDED (YouTube KE, 2026-10-23): a solver seam for the throttling parameter (spec 12 KP-D30).
// See THIRD-PARTY.md entry 1.
package org.schabi.newpipe.extractor.services.youtube;

import javax.annotation.Nonnull;

/**
 * Solves the throttling parameter ({@code n}) of streaming URLs against the base JavaScript
 * player's code, in place of the extractor's own Rhino extraction, when installed with
 * {@link YoutubeJavaScriptPlayerManager#setThrottlingParameterSolver(ThrottlingParameterSolver)}.
 *
 * <p>
 * <b>Implementations are blocking and must be thread-safe.</b>
 * </p>
 */
public interface ThrottlingParameterSolver {

    /**
     * Readies the solver for {@code playerCode}, so that a later {@link #solve(String, String)}
     * against the same code is fast.
     *
     * @param playerCode the whole base JavaScript player code
     * @throws Exception if the solver can't use the player code
     */
    void prepare(@Nonnull String playerCode) throws Exception;

    /**
     * @param playerCode the whole base JavaScript player code
     * @param challenge  the obfuscated throttling parameter of a streaming URL
     * @return the deobfuscated throttling parameter
     * @throws Exception if the solver has no answer
     */
    @Nonnull
    String solve(@Nonnull String playerCode, @Nonnull String challenge) throws Exception;
}
