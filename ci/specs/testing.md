# Pipeline testing

| Boundary | Evidence |
| --- | --- |
| Selection | Component tests of path ownership. |
| Module | Host compiler, analysis and formatting. |
| Git/shell | Real temporary repositories and process seams; expensive builds substituted. |
| Delivery | Actual component gates in pinned containers. |
| Release | Version tests and workflow registry checks. |
| NAS | Authorized isolated smoke deployment using disposable state. |
| Hosted execution | Pull-request and all-component CI. |

Selection tests do not prove delivery; all-component builds bypass selection. Both need independent evidence.

Use [application suite nomenclature](../../specs/testing.md#suite-names).
