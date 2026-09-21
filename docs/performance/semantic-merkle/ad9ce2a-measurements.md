# Paired benchmark measurements

20 independent alternating JVM pairs; every output assertion passed.
Paired changes are medians of worker ratios, not ratios of the displayed medians.
Sample p95 is the p95 of worker medians; it is not production request-tail latency.
95% intervals use the predeclared paired bootstrap. Lower is better.

## Latency

| Scenario | Before median ms | After median ms | Before sample p95 | After sample p95 | Paired change [95% interval] |
| --- | ---: | ---: | ---: | ---: | ---: |
| cold_jvm_32/cold_workspace | 2345.513 | 2225.943 | 2731.289 | 2371.179 | -5.4% [-9.1, +0.7] |
| cold_jvm_32/warm | 6.898 | 8.160 | 9.686 | 10.232 | +22.0% [-7.6, +36.4] |
| cold_jvm_32/body_edit | 73.855 | 61.660 | 91.589 | 103.471 | -18.6% [-27.1, -1.0] |
| cold_jvm_32/api_edit | 181.837 | 150.944 | 221.479 | 218.726 | -12.5% [-20.0, +6.1] |
| cold_jvm_32/rename_preview | 17.654 | 17.763 | 30.432 | 25.837 | -12.9% [-19.3, +19.2] |
| navigation_128/cold_workspace | 1000.258 | 945.077 | 1165.242 | 1104.129 | -10.3% [-17.6, -0.9] |
| navigation_128/warm | 7.263 | 7.387 | 12.606 | 13.241 | +7.7% [-11.8, +42.2] |
| navigation_128/body_edit | 59.443 | 42.786 | 79.902 | 63.279 | -26.6% [-35.5, -14.3] |
| navigation_128/api_edit | 128.740 | 110.522 | 178.322 | 197.086 | -10.0% [-18.1, +11.4] |
| navigation_128/rename_preview | 7.486 | 7.314 | 11.260 | 14.232 | +0.2% [-11.8, +16.2] |
| navigation_512/cold_workspace | 2035.591 | 2178.496 | 2221.402 | 2502.694 | +4.7% [+0.7, +14.7] |
| navigation_512/warm | 20.001 | 10.852 | 23.503 | 15.327 | -46.2% [-51.2, -35.7] |
| navigation_512/body_edit | 137.455 | 53.439 | 161.329 | 69.918 | -61.7% [-64.5, -56.6] |
| navigation_512/api_edit | 193.681 | 116.808 | 217.582 | 156.010 | -37.9% [-44.9, -32.9] |
| navigation_512/rename_preview | 12.850 | 10.511 | 14.737 | 15.416 | -12.4% [-26.3, -3.1] |
| real_core/cold_workspace | 3271.670 | 2990.771 | 3706.862 | 3176.197 | -6.0% [-10.0, +1.8] |
| real_core/warm | 0.671 | 0.541 | 1.007 | 0.766 | -15.0% [-30.8, +1.1] |
| real_core/position_edit | 123.350 | 20.699 | 168.455 | 23.819 | -84.1% [-85.1, -82.2] |
| validation_512/precise_epoch | 0.002 | 0.002 | 0.003 | 0.002 | -2.6% [-32.5, +13.2] |
| validation_512/coarse_disk | 4.077 | 3.735 | 4.787 | 4.452 | -13.1% [-34.9, +26.0] |
| lifecycle_65/cold_modules | 144.712 | 153.120 | 187.978 | 168.793 | +6.0% [-1.4, +13.0] |
| lifecycle_65/warm_modules | 1.049 | 1.014 | 1.233 | 1.313 | -0.8% [-15.7, +7.9] |
| lifecycle_65/diagnostics_warm | 0.754 | 0.763 | 0.860 | 0.920 | +0.3% [-12.9, +13.3] |
| lifecycle_65/body_edit | 97.173 | 12.351 | 105.513 | 14.517 | -86.7% [-88.8, -85.1] |
| lifecycle_65/cross_module_api | 93.868 | 88.265 | 116.099 | 111.979 | -2.6% [-19.4, +4.0] |
| lifecycle_65/diagnostics_after_api | 1.252 | 1.245 | 1.460 | 1.480 | +5.3% [-6.6, +16.9] |
| lifecycle_65/documentation_position | 79.863 | 79.219 | 98.024 | 95.982 | -4.4% [-14.2, +5.3] |
| lifecycle_65/buffer_open | 21.919 | 13.094 | 30.507 | 17.239 | -39.2% [-50.5, -32.8] |
| lifecycle_65/buffer_api | 86.702 | 83.644 | 109.799 | 113.223 | +0.2% [-4.7, +4.9] |
| lifecycle_65/buffer_close | 88.832 | 79.533 | 105.814 | 92.285 | -5.8% [-18.6, +9.4] |
| lifecycle_65/file_add | 78.382 | 69.835 | 109.235 | 87.636 | -16.1% [-22.7, -1.5] |
| lifecycle_65/file_delete | 78.586 | 70.206 | 106.885 | 89.100 | -5.8% [-18.1, +5.2] |
| lifecycle_65/context_change | 74.431 | 66.789 | 91.863 | 92.875 | -11.0% [-20.8, -5.6] |
| lifecycle_65/context_restore | 68.258 | 68.678 | 97.544 | 97.920 | -0.5% [-12.3, +17.3] |
| overview_100/cold | 106.547 | 97.506 | 169.615 | 119.380 | -12.9% [-23.2, -2.6] |
| overview_100/warm | 0.336 | 0.341 | 0.565 | 0.485 | -0.5% [-15.2, +16.8] |
| overview_100/position_edit | 39.001 | 37.469 | 51.766 | 45.966 | -5.5% [-21.3, +21.9] |

