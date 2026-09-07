package com.civicpulse.backend_spring.service.priority;

import java.util.List;

/**
 * A score and the human-readable evidence for it.
 *
 * The reasons are the product's headline explainability claim, so they are
 * returned together with the score and stored together — never recomputed
 * separately, and never allowed to drift apart.
 */
public record PriorityResult(double score, PriorityBand band, List<String> reasons) {
}
