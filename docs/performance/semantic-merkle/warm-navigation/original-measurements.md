# Paired benchmark measurements

10 independent alternating JVM pairs; every output assertion passed.
Paired changes are medians of worker ratios, not ratios of the displayed medians.
Sample p95 is the p95 of worker medians; it is not production request-tail latency.
95% intervals use the predeclared paired bootstrap. Lower is better.

## Latency

| Scenario | Before median ms | After median ms | Before sample p95 | After sample p95 | Paired change [95% interval] |
| --- | ---: | ---: | ---: | ---: | ---: |
| cold_jvm_32/cold_workspace | 2232.840 | 2215.971 | 2328.580 | 2752.039 | +1.0% [-4.8, +4.4] |
| cold_jvm_32/warm | 7.212 | 7.389 | 8.963 | 8.541 | -3.2% [-19.1, +19.8] |
| cold_jvm_32/body_edit | 69.017 | 58.353 | 127.589 | 87.495 | -22.7% [-29.6, -9.2] |
| cold_jvm_32/api_edit | 171.505 | 138.460 | 213.825 | 171.023 | -19.6% [-31.4, -4.2] |
| cold_jvm_32/rename_preview | 14.624 | 16.707 | 22.427 | 20.268 | +18.5% [-1.2, +25.7] |
| navigation_128/cold_workspace | 947.779 | 900.863 | 1211.473 | 1119.130 | +0.6% [-20.0, +8.2] |
| navigation_128/warm | 6.810 | 7.956 | 12.881 | 10.845 | +7.4% [-27.4, +38.6] |
| navigation_128/body_edit | 59.951 | 31.952 | 101.907 | 56.177 | -40.1% [-51.0, -34.3] |
| navigation_128/api_edit | 119.737 | 97.983 | 177.774 | 169.692 | -17.8% [-23.4, -4.7] |
| navigation_128/rename_preview | 7.151 | 6.528 | 24.004 | 7.958 | -16.7% [-41.7, +1.6] |
| navigation_512/cold_workspace | 2036.471 | 2017.013 | 2228.649 | 2427.946 | +1.2% [-4.7, +11.8] |
| navigation_512/warm | 17.657 | 10.300 | 21.655 | 13.174 | -43.9% [-50.6, -32.4] |
| navigation_512/body_edit | 129.402 | 51.470 | 153.452 | 72.261 | -57.5% [-63.1, -51.3] |
| navigation_512/api_edit | 168.656 | 111.509 | 196.264 | 149.314 | -33.4% [-40.1, -25.2] |
| navigation_512/rename_preview | 10.978 | 10.374 | 13.556 | 11.806 | -4.5% [-22.6, +5.9] |
| real_core/cold_workspace | 2852.329 | 2881.176 | 3072.758 | 3316.278 | -0.4% [-9.2, +8.4] |
| real_core/warm | 0.579 | 0.482 | 0.966 | 0.781 | -9.4% [-37.8, +3.4] |
| real_core/position_edit | 108.071 | 18.631 | 144.263 | 26.946 | -80.7% [-87.0, -78.5] |
| validation_512/precise_epoch | 0.002 | 0.002 | 0.002 | 0.002 | +43.4% [-1.0, +54.4] |
| validation_512/coarse_disk | 3.382 | 3.962 | 4.741 | 4.761 | +1.0% [-17.9, +69.7] |
| lifecycle_65/cold_modules | 136.187 | 158.621 | 169.185 | 191.699 | +15.2% [-4.5, +41.1] |
| lifecycle_65/warm_modules | 0.925 | 1.108 | 1.189 | 1.533 | +13.8% [-11.2, +81.2] |
| lifecycle_65/diagnostics_warm | 0.735 | 0.798 | 0.897 | 1.124 | +16.6% [-10.7, +55.2] |
| lifecycle_65/body_edit | 86.900 | 10.818 | 105.095 | 13.223 | -87.5% [-89.1, -84.9] |
| lifecycle_65/cross_module_api | 87.851 | 86.063 | 94.799 | 109.497 | +0.2% [-16.3, +28.4] |
| lifecycle_65/diagnostics_after_api | 1.071 | 1.150 | 1.337 | 1.329 | +3.8% [-9.1, +15.6] |
| lifecycle_65/documentation_position | 77.339 | 70.293 | 83.237 | 92.297 | +0.7% [-20.5, +18.0] |
| lifecycle_65/buffer_open | 19.977 | 12.860 | 23.170 | 14.595 | -32.6% [-48.1, -12.6] |
| lifecycle_65/buffer_api | 68.767 | 81.445 | 96.785 | 105.138 | +7.9% [-9.3, +38.6] |
| lifecycle_65/buffer_close | 75.730 | 84.987 | 88.638 | 91.271 | +1.0% [-14.7, +39.3] |
| lifecycle_65/file_add | 59.698 | 70.200 | 83.390 | 105.650 | +23.7% [+9.8, +31.5] |
| lifecycle_65/file_delete | 60.313 | 70.195 | 82.436 | 77.864 | +22.9% [-16.5, +49.9] |
| lifecycle_65/context_change | 59.272 | 66.943 | 89.741 | 82.468 | +17.5% [-9.5, +47.5] |
| lifecycle_65/context_restore | 57.959 | 63.746 | 76.317 | 75.966 | +15.6% [-19.0, +30.5] |
| overview_100/cold | 109.628 | 87.988 | 275.360 | 125.479 | -16.8% [-41.2, -0.4] |
| overview_100/warm | 0.398 | 0.409 | 0.859 | 0.745 | -13.1% [-39.6, +27.4] |
| overview_100/position_edit | 36.457 | 31.792 | 127.145 | 49.341 | +12.2% [-40.1, +25.6] |

