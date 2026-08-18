# SWIRLS fine point-series method

## Source semantics

The SWIRLS rainfall product used by Weather Metro contains 16 forecast frames.
The frames are valid every 6 minutes from +30 to +120 minutes, but each frame is
**30-minute accumulated rainfall** (`mm / 30 min`). Therefore a frame value must
never be relabelled as rainfall that fell in a single 6-minute interval.

For one location, let `Y[i]` be the 30-minute accumulation in frame `i` and let
`x[j]` be the unknown rainfall in one 6-minute bucket. Because 30 minutes contains
five 6-minute buckets:

`Y[i] = x[i] + x[i+1] + x[i+2] + x[i+3] + x[i+4]`

Adjacent rolling windows therefore satisfy:

`Y[i+1] - Y[i] = x[i+5] - x[i]`

The 16 `Y` values provide a high-resolution **evolution of rolling 30-minute
rainfall** at 6-minute cadence. They do not, by themselves, uniquely determine
the 20 underlying 6-minute buckets: 16 equations describe 20 unknowns, leaving
four degrees of freedom.

## Safe reconstruction rule

Weather Metro only reconstructs 6-minute buckets when at least one complete
30-minute rolling window is genuinely dry (`Y[i] == 0` within a very small
numeric epsilon).

Rainfall is non-negative, so a zero 30-minute sum means all five constituent
6-minute buckets are zero. Those five known values remove the ambiguity. The
adjacent-window identity can then be propagated both forwards and backwards to
solve all 20 buckets.

After reconstruction, Weather Metro rebuilds every original 30-minute rolling
sum from the derived buckets. If any source sum cannot be reproduced within the
rounding tolerance, the reconstruction is discarded and the app falls back to
the directly observed 16-point rolling series.

## Spatial sampling

Each SWIRLS frame is sampled at the Weather Metro host location using bilinear
interpolation of the four neighbouring grid centres. This keeps the point series
consistent with the app's existing location model while respecting the source
product's approximately 2 km grid resolution.

## Presentation rules

1. The 16-point series may be labelled as **30-minute accumulated rainfall,
   sampled every 6 minutes** or an equivalent concise description.
2. A reconstructed bucket must be labelled as **derived / reconstructed** and
   never presented as an HKO-issued 6-minute accumulation.
3. If no dry anchor exists, Weather Metro must not create a synthetic 6-minute
   total by smoothing or optimization. It should show the 16 rolling values and
   their trend instead.
4. The first implementation should reuse already-loaded SWIRLS frames. Do not
   increase Current-page network traffic until latency and data-transfer cost
   have been measured on a real device.

This design deliberately separates temporal resolution from accumulation period:
SWIRLS gives a 6-minute sampling cadence of a 30-minute accumulation product.
