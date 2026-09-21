# Paired benchmark measurements

10 independent alternating JVM pairs; every output assertion passed.
Paired changes are medians of worker ratios, not ratios of the displayed medians.
Sample p95 is the p95 of worker medians; it is not production request-tail latency.
95% intervals use the predeclared paired bootstrap. Lower is better.

## Latency

| Scenario | Before median ms | After median ms | Before sample p95 | After sample p95 | Paired change [95% interval] |
| --- | ---: | ---: | ---: | ---: | ---: |
| cold_jvm_32/cold_workspace | 2250.032 | 2361.235 | 2808.888 | 2952.997 | +6.3% [-4.8, +12.7] |
| cold_jvm_32/warm | 7.791 | 6.933 | 10.002 | 8.444 | -16.5% [-21.3, +20.4] |
| cold_jvm_32/body_edit | 59.706 | 55.728 | 91.297 | 77.519 | +1.3% [-25.6, +19.3] |
| cold_jvm_32/api_edit | 160.343 | 166.190 | 193.639 | 191.179 | +9.7% [-18.4, +32.4] |
| cold_jvm_32/rename_preview | 14.683 | 15.751 | 19.068 | 37.103 | +8.6% [+0.4, +69.3] |
| navigation_128/cold_workspace | 887.609 | 858.316 | 983.914 | 1134.023 | -7.2% [-12.3, +1.0] |
| navigation_128/warm | 8.585 | 8.971 | 11.398 | 11.039 | +26.6% [-9.6, +46.2] |
| navigation_128/body_edit | 38.327 | 38.944 | 53.943 | 63.872 | -4.3% [-20.7, +23.2] |
| navigation_128/api_edit | 114.898 | 107.587 | 154.861 | 143.252 | -3.6% [-11.5, +19.9] |
| navigation_128/rename_preview | 6.380 | 6.773 | 9.057 | 10.738 | +11.4% [-12.9, +41.4] |
| navigation_512/cold_workspace | 2023.028 | 2039.908 | 2287.228 | 2495.324 | +3.5% [-4.4, +9.7] |
| navigation_512/warm | 10.869 | 9.815 | 14.509 | 16.396 | -12.8% [-28.1, +6.5] |
| navigation_512/body_edit | 54.752 | 49.587 | 65.730 | 64.213 | -10.9% [-17.5, +5.4] |
| navigation_512/api_edit | 114.386 | 110.074 | 145.914 | 130.171 | +3.3% [-21.9, +12.9] |
| navigation_512/rename_preview | 9.606 | 8.827 | 15.591 | 12.149 | -6.2% [-20.4, +8.9] |
| real_core/cold_workspace | 2761.632 | 2863.209 | 3133.435 | 3585.100 | +6.9% [-2.7, +13.4] |
| real_core/warm | 0.504 | 0.549 | 1.406 | 0.728 | -11.8% [-29.6, +41.9] |
| real_core/position_edit | 21.240 | 20.286 | 27.050 | 29.389 | +20.4% [-21.3, +34.1] |
| validation_512/precise_epoch | 0.002 | 0.002 | 0.002 | 0.002 | +0.8% [-20.0, +24.6] |
| validation_512/coarse_disk | 3.446 | 2.822 | 5.149 | 4.818 | -6.2% [-28.9, +21.7] |
| lifecycle_65/cold_modules | 147.221 | 138.570 | 166.529 | 199.392 | -4.5% [-17.8, +15.9] |
| lifecycle_65/warm_modules | 1.109 | 0.999 | 1.213 | 1.234 | -10.4% [-21.7, +4.0] |
| lifecycle_65/diagnostics_warm | 0.768 | 0.720 | 1.076 | 1.069 | -3.5% [-33.2, +15.8] |
| lifecycle_65/body_edit | 12.715 | 12.588 | 15.841 | 19.668 | -0.6% [-21.3, +17.4] |
| lifecycle_65/cross_module_api | 93.011 | 84.270 | 128.405 | 101.617 | -8.4% [-28.0, +5.5] |
| lifecycle_65/diagnostics_after_api | 1.205 | 1.213 | 3.556 | 1.400 | +1.2% [-19.0, +7.3] |
| lifecycle_65/documentation_position | 83.632 | 76.827 | 96.505 | 95.392 | -6.0% [-17.0, -0.5] |
| lifecycle_65/buffer_open | 13.476 | 13.760 | 21.882 | 21.463 | +1.7% [-29.2, +27.9] |
| lifecycle_65/buffer_api | 78.965 | 79.499 | 99.768 | 99.674 | -16.7% [-23.0, +25.5] |
| lifecycle_65/buffer_close | 81.812 | 76.302 | 102.038 | 125.986 | -5.1% [-22.3, +23.1] |
| lifecycle_65/file_add | 75.268 | 70.131 | 82.489 | 88.088 | +2.1% [-17.8, +20.5] |
| lifecycle_65/file_delete | 70.929 | 65.863 | 92.587 | 83.648 | -3.9% [-16.1, +8.6] |
| lifecycle_65/context_change | 69.900 | 59.768 | 85.498 | 80.282 | -9.0% [-27.4, +12.3] |
| lifecycle_65/context_restore | 67.052 | 66.536 | 77.482 | 77.129 | +4.1% [-10.1, +23.8] |
| overview_100/cold | 97.958 | 102.630 | 130.223 | 112.671 | -2.6% [-12.1, +17.0] |
| overview_100/warm | 0.420 | 0.472 | 0.495 | 0.532 | +20.5% [-23.7, +39.9] |
| overview_100/position_edit | 32.847 | 35.460 | 39.704 | 53.519 | -0.6% [-7.6, +29.5] |