## Allocated bytes

Decimal MB per request, reduced to a median within each JVM first.

| Scenario | Before MB | After MB | Paired change [95% interval] |
| --- | ---: | ---: | ---: |
| cold_jvm_32/cold_workspace | 45.921 | 44.285 | -3.6% [-3.6, -3.5] |
| cold_jvm_32/warm | 0.322 | 0.274 | -15.0% [-15.0, -15.0] |
| cold_jvm_32/body_edit | 3.034 | 2.071 | -30.7% [-32.5, -29.4] |
| cold_jvm_32/api_edit | 7.151 | 6.602 | -6.9% [-17.7, +11.8] |
| cold_jvm_32/rename_preview | 0.439 | 0.520 | +18.3% [-31.8, +145.9] |
| navigation_128/cold_workspace | 44.644 | 40.011 | -10.4% [-10.5, -10.1] |
| navigation_128/warm | 0.588 | 0.536 | -8.9% [-9.1, -8.9] |
| navigation_128/body_edit | 5.333 | 2.596 | -51.3% [-51.6, -51.1] |
| navigation_128/api_edit | 9.204 | 6.424 | -30.3% [-30.5, -29.8] |
| navigation_128/rename_preview | 0.492 | 0.488 | -0.9% [-1.8, +1.2] |
| navigation_512/cold_workspace | 183.473 | 160.205 | -12.7% [-13.0, -12.5] |
| navigation_512/warm | 1.507 | 1.504 | -0.2% [-0.2, +0.3] |
| navigation_512/body_edit | 18.286 | 7.677 | -58.0% [-58.1, -57.9] |
| navigation_512/api_edit | 24.920 | 14.215 | -42.9% [-43.0, -42.9] |
| navigation_512/rename_preview | 1.425 | 1.462 | +2.7% [+2.6, +2.9] |
| real_core/cold_workspace | 150.632 | 153.067 | +1.6% [+1.4, +2.1] |
| real_core/warm | 0.074 | 0.076 | +3.1% [+3.1, +3.5] |
| real_core/position_edit | 22.098 | 1.212 | -94.5% [-94.5, -94.5] |
| validation_512/precise_epoch | 0.000 | 0.000 | +0.0% [+0.0, +0.0] |
| validation_512/coarse_disk | 0.999 | 0.970 | -2.9% [-2.9, -2.9] |
| lifecycle_65/cold_modules | 27.101 | 24.049 | -11.2% [-11.4, -10.8] |
| lifecycle_65/warm_modules | 0.179 | 0.176 | -1.7% [-2.2, -1.3] |
| lifecycle_65/diagnostics_warm | 0.092 | 0.092 | -0.4% [-0.5, -0.3] |
| lifecycle_65/body_edit | 12.280 | 1.053 | -91.4% [-91.5, -91.4] |
| lifecycle_65/cross_module_api | 12.241 | 10.011 | -18.1% [-18.4, -17.9] |
| lifecycle_65/diagnostics_after_api | 0.092 | 0.092 | -0.4% [-0.4, -0.3] |
| lifecycle_65/documentation_position | 12.221 | 10.036 | -17.8% [-18.1, -17.7] |
| lifecycle_65/buffer_open | 2.774 | 1.057 | -61.9% [-62.1, -61.8] |
| lifecycle_65/buffer_api | 12.034 | 9.701 | -19.4% [-19.6, -19.1] |
| lifecycle_65/buffer_close | 12.143 | 9.812 | -19.2% [-19.4, -18.9] |
| lifecycle_65/file_add | 12.011 | 9.583 | -20.3% [-20.4, -19.8] |
| lifecycle_65/file_delete | 11.704 | 9.414 | -19.7% [-20.0, -19.4] |
| lifecycle_65/context_change | 11.498 | 9.131 | -20.6% [-20.8, -20.3] |
| lifecycle_65/context_restore | 11.478 | 9.131 | -20.5% [-20.8, -20.3] |
| overview_100/cold | 18.159 | 15.159 | -16.5% [-16.6, -16.3] |
| overview_100/warm | 0.022 | 0.021 | -2.2% [-2.2, -0.3] |
| overview_100/position_edit | 7.964 | 5.086 | -36.2% [-36.2, -35.9] |

## Whole-worker resources

Includes warmup and untimed correctness checks. RSS includes native memory.

| Variant | Peak RSS MiB | GC milliseconds | GC collections |
| --- | ---: | ---: | ---: |
| before | 500.09 | 358.0 | 12.0 |
| after | 479.36 | 346.0 | 11.0 |

Raw inputs, every request, min/max and summaries are retained with this report.
These tables do not waive correctness, latency-regression or code-reduction gates.