## Allocated bytes

Decimal MB per request, reduced to a median within each JVM first.

| Scenario | Before MB | After MB | Paired change [95% interval] |
| --- | ---: | ---: | ---: |
| cold_jvm_32/cold_workspace | 45.934 | 44.352 | -3.4% [-3.5, -3.4] |
| cold_jvm_32/warm | 0.324 | 0.321 | -0.8% [-0.9, -0.8] |
| cold_jvm_32/body_edit | 3.034 | 2.123 | -30.1% [-30.8, -28.6] |
| cold_jvm_32/api_edit | 7.450 | 7.044 | -5.0% [-10.0, -1.3] |
| cold_jvm_32/rename_preview | 0.440 | 0.456 | +3.6% [+3.6, +16.9] |
| navigation_128/cold_workspace | 44.678 | 40.091 | -10.3% [-10.5, -10.1] |
| navigation_128/warm | 0.589 | 0.584 | -0.9% [-1.0, -0.8] |
| navigation_128/body_edit | 5.354 | 2.633 | -50.7% [-50.9, -50.5] |
| navigation_128/api_edit | 9.230 | 6.487 | -29.7% [-29.8, -29.6] |
| navigation_128/rename_preview | 0.491 | 0.495 | +1.6% [+0.4, +3.9] |
| navigation_512/cold_workspace | 183.819 | 160.226 | -12.8% [-12.9, -12.7] |
| navigation_512/warm | 1.512 | 1.560 | +2.9% [+2.6, +3.1] |
| navigation_512/body_edit | 18.358 | 7.741 | -57.7% [-57.9, -57.7] |
| navigation_512/api_edit | 25.019 | 14.294 | -42.9% [-43.0, -42.8] |
| navigation_512/rename_preview | 1.427 | 1.477 | +3.2% [+3.0, +3.4] |
| real_core/cold_workspace | 150.862 | 153.188 | +1.6% [+1.3, +1.8] |
| real_core/warm | 0.074 | 0.077 | +3.1% [+3.1, +3.1] |
| real_core/position_edit | 22.149 | 1.211 | -94.6% [-94.6, -94.5] |
| validation_512/precise_epoch | 0.000 | 0.000 | +0.0% [+0.0, +0.0] |
| validation_512/coarse_disk | 0.999 | 0.970 | -2.9% [-2.9, -2.3] |
| lifecycle_65/cold_modules | 27.130 | 23.991 | -11.5% [-11.9, -11.5] |
| lifecycle_65/warm_modules | 0.179 | 0.177 | -1.8% [-1.8, -1.7] |
| lifecycle_65/diagnostics_warm | 0.103 | 0.102 | -0.7% [-0.8, -0.6] |
| lifecycle_65/body_edit | 12.384 | 1.064 | -91.4% [-91.5, -91.4] |
| lifecycle_65/cross_module_api | 12.328 | 10.036 | -18.6% [-18.9, -18.4] |
| lifecycle_65/diagnostics_after_api | 0.103 | 0.102 | -0.7% [-0.8, -0.6] |
| lifecycle_65/documentation_position | 12.303 | 10.055 | -18.4% [-18.4, -17.9] |
| lifecycle_65/buffer_open | 2.803 | 1.069 | -61.8% [-62.1, -61.7] |
| lifecycle_65/buffer_api | 12.110 | 9.717 | -19.7% [-19.8, -19.6] |
| lifecycle_65/buffer_close | 12.213 | 9.818 | -19.4% [-19.5, -19.3] |
| lifecycle_65/file_add | 12.052 | 9.600 | -20.2% [-20.5, -19.9] |
| lifecycle_65/file_delete | 11.793 | 9.444 | -19.6% [-20.2, -19.5] |
| lifecycle_65/context_change | 11.567 | 9.158 | -20.7% [-20.9, -20.6] |
| lifecycle_65/context_restore | 11.554 | 9.154 | -20.6% [-20.8, -20.6] |
| overview_100/cold | 18.153 | 15.187 | -16.4% [-16.6, -16.1] |
| overview_100/warm | 0.022 | 0.021 | -4.1% [-4.8, -3.8] |
| overview_100/position_edit | 7.968 | 5.101 | -36.0% [-36.2, -35.7] |

## Whole-worker resources

Includes warmup and untimed correctness checks. RSS includes native memory.

| Variant | Peak RSS MiB | GC milliseconds | GC collections |
| --- | ---: | ---: | ---: |
| before | 500.04 | 339.5 | 12.5 |
| after | 475.70 | 369.5 | 11.0 |

Raw inputs, every request, min/max and summaries are retained with this report.
These tables do not waive correctness, latency-regression or code-reduction gates.
