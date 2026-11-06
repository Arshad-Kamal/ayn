// ADDED (YouTube KE, 2026-10-23): the MWEB client a made-for-kids video resolves through (spec 12
// KP-D29). See THIRD-PARTY.md entry 1.
package org.schabi.newpipe.extractor.services.youtube;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * The MWEB (mobile web, as an iPad) InnerTube client, which answers made-for-kids videos that
 * the VISIONOS client refuses, and what one stream extraction sent with it: the client version
 * and the web {@code poToken} of its player request. A SABR stream for that answer must speak as
 * the same client and send the same token.
 *
 * <p>
 * The constants are the one home of the MWEB identity, for the player request and the SABR
 * session alike.
 * </p>
 */
public final class YoutubeMwebClient {

    public static final int CLIENT_ID = 2;
    public static final String CLIENT_NAME = "MWEB";
    public static final String DEVICE_MAKE = "Apple";
    public static final String DEVICE_MODEL = "iPad";
    public static final String OS_NAME = "iPadOS";
    public static final String OS_VERSION = "16.7.10";
    public static final String USER_AGENT = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 "
            + "Safari/604.1,gzip(gfe)";

    /** The client version the player request sent. */
    @Nonnull
    public final String clientVersion;

    /** The web {@code poToken} the player request carried (base64url). */
    @Nonnull
    public final String poToken;

    public YoutubeMwebClient(@Nonnull final String clientVersion, @Nonnull final String poToken) {
        this.clientVersion = Objects.requireNonNull(clientVersion);
        this.poToken = Objects.requireNonNull(poToken);
    }
}
