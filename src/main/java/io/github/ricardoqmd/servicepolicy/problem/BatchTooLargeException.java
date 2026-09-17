package io.github.ricardoqmd.servicepolicy.problem;

/**
 * A batch carrying more items than this deployment's cap allows (ADR-031).
 *
 * <p>This has its own code rather than sharing {@code BAD_REQUEST} with every other malformed
 * request, and the reason is what a caller can do about it. Every other input rejection on this
 * surface is a programming mistake: the caller sends a different request or it never works. This one
 * is not — a batch of a hundred items is correct against one deployment and refused by the next,
 * because the cap is configuration. A client that can recognise this rejection re-chunks and
 * succeeds; a client that cannot must either parse prose or treat a recoverable condition as a bug.
 *
 * <p>The cap that refused the request travels as an extension member rather than only inside the
 * message, so the caller reacts to a number rather than to a sentence. It is the cap <em>this</em>
 * rejection applied, which is not necessarily what a later call to the metadata endpoint would
 * report: configuration can change between two requests, and a client that mixed the two could
 * re-chunk to a size that is already stale.
 */
public class BatchTooLargeException extends ProblemException {

    private final int maxBatchSize;

    public BatchTooLargeException(int maxBatchSize) {
        super(400, "BATCH_TOO_LARGE", "'requests' must contain between 1 and " + maxBatchSize + " items.");
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public ProblemDetail toProblemDetail() {
        return new ProblemDetail(
                typeUri(getCode()),
                getCode(),
                "Batch too large",
                getStatus(),
                getMessage(),
                null,
                null,
                null,
                null,
                null,
                maxBatchSize);
    }
}
