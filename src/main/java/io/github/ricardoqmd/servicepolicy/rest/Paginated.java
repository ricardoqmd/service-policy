package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response envelope for collection endpoints (ADR-017): a {@code data} array plus a
 * {@code pagination} block. Single-resource responses are returned bare, without this envelope.
 *
 * @param <T> the element type of {@code data}.
 */
@Schema(description = "Paginated collection envelope.")
public record Paginated<T>(List<T> data, Pagination pagination) {

    /** Pagination block (ADR-017). {@code page} is 1-indexed. */
    @Schema(description = "Pagination metadata; page is 1-indexed.")
    public record Pagination(int page, int size, int totalPages, long totalElements) {

        /** Builds a pagination block, computing {@code totalPages} from {@code totalElements}. */
        public static Pagination of(int page, int size, long totalElements) {
            int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
            return new Pagination(page, size, totalPages, totalElements);
        }
    }
}