## Allocated bytes

Decimal MB per request, reduced to a median within each JVM first.

| Scenario | Before MB | After MB | Paired change [95% interval] |
| --- | ---: | ---: | ---: |
| cold_jvm_32/cold_workspace | 44.325 | 44.257 | -0.1% [-0.2, -0.1] |
| cold_jvm_32/warm | 0.319 | 0.273 | -14.3% [-14.3, -14.2] |
| cold_jvm_32/body_edit | 2.111 | 2.066 | -1.0% [-3.6, +2.5] |
| cold_jvm_32/api_edit | 6.450 | 6.993 | +0.7% [-11.1, +14.3] |
| cold_jvm_32/rename_preview | 0.454 | 0.448 | -1.4% [-35.2, -1.4] |
| navigation_128/cold_workspace | 39.986 | 39.927 | -0.0% [-0.3, +0.2] |
| navigation_128/warm | 0.581 | 0.534 | -8.1% [-8.1, -7.9] |
| navigation_128/body_edit | 2.622 | 2.576 | -1.6% [-3.6, -1.1] |
| navigation_128/api_edit | 6.422 | 6.389 | -0.5% [-1.4, +0.4] |
| navigation_128/rename_preview | 0.492 | 0.486 | -1.2% [-3.7, -0.4] |
| navigation_512/cold_workspace | 159.588 | 159.840 | +0.2% [-0.3, +0.4] |
| navigation_512/warm | 1.550 | 1.503 | -3.1% [-3.5, -2.7] |
| navigation_512/body_edit | 7.681 | 7.625 | -0.7% [-1.1, -0.3] |
| navigation_512/api_edit | 14.188 | 14.124 | -0.5% [-0.6, -0.1] |
| navigation_512/rename_preview | 1.463 | 1.454 | -0.6% [-0.9, -0.0] |
| real_core/cold_workspace | 153.345 | 153.023 | -0.2% [-0.5, +0.2] |
| real_core/warm | 0.076 | 0.076 | -0.2% [-2.1, +1.1] |
| real_core/position_edit | 1.209 | 1.207 | -0.1% [-0.8, +0.4] |
| validation_512/precise_epoch | 0.000 | 0.000 | +0.0% [+0.0, +0.0] |
| validation_512/coarse_disk | 0.970 | 0.970 | -1.1% [-3.0, +1.5] |
| lifecycle_65/cold_modules | 24.051 | 24.019 | +0.2% [-0.6, +0.6] |
| lifecycle_65/warm_modules | 0.175 | 0.175 | -0.2% [-1.2, +1.2] |
| lifecycle_65/diagnostics_warm | 0.090 | 0.090 | -0.2% [-0.2, -0.0] |
| lifecycle_65/body_edit | 1.046 | 1.049 | +0.1% [-0.5, +1.0] |
| lifecycle_65/cross_module_api | 9.988 | 10.022 | +0.4% [+0.2, +0.5] |
| lifecycle_65/diagnostics_after_api | 0.090 | 0.090 | -0.2% [-0.2, -0.0] |
| lifecycle_65/documentation_position | 9.998 | 10.031 | +0.4% [+0.2, +0.6] |
| lifecycle_65/buffer_open | 1.050 | 1.052 | +0.1% [-0.5, +1.0] |
| lifecycle_65/buffer_api | 9.672 | 9.682 | +0.2% [-0.1, +0.4] |
| lifecycle_65/buffer_close | 9.785 | 9.799 | +0.2% [-0.0, +0.4] |
| lifecycle_65/file_add | 9.570 | 9.579 | +0.3% [-0.1, +0.4] |
| lifecycle_65/file_delete | 9.399 | 9.408 | +0.3% [-0.0, +0.4] |
| lifecycle_65/context_change | 9.115 | 9.122 | +0.3% [+0.1, +0.5] |
| lifecycle_65/context_restore | 9.114 | 9.119 | +0.3% [+0.0, +0.5] |
| overview_100/cold | 15.152 | 15.213 | +0.3% [-0.1, +0.7] |
| overview_100/warm | 0.021 | 0.021 | -0.1% [-0.2, +0.1] |
| overview_100/position_edit | 5.086 | 5.092 | +0.2% [-0.1, +1.6] |

## Whole-worker resources

Includes warmup and untimed correctness checks. RSS includes native memory.

| Variant | Peak RSS MiB | GC milliseconds | GC collections |
| --- | ---: | ---: | ---: |
| before | 486.61 | 369.0 | 11.0 |
| after | 478.84 | 358.0 | 11.0 |

Raw inputs, every request, min/max and summaries are retained with this report.
These tables do not waive correctness, latency-regression or code-reduction gates.
