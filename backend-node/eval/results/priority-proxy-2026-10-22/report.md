
## proxy-1.csv — 45 incidents ranked

Port check: all 45 key scores reproduced exactly.

| | Kendall τ-b vs officer | precision@10 |
|---|---:|---:|
| **Formula as shipped** | 0.423 | 0.600 |
| volume alone | 0.395 | |
| growth alone | 0.189 | |
| age alone | 0.412 | |
| spread alone | 0.353 | |

| Variant | τ-b | p@10 | τ-b half A | τ-b half B |
|---|---:|---:|---:|---:|
| shipped (age saturates at 14 d) | 0.423 | 0.600 | 0.414 | 0.411 |
| age saturates at 30 d | 0.556 | 0.600 | 0.538 | 0.557 |
| age saturates at 45 d | 0.516 | 0.600 | 0.514 | 0.491 |
| age weight 10% (to volume) | 0.412 | 0.600 | 0.433 | 0.370 |
| reports only (naive baseline) | 0.395 | 0.600 | 0.436 | 0.332 |

Rule (roadmap 8.4): adopt a variant only if it beats "shipped" on BOTH halves — a gain on one half alone is the variant fitting those incidents.

## proxy-2.csv — 45 incidents ranked

Port check: all 45 key scores reproduced exactly.

| | Kendall τ-b vs officer | precision@10 |
|---|---:|---:|
| **Formula as shipped** | 0.366 | 0.600 |
| volume alone | 0.321 | |
| growth alone | 0.171 | |
| age alone | 0.408 | |
| spread alone | 0.351 | |

| Variant | τ-b | p@10 | τ-b half A | τ-b half B |
|---|---:|---:|---:|---:|
| shipped (age saturates at 14 d) | 0.366 | 0.600 | 0.360 | 0.395 |
| age saturates at 30 d | 0.497 | 0.600 | 0.493 | 0.532 |
| age saturates at 45 d | 0.451 | 0.600 | 0.460 | 0.466 |
| age weight 10% (to volume) | 0.337 | 0.600 | 0.362 | 0.329 |
| reports only (naive baseline) | 0.321 | 0.600 | 0.361 | 0.290 |

Rule (roadmap 8.4): adopt a variant only if it beats "shipped" on BOTH halves — a gain on one half alone is the variant fitting those incidents.

## proxy-3.csv — 45 incidents ranked

Port check: all 45 key scores reproduced exactly.

| | Kendall τ-b vs officer | precision@10 |
|---|---:|---:|
| **Formula as shipped** | 0.320 | 0.600 |
| volume alone | 0.268 | |
| growth alone | 0.085 | |
| age alone | 0.340 | |
| spread alone | 0.316 | |

| Variant | τ-b | p@10 | τ-b half A | τ-b half B |
|---|---:|---:|---:|---:|
| shipped (age saturates at 14 d) | 0.320 | 0.600 | 0.405 | 0.247 |
| age saturates at 30 d | 0.430 | 0.600 | 0.484 | 0.385 |
| age saturates at 45 d | 0.398 | 0.600 | 0.442 | 0.350 |
| age weight 10% (to volume) | 0.282 | 0.600 | 0.353 | 0.189 |
| reports only (naive baseline) | 0.268 | 0.600 | 0.342 | 0.162 |

Rule (roadmap 8.4): adopt a variant only if it beats "shipped" on BOTH halves — a gain on one half alone is the variant fitting those incidents.

## Officer vs officer

| | τ-b | incidents in common |
|---|---:|---:|
| proxy-1.csv vs proxy-2.csv | 0.881 | 45 |
| proxy-1.csv vs proxy-3.csv | 0.749 | 45 |
| proxy-2.csv vs proxy-3.csv | 0.735 | 45 |

The formula cannot be expected to agree with an officer better than two officers agree with each other.
